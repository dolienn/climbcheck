package pl.dolien.climbcheck.dashboard;

import pl.dolien.climbcheck.player.PlayerResponse;

import java.time.Instant;
import java.util.List;

public record DashboardResponse(
        String token,
        Instant createdAt,
        List<PlayerResponse> players,
        /**
         * Admin token only for legacy dashboards (admin_token == token, i.e. the view link
         * is the management key). Always null for new dashboards — GET never exposes the
         * secret mutation key.
         */
        String adminToken
) {
}
