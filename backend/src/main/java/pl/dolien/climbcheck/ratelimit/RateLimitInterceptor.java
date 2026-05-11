package pl.dolien.climbcheck.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import pl.dolien.climbcheck.exception.RateLimitExceededException;

/** Passes requests through {@link RateLimiter} — a separate, stricter limit for dashboard creation. */
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final String DEFAULT_GROUP = "default";

    private final RateLimiter rateLimiter;

    public RateLimitInterceptor(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String group = isDashboardCreate(request)
                ? RateLimiter.DASHBOARD_CREATE_GROUP
                : DEFAULT_GROUP;
        String ip = clientIp(request);
        try {
            applyRateLimitHeaders(response, rateLimiter.check(ip, group));
        } catch (RateLimitExceededException ex) {
            // 429 also carries headers — the client sees how many remain and when reset happens
            applyRateLimitHeaders(response, ex.getStatus());
            throw ex;
        }
        return true;
    }

    /** Riot convention: limit, remaining in window, reset (epoch seconds). */
    private void applyRateLimitHeaders(HttpServletResponse response, RateLimitStatus status) {
        response.setHeader("X-RateLimit-Limit", String.valueOf(status.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(status.remaining()));
        response.setHeader("X-RateLimit-Reset", String.valueOf(status.resetEpochSecond()));
    }

    /** Dashboard creation is a spam vector (every POST mints a new token), hence a separate, lower pool. */
    private boolean isDashboardCreate(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && "/api/dashboards".equals(request.getRequestURI());
    }

    /** Client IP: first X-Forwarded-For entry (set by nginx in prod) or remoteAddr. */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
