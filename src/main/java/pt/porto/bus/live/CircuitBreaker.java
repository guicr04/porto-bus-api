package pt.porto.bus.live;

import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * Without this, every request during an outage pays the full HTTP timeout
 * before falling back, and a degraded API becomes an unusably slow one. After
 * enough consecutive outages upstream isn't called at all for a cooldown; then a
 * single request is let through to test the water.
 */
@Component
public class CircuitBreaker {

  static final int THRESHOLD = 3;
  static final long COOLDOWN_MS = 30_000;

  /** @param open whether calls are currently being skipped */
  public record State(boolean open, int consecutiveFailures, long cooldownMsRemaining) {}

  private final Clock clock;
  private int failures;
  private long openedAt;

  public CircuitBreaker(Clock clock) {
    this.clock = clock;
  }

  /** True when the upstream call should be skipped entirely. */
  public synchronized boolean isOpen() {
    if (failures < THRESHOLD) return false;
    if (clock.millis() - openedAt > COOLDOWN_MS) {
      // Cooldown elapsed: let one request through to probe. One more failure
      // re-opens immediately.
      failures = THRESHOLD - 1;
      return false;
    }
    return true;
  }

  public synchronized void recordFailure() {
    failures++;
    if (failures >= THRESHOLD) openedAt = clock.millis();
  }

  public synchronized void recordSuccess() {
    failures = 0;
  }

  public synchronized State state() {
    boolean open = isOpen();
    long remaining = open ? Math.max(0, COOLDOWN_MS - (clock.millis() - openedAt)) : 0;
    return new State(open, failures, remaining);
  }

  public synchronized void reset() {
    failures = 0;
    openedAt = 0;
  }
}
