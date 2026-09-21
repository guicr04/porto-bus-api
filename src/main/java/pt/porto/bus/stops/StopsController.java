package pt.porto.bus.stops;

import java.util.List;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pt.porto.bus.departures.DeparturesService;
import pt.porto.bus.gtfs.GtfsStore;
import pt.porto.bus.live.LiveBoardService;
import pt.porto.bus.model.RealtimeStop;
import pt.porto.bus.model.Stop;
import pt.porto.bus.model.StopLineDepartures;
import pt.porto.bus.model.StopLines;
import pt.porto.bus.model.StopRoutes;
import pt.porto.bus.model.StopSchedule;
import pt.porto.bus.model.StopServices;
import pt.porto.bus.shared.ApiException;
import pt.porto.bus.shared.BBox;
import pt.porto.bus.shared.Numbers;
import pt.porto.bus.stcp.StcpClient;

@RestController
@RequestMapping("/stops")
public class StopsController {

  private final GtfsStore store;
  private final StcpClient stcp;
  private final LiveBoardService live;
  private final DeparturesService departures;

  public StopsController(GtfsStore store, StcpClient stcp, LiveBoardService live, DeparturesService departures) {
    this.store = store;
    this.stcp = stcp;
    this.live = live;
    this.departures = departures;
  }

  /**
   * GET /stops?q=&bbox=&limit=
   *
   * <p>The limit tops out above the network's real size. It used to clamp at
   * 2000, which silently truncated Porto's 2,568 stops — a cap that looks like a
   * full answer is worse than an error.
   */
  @GetMapping
  public List<Stop> stops(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String bbox,
      @RequestParam(required = false) String limit) {
    String query = q == null ? null : q.trim();
    BBox box = BBox.parse(bbox);

    // A bbox is already a bound, and a map that draws 100 of the 178 stops in
    // view is wrong in a way the client cannot detect. So bbox requests default
    // to "everything in the box"; an explicit ?limit= still wins.
    int fallback = box != null ? 5000 : 100;
    int n = Math.min(Math.max(Numbers.parseInt(limit, fallback), 1), 5000);

    if (box != null) {
      List<Stop> inBox = store.stopsInBBox(box, n);
      if (query == null || query.isEmpty()) return inBox;
      String needle = query.toLowerCase(Locale.ROOT);
      return inBox.stream().filter(s -> s.name().toLowerCase(Locale.ROOT).contains(needle)).toList();
    }
    if (query != null && !query.isEmpty()) return store.searchStops(query, n);
    List<Stop> all = store.stops();
    return all.subList(0, Math.min(n, all.size()));
  }

  /** GET /stops/lines?bbox= — which lines serve each stop in a region, for map labels. */
  @GetMapping("/lines")
  public List<StopLines> stopLines(@RequestParam(required = false) String bbox) {
    BBox box = BBox.parse(bbox);
    if (box == null) throw ApiException.badRequest("bbox is required: minLon,minLat,maxLon,maxLat");
    return store.stopLinesInBBox(box);
  }

  @GetMapping("/{code}")
  public Stop stop(@PathVariable String code) {
    return store.stop(code).orElseThrow(() -> ApiException.notFound("Stop '" + code + "' not found"));
  }

  /**
   * Live when STCP answers; today's timetable, tagged data_source="scheduled",
   * when it doesn't. A client must render the difference.
   */
  @GetMapping("/{code}/realtime")
  public RealtimeStop realtime(@PathVariable String code) {
    return live.stopBoard(code);
  }

  @GetMapping("/{code}/routes")
  public StopRoutes routes(@PathVariable String code) {
    return stcp.stopRoutes(code);
  }

  /** @param date YYYY-MM-DD, default today */
  @GetMapping("/{code}/services")
  public StopServices services(@PathVariable String code, @RequestParam(required = false) String date) {
    return stcp.stopServices(code, date);
  }

  /**
   * GET /stops/{code}/departures?line=&service_id=&direction_id=&window_minutes=&limit=
   *
   * <p>The combined live + scheduled view for one line at one stop (README §4a).
   */
  @GetMapping("/{code}/departures")
  public StopLineDepartures departures(
      @PathVariable String code,
      @RequestParam(required = false) String line,
      @RequestParam(name = "service_id", required = false) String serviceId,
      @RequestParam(name = "direction_id", required = false) String directionId,
      @RequestParam(name = "window_minutes", required = false) String windowMinutes,
      @RequestParam(required = false) String limit) {
    if (line == null || line.isEmpty()) throw ApiException.badRequest("line query param is required");
    Double dir = Numbers.parse(directionId, (Double) null);
    return departures.stopLineDepartures(
        code,
        line,
        new DeparturesService.Options(
            serviceId,
            dir == null ? null : dir.intValue(),
            Numbers.parseInt(windowMinutes, 3),
            Numbers.parseInt(limit, 10)));
  }

  @GetMapping("/{code}/schedule")
  public StopSchedule schedule(
      @PathVariable String code,
      @RequestParam(name = "route_id", required = false) String routeId,
      @RequestParam(name = "service_id", required = false) String serviceId,
      @RequestParam(name = "direction_id", required = false) String directionId) {
    if (routeId == null || serviceId == null) throw ApiException.badRequest("route_id and service_id are required");
    return stcp.stopSchedule(code, routeId, serviceId, Numbers.parseInt(directionId, 0));
  }
}
