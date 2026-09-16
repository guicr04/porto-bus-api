package pt.porto.bus.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pt.porto.bus.gtfs.Fixtures;
import pt.porto.bus.gtfs.GtfsIngest;
import pt.porto.bus.live.CircuitBreaker;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The HTTP contract end to end: a real server, a real store, and stcp.pt played
 * by {@link FakeStcp}. What the Node version guaranteed, and the iOS app relies
 * on, is asserted here at the wire.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "app.boot-refresh=false",
      "app.scheduled-refresh=false",
      "app.realtime-ttl-ms=0",
      "app.http-timeout-ms=2000",
      "app.home-lat=41.1482",
      "app.home-lon=-8.6108",
      "app.home-label=TEST",
    })
class ApiIntegrationTest {

  static final FakeStcp STCP = new FakeStcp();
  @TempDir static Path dir;

  /** 07:30 on the last valid day of the fixture feed. */
  static final Instant MORNING = OffsetDateTime.parse("2026-08-15T07:30:00+01:00").toInstant();

  static final MutableClock CLOCK = new MutableClock();

  static final class MutableClock extends Clock {
    volatile Instant now = MORNING;

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @TestConfiguration
  static class TestClock {
    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("app.stcp-api-base", STCP::baseUrl);
    r.add("app.db-path", () -> dir.resolve("api.db").toString());
  }

  @AfterAll
  static void stop() {
    STCP.close();
  }

  @Value("${local.server.port}")
  int port;

  @Autowired GtfsIngest ingest;
  @Autowired CircuitBreaker breaker;

  static boolean loaded;
  final HttpClient http = HttpClient.newHttpClient();
  final JsonMapper json = JsonMapper.builder().build();

  record Reply(int status, String contentType, String body) {
    JsonNode json() {
      return JsonMapper.builder().build().readTree(body);
    }
  }

  @BeforeEach
  void setUp() {
    if (!loaded) {
      ingest.load(Fixtures.zip(Fixtures.basicFeed()), new GtfsIngest.FeedSource("test://feed", "api test feed"));
      loaded = true;
    }
    STCP.reset();
    breaker.reset();
    CLOCK.now = MORNING;
  }

  Reply get(String path) throws Exception {
    HttpResponse<String> r =
        http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(), HttpResponse.BodyHandlers.ofString());
    return new Reply(r.statusCode(), r.headers().firstValue("Content-Type").orElse(""), r.body());
  }

  // ---- live, and the fallback seam -------------------------------------------

  @Test
  void aLiveBoardIsServedSnakeCaseWithNullsPresent() throws Exception {
    STCP.on(
        "/stops/AAA1/realtime",
        200,
        "{'stop_id':'AAA1','stop_name':'AV. ALIADOS','data_source':'realtime','arrivals':[{'route_short_name':'500',"
            + "'trip_headsign':'Matosinhos','arrival_minutes':4,'estimated_arrival_time':'2026-08-15T07:34:00+01:00',"
            + "'status':'ON_TIME','delay_minutes':0,'route_color':'#417DBD','route_text_color':'#FFFFFF',"
            + "'trip_id':'T1'}]}");

    Reply r = get("/stops/AAA1/realtime");

    assertThat(r.status()).isEqualTo(200);
    assertThat(r.contentType()).startsWith("application/json");
    JsonNode body = r.json();
    assertThat(body.path("data_source").asString()).isEqualTo("realtime");
    JsonNode a = body.path("arrivals").get(0);
    assertThat(a.path("arrival_minutes").asInt()).isEqualTo(4);
    assertThat(a.path("text_color").asString()).isEqualTo("#FFFFFF");
    // A Swift decoder with non-optional keys needs the key present, even when null.
    assertThat(a.has("scheduled_arrival_time")).isTrue();
    assertThat(a.get("scheduled_arrival_time").isNull()).isTrue();
    // Declared order, not Jackson 3's alphabetical default.
    assertThat(r.body()).startsWith("{\"stop_code\":\"AAA1\",\"stop_name\"");
  }

  @Test
  void anUpstreamOutageFallsBackToTheTimetableAndSaysSo() throws Exception {
    STCP.on("/stops/BBB2/realtime", 503, "{}");

    Reply r = get("/stops/BBB2/realtime");

    assertThat(r.status()).isEqualTo(200);
    JsonNode body = r.json();
    assertThat(body.path("data_source").asString()).isEqualTo("scheduled");
    assertThat(body.path("stop_name").asString()).isEqualTo("CARMO");
    JsonNode a = body.path("arrivals").get(0);
    assertThat(a.path("line").asString()).isEqualTo("500");
    assertThat(a.path("arrival_minutes").asInt()).isEqualTo(40); // 08:10 from 07:30
    assertThat(a.path("scheduled_arrival_time").asString()).isEqualTo("08:10");
    assertThat(a.get("status").isNull()).as("never invent ON_TIME").isTrue();
    assertThat(a.path("trip_id").asString()).isEqualTo("T1");
  }

  @Test
  void aStopUpstreamDoesNotKnowIsA404NotAFabricatedBoard() throws Exception {
    Reply r = get("/stops/ZZZ9/realtime");

    assertThat(r.status()).isEqualTo(404);
    assertThat(r.json().path("detail").asString()).isEqualTo("stcp.pt returned 404 for /stops/ZZZ9/realtime");
    assertThat(breaker.state().consecutiveFailures()).as("a 404 is not an outage").isZero();
  }

  @Test
  void anOutageForAStopTheStoreDoesNotKnowIsStillA404() throws Exception {
    STCP.on("/stops/ZZZ9/realtime", 502, "{}");
    Reply r = get("/stops/ZZZ9/realtime");
    assertThat(r.status()).isEqualTo(404);
    assertThat(r.json().path("detail").asString()).isEqualTo("Stop 'ZZZ9' not found");
  }

  @Test
  void anExpiredFeedIsRefusedWithA503RatherThanServedAsToday() throws Exception {
    CLOCK.now = OffsetDateTime.parse("2026-08-16T07:30:00+01:00").toInstant();
    STCP.on("/stops/BBB2/realtime", 503, "{}");

    Reply r = get("/stops/BBB2/realtime");

    assertThat(r.status()).isEqualTo(503);
    assertThat(r.json().path("detail").asString()).contains("past its validity window");
  }

  @Test
  void theBreakerStopsCallingUpstreamAfterThreeOutagesThenProbes() throws Exception {
    STCP.on("/stops/BBB2/realtime", 503, "{}");

    for (int i = 0; i < 5; i++) assertThat(get("/stops/BBB2/realtime").status()).isEqualTo(200);
    assertThat(STCP.hits("/stops/BBB2/realtime")).as("calls 4 and 5 skip upstream").isEqualTo(3);
    JsonNode upstream = get("/health").json().path("upstream");
    assertThat(upstream.path("open").asBoolean()).isTrue();
    assertThat(upstream.path("consecutive_failures").asInt()).isEqualTo(3);

    CLOCK.now = CLOCK.now.plusSeconds(31);
    get("/stops/BBB2/realtime");
    assertThat(STCP.hits("/stops/BBB2/realtime")).as("one probe after the cooldown").isEqualTo(4);
  }

  @Test
  void lineGeometryFallsBackToTheStoreAndUnknownLinesAre404() throws Exception {
    STCP.on("/route/500/shape", 500, "{}");
    STCP.on("/route/NOPE/shape", 500, "{}");

    Reply shape = get("/lines/500/shape?direction_id=0");
    assertThat(shape.status()).isEqualTo(200);
    assertThat(shape.json().path("data_source").asString()).isEqualTo("scheduled");
    assertThat(shape.json().path("coordinates").get(1).path("lng").asDouble()).isEqualTo(-8.62);

    Reply missing = get("/lines/NOPE/shape");
    assertThat(missing.status()).isEqualTo(404);
    assertThat(missing.json().path("detail").asString()).isEqualTo("No shape for line 'NOPE' direction 0");
  }

  @Test
  void departuresSendServiceIdsUpstreamWithPercent20NotPlus() throws Exception {
    STCP.on("/stops/AAA1/realtime", 200, "{'data_source':'realtime','arrivals':[]}");
    STCP.on("/stops/AAA1/routes", 200, "{'display_routes':[],'dropdown_routes':[{'route_id':'500','direction_id':0}]}");
    STCP.on("/stops/AAA1/services", 200, "{'services':[],'active_service_id':'DOM|FERIADO:FLUXO 3.1 20260718'}");
    STCP.on(
        "/stops/AAA1/schedule",
        200,
        "{'schedule':{'08':[{'departure_time':'08:00:00','headsign':'Matosinhos','direction_id':0}]}}");

    Reply r = get("/stops/AAA1/departures?line=500");

    assertThat(r.status()).isEqualTo(200);
    assertThat(STCP.requests)
        .contains("/api/stops/AAA1/schedule?route_id=500&service_id=DOM%7CFERIADO%3AFLUXO%203.1%2020260718&direction_id=0");
    JsonNode body = r.json();
    assertThat(body.path("service_id").asString()).isEqualTo("DOM|FERIADO:FLUXO 3.1 20260718");
    assertThat(body.path("departures").get(0).path("time").asString()).isEqualTo("08:00");
    assertThat(body.path("departures").get(0).path("source").asString()).isEqualTo("scheduled");
  }

  @Test
  void departuresSurviveAFullOutageFromTheStore() throws Exception {
    // Nothing configured upstream answers but 503.
    for (String p : new String[] {"realtime", "routes", "services", "schedule"}) STCP.on("/stops/AAA1/" + p, 503, "{}");

    JsonNode body = get("/stops/AAA1/departures?line=500").json();

    assertThat(body.path("data_source").asString()).isEqualTo("scheduled");
    assertThat(body.path("service_id").asString()).as("today's service, from the store").isEqualTo("SVC1");
    assertThat(body.path("departures")).extracting(d -> d.path("time").asString()).containsExactly("08:00", "09:00");
    assertThat(body.path("departures").get(0).path("trip_id").asString()).isEqualTo("T1");
  }

  // ---- trips ------------------------------------------------------------------

  @Test
  void tripIdsWithPipesResolveEncodedOrRaw() throws Exception {
    Reply byId = get("/trips/T1/stops");
    assertThat(byId.status()).isEqualTo(200);
    assertThat(byId.json().path("match").asString()).isEqualTo("exact");
    assertThat(byId.json().path("stops")).hasSize(2);

    Reply encoded = get("/trips/500_0_1%7C280%7CD1/stops?line=500&stop=AAA1&eta_minutes=30");
    assertThat(encoded.status()).isEqualTo(200);
    assertThat(encoded.json().path("requested_trip_id").asString()).isEqualTo("500_0_1|280|D1");
    assertThat(encoded.json().path("match").asString()).isEqualTo("pattern");

    // Express accepted a raw pipe; so must this.
    assertThat(rawStatusLine("/trips/500_0_1|280|D1/stops?line=500&stop=AAA1&eta_minutes=30")).contains(" 200");

    Reply unknown = get("/trips/nope/stops");
    assertThat(unknown.status()).isEqualTo(404);
    assertThat(unknown.json().path("detail").asString()).isEqualTo("Could not resolve trip 'nope' in the static feed");
  }

  // ---- the board ----------------------------------------------------------------

  @Test
  void theTextBoardIsPlainTextAndFallsBackToHome() throws Exception {
    STCP.on("/stops/AAA1/realtime", 503, "{}");
    STCP.on("/stops/BBB2/realtime", 503, "{}");

    Reply r = get("/board.txt?width=30");

    assertThat(r.status()).isEqualTo(200);
    assertThat(r.contentType()).startsWith("text/plain");
    String[] lines = r.body().split("\n");
    assertThat(lines[0]).isEqualTo("TEST" + " ".repeat(26));
    assertThat(lines[2]).startsWith("500  Matosinhos").endsWith("30m").hasSize(30);
  }

  @Test
  void theJsonBoardReportsWhatItPolled() throws Exception {
    STCP.on("/stops/AAA1/realtime", 503, "{}");
    STCP.on("/stops/BBB2/realtime", 503, "{}");

    JsonNode body = get("/board?lat=41.1482&lon=-8.6108&walk_minutes=15").json();

    assertThat(body.path("walk_minutes").isIntegralNumber()).isTrue();
    assertThat(body.path("stops_considered").asInt()).isEqualTo(2);
    assertThat(body.path("stops_truncated").asBoolean()).isFalse();
    assertThat(body.path("stops_polled")).allSatisfy(s -> assertThat(s.path("ok").asBoolean()).isTrue());
    JsonNode row = body.path("departures").get(0);
    assertThat(row.path("realtime").asBoolean()).isFalse();
    assertThat(row.path("leave_in_minutes").isIntegralNumber()).as("an Int, not 30.0").isTrue();
    assertThat(body.path("generated_at").asString()).isEqualTo("2026-08-15T06:30:00.000Z");
  }

  @Test
  void anUnparseableOriginFallsBackToHome() throws Exception {
    STCP.on("/stops/AAA1/realtime", 503, "{}");
    STCP.on("/stops/BBB2/realtime", 503, "{}");
    JsonNode origin = get("/board?lat=abc&lon=").json().path("origin");
    assertThat(origin.path("lat").asDouble()).isEqualTo(41.1482);
    assertThat(origin.path("lon").asDouble()).isEqualTo(-8.6108);
  }

  // ---- stops, errors, health ---------------------------------------------------

  @Test
  void stopReadsBboxValidationAndTrailingSlashes() throws Exception {
    assertThat(get("/stops/").json()).hasSize(2);
    assertThat(get("/stops?bbox=-8.63,41.14,-8.6,41.16&q=car").json()).hasSize(1);
    assertThat(get("/stops/lines?bbox=-8.63,41.14,-8.6,41.16").json().get(0).path("lines").get(0).path("line").asString())
        .isEqualTo("500");

    Reply bad = get("/stops?bbox=1,2,3");
    assertThat(bad.status()).isEqualTo(400);
    assertThat(bad.json().path("detail").asString()).isEqualTo("bbox must be four numbers: minLon,minLat,maxLon,maxLat");

    Reply missing = get("/stops/lines");
    assertThat(missing.status()).isEqualTo(400);
  }

  @Test
  void unknownPathsAndMethodsAnswerWithTheSameErrorShape() throws Exception {
    Reply r = get("/no/such/thing");
    assertThat(r.status()).isEqualTo(404);
    assertThat(r.json().path("detail").asString()).isEqualTo("Not found");

    HttpResponse<String> post =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/stops")).POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(post.statusCode()).isEqualTo(405);
    assertThat(json.readTree(post.body()).has("detail")).isTrue();
  }

  @Test
  void healthReportsTheFeedAndTheBreaker() throws Exception {
    JsonNode body = get("/health").json();
    assertThat(body.path("status").asString()).isEqualTo("ok");
    assertThat(body.path("gtfs").path("source_name").asString()).isEqualTo("api test feed");
    assertThat(body.path("gtfs").path("stops").asInt()).isEqualTo(2);
    assertThat(body.path("gtfs").path("feed_expired").asBoolean()).isFalse();
    assertThat(body.path("upstream").path("open").asBoolean()).isFalse();
  }

  /** A request line sent verbatim, for characters java.net.URI refuses to build. */
  String rawStatusLine(String pathAndQuery) throws Exception {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      OutputStream out = socket.getOutputStream();
      out.write(("GET " + pathAndQuery + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
      out.flush();
      return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
    }
  }
}
