package pt.porto.bus.lines;

import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.Clock;
import pt.porto.bus.gtfs.GtfsStore;
import pt.porto.bus.gtfs.LineStore;
import pt.porto.bus.model.Line;
import pt.porto.bus.model.RouteDirectionStops;
import pt.porto.bus.model.RouteSchedule;
import pt.porto.bus.model.RouteServices;
import pt.porto.bus.model.RouteShape;
import pt.porto.bus.shared.ApiException;
import pt.porto.bus.shared.LisbonTime;
import pt.porto.bus.shared.Numbers;
import pt.porto.bus.shared.UpstreamException;
import pt.porto.bus.stcp.StcpClient;

@RestController
@RequestMapping("/lines")
public class LinesController {
  private static final Logger log = LoggerFactory.getLogger(LinesController.class);

  private final GtfsStore store;
  private final LineStore lineStore;
  private final StcpClient stcp;
  private final Clock clock;

  public LinesController(GtfsStore store, LineStore lineStore, StcpClient stcp, Clock clock) {
    this.store = store;
    this.lineStore = lineStore;
    this.stcp = stcp;
    this.clock = clock;
  }

  /** All lines, with the GTFS family colour — there is no live context for a global list. */
  @GetMapping
  public List<Line> lines() {
    return store.lines();
  }

  @GetMapping("/{line}/stops")
  public RouteDirectionStops stops(@PathVariable String line, @RequestParam(name = "direction_id", required = false) String dir) {
    int d = direction(dir);
    Sourced<RouteDirectionStops> r =
        liveOrStore(
            () -> stcp.routeStops(line, d),
            () -> lineStore.lineStops(line, d),
            "No stops for line '" + line + "' direction " + d);
    return r.value().withDataSource(r.source());
  }

  @GetMapping("/{line}/shape")
  public RouteShape shape(@PathVariable String line, @RequestParam(name = "direction_id", required = false) String dir) {
    int d = direction(dir);
    Sourced<RouteShape> r =
        liveOrStore(
            () -> stcp.routeShape(line, d),
            () -> lineStore.lineShape(line, d),
            "No shape for line '" + line + "' direction " + d);
    return r.value().withDataSource(r.source());
  }

  /** @param date YYYY-MM-DD, default today */
  @GetMapping("/{line}/services")
  public RouteServices services(@PathVariable String line, @RequestParam(required = false) String date) {
    Sourced<RouteServices> r =
        liveOrStore(
            () -> stcp.routeServices(line, date),
            () -> {
              String routeId = lineStore.resolveRouteId(line);
              if (routeId == null) return null;
              String today = LisbonTime.dateStamp(clock.instant());
              String stamp = date != null ? date.replace("-", "") : today;
              List<String> services = store.activeServiceIds(stamp);
              return new RouteServices(routeId, services, services.isEmpty() ? null : services.getFirst(), stamp, today, null);
            },
            "Line '" + line + "' not found");
    return r.value().withDataSource(r.source());
  }

  /** The full timetable grid. Live only. */
  @GetMapping("/{line}/schedule")
  public RouteSchedule schedule(
      @PathVariable String line,
      @RequestParam(name = "service_id", required = false) String serviceId,
      @RequestParam(name = "direction_id", required = false) String dir) {
    if (serviceId == null) throw ApiException.badRequest("service_id is required");
    return stcp.routeSchedule(line, serviceId, direction(dir));
  }

  // ---- live first, store behind it -----------------------------------------

  record Sourced<T>(T value, String source) {}

  /**
   * The store returning null means "it has nothing for this line" — a 404
   * rather than a 502, since the upstream failure is no longer the interesting
   * fact. An expired feed disqualifies the store entirely: a timetable outside
   * its validity window is a guess, not data.
   */
  private <T> Sourced<T> liveOrStore(Supplier<T> live, Supplier<T> fromStore, String notFound) {
    try {
      return new Sourced<>(live.get(), "realtime");
    } catch (RuntimeException e) {
      if (!UpstreamException.isOutage(e) || !store.canStandIn()) throw e;
      T value = fromStore.get();
      if (value == null) throw ApiException.notFound(notFound);
      log.error("[lines] serving from the static store: {}", e.getMessage());
      return new Sourced<>(value, "scheduled");
    }
  }

  /** A 0/1 direction query param, defaulting to 0. */
  private static int direction(String raw) {
    return (int) Math.min(Math.max(Numbers.parse(raw, 0.0), 0), 1);
  }
}
