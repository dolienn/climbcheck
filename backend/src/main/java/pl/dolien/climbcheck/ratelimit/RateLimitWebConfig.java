package pl.dolien.climbcheck.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;
import java.time.Duration;

/**
 * Own-API rate limiting: registers {@link RateLimitInterceptor} for /api/**
 * (actuator and static assets are out of scope). The RateLimiter is built here from
 * @Value rather than as a separate bean — @WebMvcTest does not scan plain @Components,
 * so existing controller tests work unchanged and the limiter can be tested on its own.
 */
@Configuration
public class RateLimitWebConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor interceptor;

    public RateLimitWebConfig(@Value("${app.rate-limit.window:60s}") Duration window,
                              @Value("${app.rate-limit.max-requests-per-ip:120}") int maxRequestsPerIp,
                              @Value("${app.rate-limit.dashboard-create-max:10}") int dashboardCreateMax,
                              @Value("${app.rate-limit.enabled:true}") boolean enabled,
                              Clock clock) {
        this.interceptor = new RateLimitInterceptor(
                new RateLimiter(window, maxRequestsPerIp, dashboardCreateMax, enabled, clock));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/**");
    }
}
