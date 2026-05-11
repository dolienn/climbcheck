package pl.dolien.climbcheck.exception;

import pl.dolien.climbcheck.ratelimit.RateLimitStatus;

import java.time.Duration;

/** Own-API request limit exceeded (429) — how long to wait before retrying. */
public class RateLimitExceededException extends RuntimeException {

    private final Duration retryAfter;
    private final RateLimitStatus status;

    public RateLimitExceededException(String message, Duration retryAfter, RateLimitStatus status) {
        super(message);
        this.retryAfter = retryAfter;
        this.status = status;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }

    public RateLimitStatus getStatus() {
        return status;
    }
}
