package pl.dolien.climbcheck.ratelimit;

import java.time.Instant;

/**
 * Limit state returned to the client in X-RateLimit-* headers (Riot API convention):
 * how many requests are allowed in the window, how many remain and when the counter
 * resets (epoch seconds).
 */
public record RateLimitStatus(int limit, int remaining, Instant reset) {

    public long resetEpochSecond() {
        return reset.getEpochSecond();
    }
}
