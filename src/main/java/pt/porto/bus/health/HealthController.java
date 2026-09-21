package pt.porto.bus.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import pt.porto.bus.gtfs.FeedMeta;
import pt.porto.bus.gtfs.GtfsStore;
import pt.porto.bus.live.CircuitBreaker;

@RestController
public class HealthController {

  public record Health(String status, Gtfs gtfs, CircuitBreaker.State upstream) {}

  /**
   * @param feedExpired the field worth alerting on: the store can be freshly
   *     ingested and still hold a feed whose validity window has passed, because
   *     the portal stopped republishing
   */
  public record Gtfs(
      String sourceName,
      String sourceUrl,
      String feedVersion,
      String feedStartDate,
      String feedEndDate,
      String loadedAt,
      long stops,
      long lines,
      long trips,
      long stopTimes,
      boolean feedExpired) {}

  private final GtfsStore store;
  private final CircuitBreaker breaker;

  public HealthController(GtfsStore store, CircuitBreaker breaker) {
    this.store = store;
    this.breaker = breaker;
  }

  /** Liveness, which static feed is being served, and the upstream breaker. */
  @GetMapping("/health")
  public Health health() {
    FeedMeta m = store.feedMeta();
    var gtfs =
        new Gtfs(
            m == null ? null : m.resourceName(),
            m == null ? null : m.sourceUrl(),
            m == null ? null : m.feedVersion(),
            m == null ? null : m.feedStartDate(),
            m == null ? null : m.feedEndDate(),
            m == null ? null : m.ingestedAt(),
            store.count("stops"),
            store.count("routes"),
            store.count("trips"),
            store.count("stop_times"),
            store.isFeedExpired());
    return new Health("ok", gtfs, breaker.state());
  }
}
