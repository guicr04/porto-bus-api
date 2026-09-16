package pt.porto.bus.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

  /** A clock the test can move. */
  static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-08-15T12:00:00Z");

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

  @Test
  void opensAfterThreeConsecutiveOutagesAndProbesAfterTheCooldown() {
    var clock = new MutableClock();
    var breaker = new CircuitBreaker(clock);

    breaker.recordFailure();
    breaker.recordFailure();
    assertThat(breaker.isOpen()).isFalse();
    breaker.recordFailure();
    assertThat(breaker.isOpen()).isTrue();
    assertThat(breaker.state().cooldownMsRemaining()).isEqualTo(30_000);

    clock.now = clock.now.plus(Duration.ofSeconds(31));
    assertThat(breaker.isOpen()).as("one probe is let through").isFalse();
    breaker.recordFailure();
    assertThat(breaker.isOpen()).as("a failed probe re-opens at once").isTrue();

    clock.now = clock.now.plus(Duration.ofSeconds(31));
    assertThat(breaker.isOpen()).isFalse();
    breaker.recordSuccess();
    assertThat(breaker.state().consecutiveFailures()).isZero();
  }
}
