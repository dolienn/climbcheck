package pl.dolien.climbcheck.player;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pl.dolien.climbcheck.exception.DashboardNotFoundException;
import pl.dolien.climbcheck.exception.PlayerNotFoundException;
import pl.dolien.climbcheck.exception.RiotRateLimitException;
import pl.dolien.climbcheck.exception.UnauthorizedException;
import pl.dolien.climbcheck.riot.RiotRegion;

import java.time.Duration;
import java.util.Map;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.List;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlayerController.class)
class PlayerControllerTest {

    private static final String TOKEN = "dashboard-token";
    private static final String VALID_BODY = """
            {"region":"EUW","gameName":"Test","tagLine":"EUW"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PlayerService playerService;
    @MockitoBean
    private MatchInsightsService matchInsightsService;

    @Test
    void addPlayer_shouldReturn201WithPlayer() throws Exception {
        when(playerService.addPlayer(anyString(), any(AddPlayerRequest.class), nullable(String.class)))
                .thenReturn(new PlayerResponse(1L, "Test", "EUW", RiotRegion.EUW, 7, "DIAMOND", "II", 45, null, null, null, null, List.of()));

        mockMvc.perform(post("/api/dashboards/{token}/players", TOKEN)
                        .header("X-Admin-Token", "admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gameName").value("Test"))
                .andExpect(jsonPath("$.tagLine").value("EUW"))
                .andExpect(jsonPath("$.tier").value("DIAMOND"))
                .andExpect(jsonPath("$.leaguePoints").value(45))
                .andExpect(jsonPath("$.lpHistory").isArray());
    }

    @Test
    void addPlayer_shouldReturn401WhenAdminTokenMissing() throws Exception {
        when(playerService.addPlayer(anyString(), any(AddPlayerRequest.class), nullable(String.class)))
                .thenThrow(new UnauthorizedException("Invalid or missing X-Admin-Token"));

        mockMvc.perform(post("/api/dashboards/{token}/players", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid or missing X-Admin-Token"));
    }

    @Test
    void addPlayer_shouldReturn400WhenValidationFails() throws Exception {
        mockMvc.perform(post("/api/dashboards/{token}/players", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"region":null,"gameName":"","tagLine":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(allOf(
                        containsString("Region is required"),
                        containsString("GameName must not be blank"),
                        containsString("TagLine must not be blank"))));
    }

    @Test
    void addPlayer_shouldReturn400WhenRiotIdTooLong() throws Exception {
        mockMvc.perform(post("/api/dashboards/{token}/players", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"region":"EUW","gameName":"abcdefghijklmnopq","tagLine":"ABCDEF"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(allOf(
                        containsString("Invalid gameName"),
                        containsString("Invalid tagLine"))));
    }

    @Test
    void addPlayer_shouldReturn400WhenRiotIdFormatInvalid() throws Exception {
        mockMvc.perform(post("/api/dashboards/{token}/players", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"region":"EUW","gameName":"Dzik#i","tagLine":"XX"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(allOf(
                        containsString("Invalid gameName"),
                        containsString("Invalid tagLine"))));
    }

    @Test
    void addPlayer_shouldAcceptUnicodeRiotId() throws Exception {
        // Riot IDs allow accented letters in both the game name and the tag line
        // (e.g. OłJeleń#jeleń) — validation must not reject them with a 400.
        when(playerService.addPlayer(anyString(), any(AddPlayerRequest.class), nullable(String.class)))
                .thenReturn(new PlayerResponse(1L, "OłJeleń", "jeleń", RiotRegion.EUNE, 7,
                        "SILVER", "II", 15, null, null, null, null, List.of()));

        mockMvc.perform(post("/api/dashboards/{token}/players", TOKEN)
                        .header("X-Admin-Token", "admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"region":"EUNE","gameName":"OłJeleń","tagLine":"jeleń"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void addPlayer_shouldReturn404WhenDashboardNotFound() throws Exception {
        when(playerService.addPlayer(anyString(), any(AddPlayerRequest.class), nullable(String.class)))
                .thenThrow(new DashboardNotFoundException("Dashboard not found for token: " + TOKEN));

        mockMvc.perform(post("/api/dashboards/{token}/players", TOKEN)
                        .header("X-Admin-Token", "admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Dashboard not found for token: " + TOKEN));
    }

    @Test
    void getLpHistory_shouldReturn200WithSnapshots() throws Exception {
        when(playerService.getLpHistory(TOKEN, 1L))
                .thenReturn(List.of(
                        new LpSnapshotResponse(40, Instant.parse("2026-01-01T10:00:00Z"), "GOLD", "III"),
                        new LpSnapshotResponse(45, Instant.parse("2026-01-02T10:00:00Z"), "GOLD", "III")));

        mockMvc.perform(get("/api/dashboards/{token}/players/{playerId}/lp-history", TOKEN, 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].lp").value(40))
                .andExpect(jsonPath("$[1].lp").value(45))
                .andExpect(jsonPath("$[0].timestamp").value("2026-01-01T10:00:00Z"));
    }

    @Test
    void addPlayer_shouldReturn429WhenRiotRateLimited() throws Exception {
        when(playerService.addPlayer(anyString(), any(AddPlayerRequest.class), nullable(String.class)))
                .thenThrow(new RiotRateLimitException("Riot API rate limit exceeded",
                        Duration.ofSeconds(30), Map.of()));

        mockMvc.perform(post("/api/dashboards/{token}/players", TOKEN)
                        .header("X-Admin-Token", "admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.message").value(containsString("retry after 30s")));
    }

    @Test
    void getLpHistory_shouldReturn404WhenPlayerNotFound() throws Exception {
        when(playerService.getLpHistory(TOKEN, 99L))
                .thenThrow(new PlayerNotFoundException("Player not found: 99"));

        mockMvc.perform(get("/api/dashboards/{token}/players/{playerId}/lp-history", TOKEN, 99L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Player not found: 99"));
    }

    @Test
    void removePlayer_shouldReturn204() throws Exception {
        mockMvc.perform(delete("/api/dashboards/{token}/players/{playerId}", TOKEN, 1L)
                        .header("X-Admin-Token", "admin-token"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(playerService).removePlayer(TOKEN, 1L, "admin-token");
    }

    @Test
    void removePlayer_shouldReturn401WhenAdminTokenMissing() throws Exception {
        doThrow(new UnauthorizedException("Invalid or missing X-Admin-Token"))
                .when(playerService).removePlayer(eq(TOKEN), eq(1L), nullable(String.class));

        mockMvc.perform(delete("/api/dashboards/{token}/players/{playerId}", TOKEN, 1L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid or missing X-Admin-Token"));
    }

    @Test
    void removePlayer_shouldReturn404WhenPlayerNotFound() throws Exception {
        doThrow(new PlayerNotFoundException("Player not found: 99"))
                .when(playerService).removePlayer(eq(TOKEN), eq(99L), nullable(String.class));

        mockMvc.perform(delete("/api/dashboards/{token}/players/{playerId}", TOKEN, 99L)
                        .header("X-Admin-Token", "admin-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Player not found: 99"));
    }

    @Test
    void getRecentMatches_shouldReturn200WithMatchesAndStreak() throws Exception {
        when(matchInsightsService.getRecentMatches(anyString(), anyLong()))
                .thenReturn(new PlayerMatchesResponse(
                        List.of(new PlayerMatchResponse("Yasuo", 157, false, 2, 12, 4, 201,
                                1983, 1700000000000L, "MIDDLE", "SOLO", 20)),
                        4,
                        List.of(new ChampionStatsResponse("Yasuo", 157, 5, 3, 60))));

        mockMvc.perform(get("/api/dashboards/{token}/players/{playerId}/matches", TOKEN, 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches[0].championName").value("Yasuo"))
                .andExpect(jsonPath("$.matches[0].win").value(false))
                .andExpect(jsonPath("$.matches[0].kills").value(2))
                .andExpect(jsonPath("$.matches[0].cs").value(201))
                .andExpect(jsonPath("$.matches[0].lpChange").value(20))
                .andExpect(jsonPath("$.streak").value(4))
                .andExpect(jsonPath("$.topChampions[0].championName").value("Yasuo"))
                .andExpect(jsonPath("$.topChampions[0].games").value(5))
                .andExpect(jsonPath("$.topChampions[0].winrate").value(60));
    }

    @Test
    void getRecentMatches_shouldReturn404WhenPlayerNotFound() throws Exception {
        when(matchInsightsService.getRecentMatches(anyString(), anyLong()))
                .thenThrow(new PlayerNotFoundException("Player not found: 99"));

        mockMvc.perform(get("/api/dashboards/{token}/players/{playerId}/matches", TOKEN, 99L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Player not found: 99"));
    }
}
