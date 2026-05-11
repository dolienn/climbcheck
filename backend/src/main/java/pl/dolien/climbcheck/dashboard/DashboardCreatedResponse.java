package pl.dolien.climbcheck.dashboard;

import java.time.Instant;

/**
 * Response of POST /api/dashboards — includes adminToken because this is the only
 * moment the creator receives the management key (later GETs never expose it).
 */
public record DashboardCreatedResponse(
        String token,
        String adminToken,
        Instant createdAt
) {
}
