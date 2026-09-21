package pt.porto.bus.board;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import pt.porto.bus.gtfs.GtfsStore;
import pt.porto.bus.live.LiveBoardService;
import pt.porto.bus.model.LocationBoard;
import pt.porto.bus.model.PolledStop;
import pt.porto.bus.model.RealtimeStop;
import pt.porto.bus.shared.Async;
import pt.porto.bus.shared.Geo;
import pt.porto.bus.shared.Geo.NearbyStop;
import pt.porto.bus.shared.LisbonTime;
import pt.porto.bus.shared.Numbers;

/**
 * Every bus you can still catch from any stop within a walking budget. This is
 * what the desk display polls; rendering lives in {@link BoardRenderer}.
 */
@Service
public class BoardService {
  private static final Logger log = LoggerFactory.getLogger(BoardService.class);

  /**
   * How close two stops must be to count as platforms of the same place.
   * Opposite kerbs of a wide avenue sit around 30 m apart.
   */
  static final double CLUSTER_METERS = 60;

  public record Request(
      double lat, double lon, double walkMinutes, int maxStops, double metersPerMinute, BoardAssembler.Options options) {}

  /** A stop that can be clustered: a name, and coordinates when known. */
  public record Candidate(String stopCode, String name, Double lat, Double lon) {}

  private record Polled(NearbyStop stop, RealtimeStop board, boolean failed) {}

  private final GtfsStore store;
  private final LiveBoardService live;
  private final ExecutorService executor;
  private final Clock clock;

  public BoardService(GtfsStore store, LiveBoardService live, ExecutorService fanOutExecutor, Clock clock) {
    this.store = store;
    this.live = live;
    this.executor = fanOutExecutor;
    this.clock = clock;
  }

  /**
   * Which in-range stops to actually poll, as indexes into `nearby`.
   *
   * <p>Nearest-first, but skipping stops that are another platform of one already
   * picked, so a cluster doesn't eat the budget and leave the rest of the radius
   * unseen (CORDOARIA alone appears three times within 20 m). Proximity, not
   * just name, decides: the feed spells one place several ways ("GUIL. G.
   * FERNANDES" vs "GUILHERME GOMES FERNANDES"). Leftovers fill any remaining
   * budget.
   *
   * @param nearby sorted by distance from the origin
   */
  public static List<Candidate> pickStopsToPoll(List<Candidate> nearby, int maxStops) {
    Set<String> names = new HashSet<>();
    List<Candidate> picked = new ArrayList<>();
    List<Candidate> rest = new ArrayList<>();
    for (Candidate s : nearby) {
      String name = s.name().trim().toUpperCase(Locale.ROOT);
      boolean duplicate =
          names.contains(name)
              || picked.stream()
                  .anyMatch(
                      p ->
                          p.lat() != null
                              && s.lat() != null
                              && Geo.haversineMeters(p.lat(), p.lon(), s.lat(), s.lon()) < CLUSTER_METERS);
      if (duplicate) {
        rest.add(s);
      } else {
        names.add(name);
        picked.add(s);
      }
    }
    picked.addAll(rest);
    return picked.subList(0, Math.min(Math.max(maxStops, 0), picked.size()));
  }

  public LocationBoard locationBoard(Request req) {
    List<NearbyStop> nearby =
        Geo.stopsWithinWalk(store.stops(), req.lat(), req.lon(), req.walkMinutes(), req.metersPerMinute());

    // Each stop costs one upstream call, so cap how many are polled.
    List<Candidate> picked =
        pickStopsToPoll(
            nearby.stream().map(s -> new Candidate(s.stopCode(), s.name(), s.lat(), s.lon())).toList(),
            req.maxStops());
    List<NearbyStop> polled =
        picked.stream()
            .map(c -> nearby.stream().filter(n -> n.stopCode().equals(c.stopCode())).findFirst().orElseThrow())
            .toList();

    List<CompletableFuture<Polled>> calls =
        polled.stream()
            .map(
                stop ->
                    CompletableFuture.supplyAsync(
                        () -> {
                          try {
                            return new Polled(stop, live.stopBoard(stop.stopCode()), false);
                          } catch (RuntimeException e) {
                            // One flaky stop shouldn't blank the whole board.
                            log.error("realtime failed for {}: {}", stop.stopCode(), e.getMessage());
                            return new Polled(stop, null, true);
                          }
                        },
                        executor))
            .toList();
    List<Polled> boards = calls.stream().map(Async::join).toList();

    var departures =
        BoardAssembler.build(
            boards.stream()
                .map(
                    b ->
                        new BoardAssembler.StopBoard(
                            b.stop().stopCode(),
                            b.stop().name(),
                            b.stop().walkMinutes(),
                            b.stop().distanceMeters(),
                            b.board() == null || b.board().arrivals() == null ? List.of() : b.board().arrivals(),
                            b.board() == null ? null : b.board().dataSource()))
                .toList(),
            req.options());

    return new LocationBoard(
        new LocationBoard.Origin(req.lat(), req.lon()),
        Numbers.compact(req.walkMinutes()),
        LisbonTime.isoTimestamp(clock.instant()),
        nearby.size(),
        // So a thin board reads as "we didn't look everywhere", not "nothing runs".
        nearby.size() > polled.size(),
        boards.stream()
            .map(
                b ->
                    new PolledStop(
                        b.stop().stopCode(),
                        b.stop().name(),
                        b.stop().distanceMeters(),
                        b.stop().walkMinutes(),
                        !b.failed()))
            .toList(),
        departures);
  }
}
