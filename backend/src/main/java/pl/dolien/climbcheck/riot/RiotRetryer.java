package pl.dolien.climbcheck.riot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pl.dolien.climbcheck.exception.RiotRateLimitException;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Runs Riot API calls with retry on rate limits: exponential backoff computed by
 * {@link RetryPolicy}, with the server-provided Retry-After as the floor. The retry
 * loop used to live in both PlayerService and LpSnapshotScheduler (copy-pasted) —
 * this is its single home. An interruption while sleeping propagates as
 * IllegalStateException.
 */
@Slf4j
@Component
public class RiotRetryer {

    private final RetryPolicy retryPolicy;

    public RiotRetryer(RetryPolicy retryPolicy) {
        this.retryPolicy = retryPolicy;
    }

    /**
     * Executes the call, retrying on {@link RiotRateLimitException} up to
     * {@code maxAttempts} with exponential backoff. The last rate-limit exception
     * is re-thrown when attempts are exhausted; any other exception propagates
     * immediately (only rate limits are worth waiting out).
     */
    public <T> T execute(Supplier<T> call) {
        int maxAttempts = retryPolicy.maxAttempts();
        for (int attempt = 1; ; attempt++) {
            try {
                return call.get();
            } catch (RiotRateLimitException ex) {
                if (attempt >= maxAttempts) {
                    throw ex;
                }
                Duration delay = retryPolicy.delayForAttempt(attempt, ex.getRetryAfter());
                log.warn("Riot API rate limited, retrying in {}ms (attempt {}/{})",
                        delay.toMillis(), attempt, maxAttempts);
                sleep(delay);
            }
        }
    }

    private void sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for rate limit retry", ex);
        }
    }
}
