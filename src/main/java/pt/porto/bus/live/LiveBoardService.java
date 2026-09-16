package pt.porto.bus.live;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import pt.porto.bus.gtfs.GtfsStore;
import pt.porto.bus.gtfs.ScheduleStore;
import pt.porto.bus.model.RealtimeStop;
import pt.porto.bus.shared.ApiException;
import pt.porto.bus.shared.UpstreamException;
import pt.porto.bus.stcp.StcpClient;

/**
 * Live first, the store as the fallback (README §2a).
 *
 * <p>Before the static store existed, every time-related answer was an upstream
 * call, and when stcp.pt went away every screen went blank at once. This is the
 * seam: callers ask for a stop's board here and get either the live answer or
 * today's timetable, always tagged so the client can say which it is showing.
 */
@Service
public class LiveBoardService {
  private static final Logger log = LoggerFactory.getLogger(LiveBoardService.class);

  private final StcpClient stcp;
  private final GtfsStore store;
  private final ScheduleStore schedule;
  private final CircuitBreaker breaker;
  private final Clock clock;

  public LiveBoardService(StcpClient stcp, GtfsStore store, ScheduleStore schedule, CircuitBreaker breaker, Clock clock) {
    this.stcp = stcp;
    this.store = store;
    this.schedule = schedule;
    this.breaker = breaker;
    this.clock = clock;
  }

  /** A stop's board: live when STCP answers, today's timetable when it doesn't. */
  public RealtimeStop stopBoard(String stopCode) {
    if (!breaker.isOpen()) {
      try {
        RealtimeStop live = stcp.stopRealtime(stopCode);
        breaker.recordSuccess();
        return live;
      } catch (RuntimeException e) {
        if (!UpstreamException.isOutage(e)) throw e;
        breaker.recordFailure();
        log.error("[live] stcp unavailable for {}: {}", stopCode, e.getMessage());
      }
    }

    // An expired feed is refused rather than served: projecting a timetable
    // outside its own validity window onto today would be indistinguishable, to
    // the client, from a real one.
    if (!store.hasData()) {
      throw new ApiException(503, "stcp.pt is unavailable and the static store is empty");
    }
    if (store.isFeedExpired()) {
      throw new ApiException(503, "stcp.pt is unavailable and the ingested GTFS feed is past its validity window");
    }
    // Don't invent a board for a stop we've never heard of.
    if (!store.stopExists(stopCode)) {
      throw ApiException.notFound("Stop '" + stopCode + "' not found");
    }
    return schedule.stopBoard(stopCode, clock.instant());
  }
}
