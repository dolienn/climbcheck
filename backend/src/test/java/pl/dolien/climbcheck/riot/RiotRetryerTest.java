package pl.dolien.climbcheck.riot;

import org.junit.jupiter.api.Test;
import pl.dolien.climbcheck.exception.RiotRateLimitException;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiotRetryerTest {

    private final RiotRetryer riotRetryer = new RiotRetryer(new RetryPolicy(3, 1, 10));

    @Test
    void execute_shouldRetryOnRateLimitAndReturnResult() {
        AtomicInteger calls = new AtomicInteger();
        String result = riotRetryer.execute(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new RiotRateLimitException("rate limited", Duration.ofMillis(1), Map.of());
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(2);
    }

    @Test
    void execute_shouldGiveUpAfterMaxAttempts() {
        assertThatThrownBy(() -> riotRetryer.execute(() -> {
            throw new RiotRateLimitException("rate limited", Duration.ofMillis(1), Map.of());
        }))
                .isInstanceOf(RiotRateLimitException.class)
                .hasMessageContaining("rate limited");
    }

    @Test
    void execute_shouldPropagateNonRateLimitExceptionsImmediately() {
        assertThatThrownBy(() -> riotRetryer.execute(() -> {
            throw new IllegalStateException("Riot API down");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Riot API down");
    }
}
