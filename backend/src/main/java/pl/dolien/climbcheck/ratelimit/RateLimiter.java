package pl.dolien.climbcheck.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import pl.dolien.climbcheck.exception.RateLimitExceededException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Sliding window rate limiter per key (IP + endpoint group) — protects the own API
 * from dashboard-token scraping. For each key it keeps request timestamps in a window
 * (e.g. 60s) and drops those older than the window on every check — no bursts at the
 * window boundary unlike a fixed window. The clock is injected, so time can be
 * controlled in tests.
 */
public class RateLimiter {

    public static final String DASHBOARD_CREATE_GROUP = "dashboard-create";
    private static final String DEFAULT_GROUP = "default";

    private final Cache<String, Deque<Long>> counters;
    private final Duration window;
    private final int maxRequestsPerIp;
    private final int dashboardCreateMax;
    private final boolean enabled;
    private final Clock clock;

    public RateLimiter(Duration window, int maxRequestsPerIp, int dashboardCreateMax, boolean enabled, Clock clock) {
        this.window = window;
        this.maxRequestsPerIp = maxRequestsPerIp;
        this.dashboardCreateMax = dashboardCreateMax;
        this.enabled = enabled;
        this.clock = clock;
        this.counters = Caffeine.newBuilder()
                .maximumSize(100_000)
                .build();
    }

    /**
     * Records a request for the given client and group; throws {@link RateLimitExceededException}
     * when the group limit is exceeded in the current window. Returns the limit state for
     * response headers (limit, remaining, reset).
     */
    public RateLimitStatus check(String ip, String group) {
        if (!enabled) {
            return new RateLimitStatus(maxRequestsPerIp, maxRequestsPerIp, clock.instant().plus(window));
        }
        int max = DASHBOARD_CREATE_GROUP.equals(group) ? dashboardCreateMax : maxRequestsPerIp;
        String key = ip + ":" + group;
        long nowMillis = clock.millis();

        Deque<Long> timestamps = counters.get(key, k -> new ArrayDeque<>());
        RateLimitStatus status;
        synchronized (timestamps) {
            long cutoff = nowMillis - window.toMillis();
            while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) {
                timestamps.removeFirst();
            }

            int count = timestamps.size();
            // reset = when the oldest timestamp falls out of the window — a slot frees up
            Instant reset = timestamps.isEmpty()
                    ? clock.instant().plus(window)
                    : Instant.ofEpochMilli(timestamps.peekFirst() + window.toMillis());

            if (count >= max) {
                status = new RateLimitStatus(max, 0, reset);
                throw new RateLimitExceededException("Too many requests", Duration.between(clock.instant(), reset), status);
            }

            timestamps.addLast(nowMillis);
            status = new RateLimitStatus(max, max - count - 1, reset);
        }
        return status;
    }
}
