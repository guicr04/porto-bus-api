package pt.porto.bus.gtfs;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pt.porto.bus.shared.AppProperties;

/**
 * When the store gets (re)built.
 *
 * <ul>
 *   <li>At boot, before the web server accepts traffic: an empty store blocks
 *       startup, since there is nothing to serve without it. A merely stale one
 *       refreshes in the background — yesterday's timetable beats a server that
 *       won't come up. A fresh clone therefore works with no scheduler at all.
 *   <li>Hourly while running, when the store has passed its TTL.
 *   <li>On demand with `--ingest`, for cron or `make gtfs`.
 * </ul>
 */
@Component
public class GtfsRefresh implements SmartInitializingSingleton {
  private static final Logger log = LoggerFactory.getLogger(GtfsRefresh.class);

  private final GtfsIngest ingest;
  private final GtfsStore store;
  private final AppProperties props;

  public GtfsRefresh(GtfsIngest ingest, GtfsStore store, AppProperties props) {
    this.ingest = ingest;
    this.store = store;
    this.props = props;
  }

  /** Runs after every bean exists and before the web server starts. */
  @Override
  public void afterSingletonsInstantiated() {
    if (props.bootRefresh()) prepareStore();
  }

  void prepareStore() {
    if (!store.hasData()) {
      log.info("[gtfs] store is empty — ingesting before accepting traffic");
      ingest.ingest(m -> log.info("[gtfs] {}", m));
      log.info("[gtfs] ready");
      return;
    }
    if (store.isStale()) {
      log.info("[gtfs] store is stale — refreshing in the background");
      Thread.ofVirtual().name("gtfs-boot-refresh").start(this::refreshQuietly);
    }
    if (store.isFeedExpired()) {
      log.warn(
          "[gtfs] the ingested feed is past its validity window. Scheduled fallbacks will be refused until the"
              + " portal publishes a current feed.");
    }
  }

  @Scheduled(initialDelay = 1, fixedDelay = 1, timeUnit = TimeUnit.HOURS)
  void refreshIfStale() {
    if (props.scheduledRefresh() && store.isStale()) refreshQuietly();
  }

  private void refreshQuietly() {
    try {
      if (ingest.ingestIfIdle(m -> log.info("[gtfs] {}", m)) != null) log.info("[gtfs] refresh complete");
    } catch (RuntimeException e) {
      log.error("[gtfs] background refresh failed, the previous store is untouched: {}", e.getMessage());
    }
  }

  /** `--ingest`: rebuild, print what was loaded, and report success as an exit code. */
  public int runStandalone() {
    long started = System.nanoTime();
    try {
      Map<String, Object> counts = ingest.ingest(m -> System.out.println("[gtfs] " + m));
      System.out.printf("[gtfs] ingested in %.1fs:%n", (System.nanoTime() - started) / 1e9);
      counts.forEach((k, v) -> System.out.printf("         %s: %s%n", k, v));
      if (store.isFeedExpired()) {
        System.err.printf(
            "[gtfs] WARNING: this feed expired on %s. The portal has not published a current feed;"
                + " scheduled data will be refused.%n",
            counts.get("feed_end_date"));
      }
      return 0;
    } catch (RuntimeException e) {
      System.err.println("[gtfs] refresh failed: " + e.getMessage());
      System.err.println("[gtfs] the previous store is untouched.");
      return 1;
    }
  }
}
