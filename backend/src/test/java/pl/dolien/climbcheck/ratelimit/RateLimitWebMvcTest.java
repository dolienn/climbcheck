package pl.dolien.climbcheck.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pl.dolien.climbcheck.dashboard.DashboardController;
import pl.dolien.climbcheck.dashboard.DashboardCreatedResponse;
import pl.dolien.climbcheck.dashboard.DashboardResponse;
import pl.dolien.climbcheck.dashboard.DashboardService;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DashboardController.class, properties = {
        "app.rate-limit.window=60s",
        "app.rate-limit.max-requests-per-ip=3",
        "app.rate-limit.dashboard-create-max=1"
})
class RateLimitWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardService dashboardService;

    private static DashboardResponse dashboard() {
        return new DashboardResponse("token", Instant.now(), List.of(), null);
    }

    private static DashboardCreatedResponse createdDashboard() {
        return new DashboardCreatedResponse("token", "admin-token", Instant.now());
    }

    @Test
    void defaultGroup_shouldReturn429AfterExceedingLimit() throws Exception {
        when(dashboardService.getDashboard(anyString())).thenReturn(dashboard());

        mockMvc.perform(get("/api/dashboards/token").header("X-Forwarded-For", "10.0.0.10"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "3"))
                .andExpect(header().string("X-RateLimit-Remaining", "2"))
                .andExpect(header().exists("X-RateLimit-Reset"));
        mockMvc.perform(get("/api/dashboards/token").header("X-Forwarded-For", "10.0.0.10"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Remaining", "1"));
        mockMvc.perform(get("/api/dashboards/token").header("X-Forwarded-For", "10.0.0.10"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Remaining", "0"));

        // 4th request → 429, but with the full limit headers (like Riot).
        // Sliding window: Retry-After is the precise time until the oldest timestamp
        // expires (here ~59s, not a rigid 60) — the exact value is covered by the
        // RateLimiterTest unit test with an injectable clock.
        mockMvc.perform(get("/api/dashboards/token").header("X-Forwarded-For", "10.0.0.10"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", matchesPattern("[0-9]+")))
                .andExpect(header().string("X-RateLimit-Limit", "3"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(header().exists("X-RateLimit-Reset"))
                .andExpect(jsonPath("$.message").value(containsString("Too many requests")));
    }

    @Test
    void differentIps_shouldHaveSeparateBudgets() throws Exception {
        when(dashboardService.getDashboard(anyString())).thenReturn(dashboard());

        // unique IP per test — the @WebMvcTest context is shared, so budgets
        // must not bleed between tests
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/dashboards/token").header("X-Forwarded-For", "10.0.0.30"))
                    .andExpect(status().isOk());
        }
        // 4th request from the same IP → 429
        mockMvc.perform(get("/api/dashboards/token").header("X-Forwarded-For", "10.0.0.30"))
                .andExpect(status().isTooManyRequests());

        // different IP → its own pool, everything passes
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/dashboards/token").header("X-Forwarded-For", "10.0.0.31"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void spoofedFirstForwardedForEntry_shouldNotBypassTheLimit() throws Exception {
        when(dashboardService.getDashboard(anyString())).thenReturn(dashboard());

        // The client can send its own X-Forwarded-For; the trusted proxy (Caddy) appends the
        // real IP as the LAST entry. Only the last entry counts, so rotating the first value
        // (a spoof attempt) must NOT grant a fresh budget.
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/dashboards/token")
                            .header("X-Forwarded-For", "1.2.3.4, 10.0.0.40"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/dashboards/token")
                        .header("X-Forwarded-For", "9.9.9.9, 10.0.0.40"))
                .andExpect(status().isTooManyRequests());

        // A different real IP (different last entry) has its own budget.
        mockMvc.perform(get("/api/dashboards/token")
                        .header("X-Forwarded-For", "5.5.5.5, 10.0.0.41"))
                .andExpect(status().isOk());
    }

    @Test
    void withoutForwardedFor_shouldFallBackToRemoteAddr() throws Exception {
        when(dashboardService.getDashboard(anyString())).thenReturn(dashboard());

        // No X-Forwarded-For (e.g. local dev without a proxy) → remoteAddr (127.0.0.1 in
        // MockMvc) is the key; the limit applies to it like to any other IP.
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/dashboards/token"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/dashboards/token"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void dashboardCreate_shouldHaveStricterLimitThanDefaultGroup() throws Exception {
        when(dashboardService.createDashboard()).thenReturn(createdDashboard());

        // dashboard-create limit = 1 → second POST from this IP is 429
        mockMvc.perform(post("/api/dashboards").header("X-Forwarded-For", "10.0.0.20"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/dashboards").header("X-Forwarded-For", "10.0.0.20"))
                .andExpect(status().isTooManyRequests());

        // GET (default group) has its own, higher pool — still works
        when(dashboardService.getDashboard(anyString())).thenReturn(dashboard());
        mockMvc.perform(get("/api/dashboards/token").header("X-Forwarded-For", "10.0.0.20"))
                .andExpect(status().isOk());
    }
}
