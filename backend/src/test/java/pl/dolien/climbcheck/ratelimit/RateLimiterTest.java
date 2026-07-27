package pl.dolien.climbcheck.ratelimit;

import org.junit.jupiter.api.Test;
import pl.dolien.climbcheck.exception.RateLimitExceededException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-07-05T10:00:00Z"));
    private final RateLimiter limiter = new RateLimiter(Duration.ofSeconds(60), 3, 1, true, clock);

    @Test
    void check_shouldReturnStatusWithLimitAndRemainingBudget() {
        RateLimitStatus first = limiter.check("1.1.1.1", "default");
        assertThat(first.limit()).isEqualTo(3);
        assertThat(first.remaining()).isEqualTo(2);
        assertThat(first.reset()).isAfter(clock.instant());

        RateLimitStatus second = limiter.check("1.1.1.1", "default");
        assertThat(second.remaining()).isEqualTo(1);

        RateLimitStatus third = limiter.check("1.1.1.1", "default");
        assertThat(third.remaining()).isZero();
    }

    @Test
    void check_shouldThrowWhenLimitExceeded() {
        assertThatThrownBy(() -> {
            limiter.check("1.1.1.1", "default");
            limiter.check("1.1.1.1", "default");
            limiter.check("1.1.1.1", "default");
            limiter.check("1.1.1.1", "default");
        }).isInstanceOf(RateLimitExceededException.class)
                .satisfies(ex -> {
                    RateLimitExceededException rateLimitEx = (RateLimitExceededException) ex;
                    // sliding window: retry = when the oldest timestamp falls out of the window
                    assertThat(rateLimitEx.getRetryAfter()).isEqualTo(Duration.ofSeconds(60));
                    // 429 carries the limit state — 0 remaining, reset in the future
                    RateLimitStatus status = rateLimitEx.getStatus();
                    assertThat(status.limit()).isEqualTo(3);
                    assertThat(status.remaining()).isZero();
                    assertThat(status.reset()).isAfter(clock.instant());
                });
    }

    @Test
    void check_shouldResetLimitAfterWindowElapses() {
        limiter.check("1.1.1.1", "default");
        limiter.check("1.1.1.1", "default");
        limiter.check("1.1.1.1", "default");

        // still inside the window → 4th request rejected
        assertThatThrownBy(() -> limiter.check("1.1.1.1", "default"))
                .isInstanceOf(RateLimitExceededException.class);

        // after the window elapses the timestamps expire → the limit resets
        clock.advance(Duration.ofSeconds(61));

        RateLimitStatus afterReset = limiter.check("1.1.1.1", "default");
        assertThat(afterReset.remaining()).isEqualTo(2);
    }

    @Test
    void check_shouldSlideWindowGradually() {
        // 3 requests in the window → limit exhausted
        limiter.check("1.1.1.1", "default");
        limiter.check("1.1.1.1", "default");
        limiter.check("1.1.1.1", "default");
        assertThatThrownBy(() -> limiter.check("1.1.1.1", "default"))
                .isInstanceOf(RateLimitExceededException.class);

        // after 30s the oldest timestamp is still in the window (60s) → still 429
        clock.advance(Duration.ofSeconds(30));
        assertThatThrownBy(() -> limiter.check("1.1.1.1", "default"))
                .isInstanceOf(RateLimitExceededException.class);

        // after another 31s (61s total) the oldest timestamp fell out → 1 slot free
        clock.advance(Duration.ofSeconds(31));
        RateLimitStatus status = limiter.check("1.1.1.1", "default");
        assertThat(status.remaining()).isEqualTo(2);
    }

    @Test
    void check_shouldTreatDifferentIpsIndependently() {
        assertThatCode(() -> {
            limiter.check("1.1.1.1", "default");
            limiter.check("1.1.1.1", "default");
            limiter.check("1.1.1.1", "default");
            limiter.check("2.2.2.2", "default"); // different IP — its own pool
        }).doesNotThrowAnyException();
    }

    @Test
    void check_shouldUseStricterLimitForDashboardCreateGroup() {
        assertThatThrownBy(() -> {
            limiter.check("1.1.1.1", RateLimiter.DASHBOARD_CREATE_GROUP);
            limiter.check("1.1.1.1", RateLimiter.DASHBOARD_CREATE_GROUP);
        }).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void check_shouldNeverThrowWhenDisabled() {
        RateLimiter disabled = new RateLimiter(Duration.ofSeconds(60), 1, 1, false, clock);

        assertThatCode(() -> {
            for (int i = 0; i < 100; i++) {
                disabled.check("1.1.1.1", "default");
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void check_shouldReturnStatusWhenDisabled() {
        RateLimiter disabled = new RateLimiter(Duration.ofSeconds(60), 5, 2, false, clock);

        RateLimitStatus status = disabled.check("1.1.1.1", "default");
        assertThat(status.limit()).isEqualTo(5);
        assertThat(status.remaining()).isEqualTo(5);
    }

    /** Simple injectable clock — allows advancing time in tests. */
    private static final class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            this.instant = instant.plus(duration);
        }

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
            return instant;
        }
    }
}
