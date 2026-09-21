package pt.porto.bus.departures;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import pt.porto.bus.gtfs.GtfsStore;
import pt.porto.bus.gtfs.ScheduleStore;
import pt.porto.bus.live.LiveBoardService;
import pt.porto.bus.model.Arrival;
import pt.porto.bus.model.RealtimeStop;
import pt.porto.bus.model.StopLineDepartures;
import pt.porto.bus.model.StopRoute;
import pt.porto.bus.model.StopRoutes;
import pt.porto.bus.model.StopServices;
import pt.porto.bus.shared.Async;
import pt.porto.bus.shared.LisbonTime;
import pt.porto.bus.shared.UpstreamException;
import pt.porto.bus.stcp.StcpClient;

/** The combined live + scheduled view of one line at one stop (README §4a). */
@Service
public class DeparturesService {

  /**
   * @param serviceId override the auto-detected service
   * @param directionId override the auto-detected direction
   * @param windowMinutes dedup tolerance
   */
  public record Options(String serviceId, Integer directionId, int windowMinutes, int limit) {}

  private final StcpClient stcp;
  private final LiveBoardService live;
  private final GtfsStore store;
  private final ScheduleStore schedule;
  private final ExecutorService executor;
  private final Clock clock;

  public DeparturesService(
      StcpClient stcp,
      LiveBoardService live,
      GtfsStore store,
      ScheduleStore schedule,
      ExecutorService fanOutExecutor,
      Clock clock) {
    this.stcp = stcp;
    this.live = live;
    this.store = store;
    this.schedule = schedule;
    this.executor = fanOutExecutor;
    this.clock = clock;
  }

  public StopLineDepartures stopLineDepartures(String stopCode, String line, Options opts) {
    Instant now = clock.instant();

    // The board, the stop's routes (direction + colour) and the day's services
    // (active service_id), in parallel.
    //
    // Only the board is load-bearing: it comes through the fallback seam, so it
    // is live or timetable but never absent. The other two are decoration, and
    // an outage on either degrades a detail rather than the answer.
    CompletableFuture<RealtimeStop> boardF = CompletableFuture.supplyAsync(() -> live.stopBoard(stopCode), executor);
    CompletableFuture<StopRoutes> routesF =
        CompletableFuture.supplyAsync(nullOnOutage(() -> stcp.stopRoutes(stopCode)), executor);
    CompletableFuture<StopServices> servicesF =
        opts.serviceId() != null
            ? CompletableFuture.completedFuture(null)
            : CompletableFuture.supplyAsync(nullOnOutage(() -> stcp.stopServices(stopCode, null)), executor);

    RealtimeStop realtime = Async.join(boardF);
    StopRoutes routes = Async.join(routesF);
    StopServices services = Async.join(servicesF);

    boolean degraded = "scheduled".equals(realtime.dataSource());

    // Which direction does this line run at this stop? The per-direction list
    // first; the caller's override wins over both.
    StopRoute routeMeta = null;
    if (routes != null) {
      routeMeta =
          routes.directions().stream()
              .filter(r -> line.equals(r.routeId()))
              .findFirst()
              .or(() -> routes.routes().stream().filter(r -> line.equals(r.routeId())).findFirst())
              .orElse(null);
    }
    int directionId =
        opts.directionId() != null
            ? opts.directionId()
            : routeMeta != null && routeMeta.directionId() != null ? routeMeta.directionId() : 0;

    // Colour: the live board carries the line's real colour (300 is #417DBD),
    // while routes only has a coarse family colour (#187EC2 for every city line).
    // Taking the routes one would render one line in two blues depending on the
    // source, so prefer live and fall back only when the board is empty.
    List<Arrival> liveForLine = realtime.arrivals().stream().filter(a -> line.equals(a.line())).toList();
    Arrival liveSample =
        liveForLine.stream().filter(a -> a.color() != null && !a.color().isEmpty()).findFirst().orElse(null);
    String color = liveSample != null ? liveSample.color() : routeMeta != null ? routeMeta.color() : null;
    String textColor = liveSample != null ? liveSample.textColor() : routeMeta != null ? routeMeta.textColor() : null;

    // The store knows today's service too, and unlike upstream it still knows it
    // during an outage.
    String serviceId = opts.serviceId();
    if (serviceId == null && services != null) serviceId = services.activeServiceId();
    if (serviceId == null) {
      List<String> active = store.activeServiceIds(LisbonTime.dateStamp(now));
      serviceId = active.isEmpty() ? null : active.getFirst();
    }

    // The scheduled half. Upstream while healthy — it can reflect short-term
    // changes the fortnightly zip doesn't — and the store once it isn't.
    List<DepartureMerger.Scheduled> scheduled = List.of();
    if (degraded) {
      scheduled = fromStore(stopCode, line, opts.limit(), now);
    } else if (serviceId != null) {
      try {
        scheduled =
            stcp.stopSchedule(stopCode, line, serviceId, directionId).departures().stream()
                .map(d -> new DepartureMerger.Scheduled(d.departureTime(), d.headsign(), null))
                .toList();
      } catch (RuntimeException e) {
        if (!UpstreamException.isOutage(e)) throw e;
        scheduled = fromStore(stopCode, line, opts.limit(), now);
      }
    }

    var departures =
        DepartureMerger.merge(
            new DepartureMerger.Input(
                liveForLine,
                scheduled,
                LisbonTime.nowMinutes(now),
                opts.windowMinutes(),
                opts.limit(),
                line,
                color,
                textColor));

    return new StopLineDepartures(
        stopCode,
        line,
        directionId,
        serviceId,
        LisbonTime.isoTimestamp(now),
        degraded ? "scheduled" : "realtime",
        departures);
  }

  private List<DepartureMerger.Scheduled> fromStore(String stopCode, String line, int limit, Instant now) {
    return schedule.departures(stopCode, 120, limit * 2, line, now).stream()
        .map(d -> new DepartureMerger.Scheduled(d.clock() + ":00", d.destination(), d.tripId()))
        .toList();
  }

  /** Swallow an upstream outage (there is a store-backed path); rethrow anything that means the request was wrong. */
  private static <T> Supplier<T> nullOnOutage(Supplier<T> call) {
    return () -> {
      try {
        return call.get();
      } catch (RuntimeException e) {
        if (!UpstreamException.isOutage(e)) throw e;
        return null;
      }
    };
  }
}
