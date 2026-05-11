package pl.dolien.climbcheck.dashboard;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pl.dolien.climbcheck.exception.DashboardNotFoundException;
import pl.dolien.climbcheck.player.LpSnapshotResponse;
import pl.dolien.climbcheck.player.PlayerResponse;
import pl.dolien.climbcheck.riot.RiotRegion;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DashboardController.class)
class DashboardControllerTest {

    private static final String TOKEN = "dashboard-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardService dashboardService;

    @Test
    void createDashboard_shouldReturn201WithToken() throws Exception {
        when(dashboardService.createDashboard())
                .thenReturn(new DashboardCreatedResponse(TOKEN, "admin-token", Instant.now()));

        mockMvc.perform(post("/api/dashboards"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value(TOKEN))
                .andExpect(jsonPath("$.adminToken").value("admin-token"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void getDashboard_shouldReturn200WithRanking() throws Exception {
        PlayerResponse player = new PlayerResponse(1L, "Test", "EUW", RiotRegion.EUW, 7, "DIAMOND", "II", 45, 60, 100,
                60, 40,
                List.of(new LpSnapshotResponse(40, Instant.parse("2026-01-01T10:00:00Z"), "GOLD", "III"),
                        new LpSnapshotResponse(45, Instant.parse("2026-01-02T10:00:00Z"), "GOLD", "III")));
        when(dashboardService.getDashboard(TOKEN))
                .thenReturn(new DashboardResponse(TOKEN, Instant.now(), List.of(player), null));

        mockMvc.perform(get("/api/dashboards/{token}", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(TOKEN))
                .andExpect(jsonPath("$.players[0].gameName").value("Test"))
                .andExpect(jsonPath("$.players[0].region").value("EUW"))
                .andExpect(jsonPath("$.players[0].tier").value("DIAMOND"))
                .andExpect(jsonPath("$.players[0].leaguePoints").value(45))
                .andExpect(jsonPath("$.players[0].winrate").value(60))
                .andExpect(jsonPath("$.players[0].totalGames").value(100))
                .andExpect(jsonPath("$.players[0].wins").value(60))
                .andExpect(jsonPath("$.players[0].losses").value(40))
                .andExpect(jsonPath("$.players[0].lpHistory[0].lp").value(40))
                .andExpect(jsonPath("$.players[0].lpHistory[1].lp").value(45))
                .andExpect(jsonPath("$.players[0].lpHistory[0].timestamp").value("2026-01-01T10:00:00Z"));
    }

    @Test
    void getDashboard_shouldReturn404WhenDashboardNotFound() throws Exception {
        when(dashboardService.getDashboard(TOKEN))
                .thenThrow(new DashboardNotFoundException("Dashboard not found for token: " + TOKEN));

        mockMvc.perform(get("/api/dashboards/{token}", TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Dashboard not found for token: " + TOKEN));
    }
}
