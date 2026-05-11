package pl.dolien.climbcheck.riot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RetryPolicy {

    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;

    public RetryPolicy(@Value("${app.lp-snapshot.retry.max-attempts:3}") int maxAttempts,
                       @Value("${app.lp-snapshot.retry.base-delay-ms:1000}") long baseDelayMs,
                       @Value("${app.lp-snapshot.retry.max-delay-ms:30000}") long maxDelayMs) {
        this.maxAttempts = maxAttempts;
        this.baseDelay = Duration.ofMillis(baseDelayMs);
        this.maxDelay = Duration.ofMillis(maxDelayMs);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * Delay before the given 1-based retry attempt: exponential backoff
     * (baseDelay * 2^(attempt-1)) capped at maxDelay, and never shorter than
     * the server-provided Retry-After (which is also capped at maxDelay so a
     * long server hint cannot stall the whole snapshot batch).
     */
    public Duration delayForAttempt(int attempt, Duration retryAfter) {
        Duration backoff = baseDelay.multipliedBy(1L << (attempt - 1));
        if (backoff.compareTo(maxDelay) > 0) {
            backoff = maxDelay;
        }
        if (retryAfter == null) {
            return backoff;
        }
        Duration cappedRetryAfter = retryAfter.compareTo(maxDelay) > 0 ? maxDelay : retryAfter;
        return cappedRetryAfter.compareTo(backoff) > 0 ? cappedRetryAfter : backoff;
    }
}
