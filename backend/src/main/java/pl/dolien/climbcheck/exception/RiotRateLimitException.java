package pl.dolien.climbcheck.exception;

import java.time.Duration;
import java.util.Map;

public class RiotRateLimitException extends RuntimeException {

    private final Duration retryAfter;
    private final Map<String, String> rateLimitHeaders;

    public RiotRateLimitException(String message, Duration retryAfter, Map<String, String> rateLimitHeaders) {
        super(message);
        this.retryAfter = retryAfter;
        this.rateLimitHeaders = rateLimitHeaders;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }

    public Map<String, String> getRateLimitHeaders() {
        return rateLimitHeaders;
    }
}
