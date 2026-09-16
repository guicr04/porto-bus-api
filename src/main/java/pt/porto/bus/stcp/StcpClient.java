package pt.porto.bus.stcp;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import pt.porto.bus.model.RealtimeStop;
import pt.porto.bus.model.RouteDirectionStops;
import pt.porto.bus.model.RouteSchedule;
import pt.porto.bus.model.RouteServices;
import pt.porto.bus.model.RouteShape;
import pt.porto.bus.model.StopRoutes;
import pt.porto.bus.model.StopSchedule;
import pt.porto.bus.model.StopServices;
import pt.porto.bus.shared.AppProperties;
import pt.porto.bus.shared.LisbonTime;
import pt.porto.bus.shared.UpstreamException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Client for stcp.pt's public JSON API — the one its own website calls (README
 * §2B). Undocumented, no SLA: cache politely, don't hammer.
 *
 * <p>Stop-centric: /stops/{code}/realtime, /routes, /services, /schedule.
 * Line-centric: /route/{line}/shape, /stops/direction, /services, /schedule.
 */
@Component
public class StcpClient {

  private final RestClient http;
  private final JsonMapper json;
  private final String base;
  private final Clock clock;
  private final Cache<String, RealtimeStop> realtimeCache;

  public StcpClient(RestClient.Builder builder, JsonMapper json, AppProperties props, Clock clock) {
    Duration timeout = Duration.ofMillis(props.httpTimeoutMs());
    HttpClient jdk =
        HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
    factory.setReadTimeout(timeout);

    this.http =
        builder
            .requestFactory(factory)
            .defaultHeader(HttpHeaders.USER_AGENT, props.userAgent())
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .build();
    this.json = json;
    this.base = props.stcpApiBase();
    this.clock = clock;
    // Live arrivals change on the order of tens of seconds, and the board polls
    // a dozen stops at a time from an always-on display. A short TTL costs the
    // display nothing and cuts upstream traffic by an order of magnitude.
    this.realtimeCache =
        props.realtimeTtlMs() > 0
            ? Caffeine.newBuilder().expireAfterWrite(Duration.ofMillis(props.realtimeTtlMs())).build()
            : null;
  }

  // ---- stop-centric --------------------------------------------------------

  /** Live arrivals for a stop, cached for REALTIME_TTL_MS. Failures are never cached. */
  public RealtimeStop stopRealtime(String stopCode) {
    if (realtimeCache != null) {
      RealtimeStop hit = realtimeCache.getIfPresent(stopCode);
      if (hit != null) return hit;
    }
    RealtimeStop fresh = StcpParsers.realtime(getJson("/stops/" + enc(stopCode) + "/realtime"), stopCode);
    if (realtimeCache != null) realtimeCache.put(stopCode, fresh);
    return fresh;
  }

  public StopRoutes stopRoutes(String stopCode) {
    return StcpParsers.stopRoutes(getJson("/stops/" + enc(stopCode) + "/routes"));
  }

  /** @param date YYYY-MM-DD, or null for today in Lisbon */
  public StopServices stopServices(String stopCode, String date) {
    String q = query(Map.of("date", date != null ? date : today()));
    return StcpParsers.stopServices(getJson("/stops/" + enc(stopCode) + "/services?" + q));
  }

  /** @param serviceId a GTFS-style key, e.g. "DOM|FERIADO:FLUXO 3.1 20260718" */
  public StopSchedule stopSchedule(String stopCode, String routeId, String serviceId, int directionId) {
    var params = new LinkedHashMap<String, Object>();
    params.put("route_id", routeId);
    params.put("service_id", serviceId);
    params.put("direction_id", directionId);
    return StcpParsers.stopSchedule(getJson("/stops/" + enc(stopCode) + "/schedule?" + query(params)), stopCode);
  }

  // ---- line-centric --------------------------------------------------------

  public RouteShape routeShape(String line, int directionId) {
    String q = query(Map.of("direction_id", directionId));
    return StcpParsers.shape(getJson("/route/" + enc(line) + "/shape?" + q), line);
  }

  public RouteDirectionStops routeStops(String line, int directionId) {
    String q = query(Map.of("direction_id", directionId));
    return StcpParsers.routeDirectionStops(getJson("/route/" + enc(line) + "/stops/direction?" + q), line);
  }

  /** @param date YYYY-MM-DD, or null for today in Lisbon */
  public RouteServices routeServices(String line, String date) {
    String q = query(Map.of("date", date != null ? date : today()));
    return StcpParsers.routeServices(getJson("/route/" + enc(line) + "/services?" + q), line);
  }

  public RouteSchedule routeSchedule(String line, String serviceId, int directionId) {
    var params = new LinkedHashMap<String, Object>();
    params.put("service_id", serviceId);
    params.put("direction_id", directionId);
    return StcpParsers.routeSchedule(getJson("/route/" + enc(line) + "/schedule?" + query(params)), line);
  }

  // ---- transport -----------------------------------------------------------

  /** GET a path under the API base. `path` is already encoded and may carry a query. */
  JsonNode getJson(String path) {
    URI uri = URI.create(base + path);
    try {
      return http.get()
          .uri(uri)
          .exchange(
              (request, response) -> {
                int status = response.getStatusCode().value();
                if (status < 200 || status > 299) {
                  throw new UpstreamException(status, "stcp.pt returned " + status + " for " + path);
                }
                // Parsed from bytes rather than through a message converter, so a
                // wrong Content-Type header can't turn a good payload into a failure.
                return json.readTree(response.getBody().readAllBytes());
              });
    } catch (UpstreamException e) {
      throw e;
    } catch (RestClientException | JacksonException e) {
      throw new UpstreamException(null, messageOf(e), e);
    }
  }

  /** Say what happened in words; the JDK's own messages are often just a class name. */
  private static String messageOf(Exception e) {
    for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
        return "stcp.pt timed out";
      }
      if (t instanceof ConnectException || t instanceof ClosedChannelException || t instanceof UnresolvedAddressException) {
        return "could not connect to stcp.pt";
      }
      if (t instanceof JacksonException) return "stcp.pt returned a body that is not JSON";
    }
    Throwable root = e;
    while (root.getCause() != null && root.getCause() != root) root = root.getCause();
    String m = root.getMessage();
    return m != null && !m.isBlank() ? m : root.getClass().getSimpleName();
  }

  private String today() {
    return LisbonTime.isoDate(clock.instant());
  }

  private static String enc(String s) {
    return pt.porto.bus.shared.UriEncoding.component(s);
  }

  private static String query(Map<String, ?> params) {
    return pt.porto.bus.shared.UriEncoding.query(params);
  }
}
