package pl.dolien.climbcheck.dashboard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pl.dolien.climbcheck.exception.DashboardNotFoundException;
import pl.dolien.climbcheck.player.LpSnapshot;
import pl.dolien.climbcheck.player.LpSnapshotRepository;
import pl.dolien.climbcheck.player.LpSnapshotResponse;
import pl.dolien.climbcheck.player.PlayerMapper;
import pl.dolien.climbcheck.player.PlayerRepository;
import pl.dolien.climbcheck.player.PlayerResponse;
import pl.dolien.climbcheck.player.TrackedPlayer;
import pl.dolien.climbcheck.riot.RiotApiClient;
import pl.dolien.climbcheck.riot.RiotLeagueEntryResponse;
import pl.dolien.climbcheck.riot.RiotRegion;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    private static final String TOKEN = "dashboard-token";

    @Mock
    private DashboardRepository dashboardRepository;
    @Mock
    private PlayerRepository playerRepository;
    @Mock
    private LpSnapshotRepository lpSnapshotRepository;
    @Mock
    private RiotApiClient riotApiClient;

    private DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        dashboardService = new DashboardService(dashboardRepository, playerRepository,
                lpSnapshotRepository, riotApiClient, new PlayerMapper());
    }

    @Test
    void createDashboard_shouldReturnDashboardWithTokenAndAdminToken() {
        Dashboard dashboard = Dashboard.create();
        when(dashboardRepository.save(any(Dashboard.class))).thenReturn(dashboard);

        DashboardCreatedResponse response = dashboardService.createDashboard();

        assertThat(response.token()).isEqualTo(dashboard.getToken());
        // the only moment the creator receives the secret management key
        assertThat(response.adminToken()).isEqualTo(dashboard.getAdminToken());
        assertThat(response.createdAt()).isEqualTo(dashboard.getCreatedAt());
    }

    @Test
    void getDashboard_shouldNotExposeAdminTokenForNewDashboards() {
        Dashboard dashboard = Dashboard.create();
        // new dashboard: adminToken != token — GET must hide it
        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(playerRepository.findByDashboardId(any())).thenReturn(List.of());

        DashboardResponse response = dashboardService.getDashboard(TOKEN);

        assertThat(response.adminToken()).isNull();
    }

    @Test
    void getDashboard_shouldExposeAdminTokenForLegacyDashboards() {
        Dashboard dashboard = Dashboard.create();
        // legacy (from migration V6): admin_token == token — management key = view link
        ReflectionTestUtils.setField(dashboard, "adminToken", dashboard.getToken());
        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(playerRepository.findByDashboardId(any())).thenReturn(List.of());

        DashboardResponse response = dashboardService.getDashboard(TOKEN);

        assertThat(response.adminToken()).isEqualTo(dashboard.getToken());
    }

    @Test
    void getDashboard_shouldSortPlayersByRankDescending() {
        Dashboard dashboard = Dashboard.create();
        TrackedPlayer silver = TrackedPlayer.create(dashboard, RiotRegion.EUW, "Silver", "EUW", "puuid-silver", 0);
        TrackedPlayer challenger = TrackedPlayer.create(dashboard, RiotRegion.EUW, "Challenger", "EUW", "puuid-challenger", 0);
        TrackedPlayer diamond = TrackedPlayer.create(dashboard, RiotRegion.EUW, "Diamond", "EUW", "puuid-diamond", 0);

        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(playerRepository.findByDashboardId(any())).thenReturn(List.of(silver, challenger, diamond));
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, "puuid-silver"))
                .thenReturn(new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "SILVER", "I", 55, 10, 5));
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, "puuid-challenger"))
                .thenReturn(new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "CHALLENGER", "I", 700, 10, 5));
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, "puuid-diamond"))
                .thenReturn(new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "DIAMOND", "I", 45, 10, 5));

        DashboardResponse response = dashboardService.getDashboard(TOKEN);

        List<String> orderedGameNames = response.players().stream().map(PlayerResponse::gameName).toList();
        assertThat(orderedGameNames).containsExactly("Challenger", "Diamond", "Silver");
        // winrate from league-v4 (wins/losses): 10W 5L → 67% — no extra Riot API calls
        assertThat(response.players()).allMatch(p -> p.winrate() == 67);
    }

    @Test
    void getDashboard_shouldSortByRankNotByLp() {
        Dashboard dashboard = Dashboard.create();
        TrackedPlayer silver1 = TrackedPlayer.create(dashboard, RiotRegion.EUW, "Silver1", "EUW", "puuid-silver1", 0);
        TrackedPlayer emerald4 = TrackedPlayer.create(dashboard, RiotRegion.EUW, "Emerald4", "EUW", "puuid-emerald4", 0);
        TrackedPlayer unranked = TrackedPlayer.create(dashboard, RiotRegion.EUW, "Unranked", "EUW", "puuid-unranked", 0);

        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(playerRepository.findByDashboardId(any())).thenReturn(List.of(silver1, unranked, emerald4));
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, "puuid-silver1"))
                .thenReturn(new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "SILVER", "I", 76, 10, 5));
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, "puuid-emerald4"))
                .thenReturn(new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "EMERALD", "IV", 20, 10, 5));
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, "puuid-unranked"))
                .thenReturn(RiotLeagueEntryResponse.unranked());

        DashboardResponse response = dashboardService.getDashboard(TOKEN);

        List<String> orderedGameNames = response.players().stream().map(PlayerResponse::gameName).toList();
        assertThat(orderedGameNames).containsExactly("Emerald4", "Silver1", "Unranked");
    }

    @Test
    void getDashboard_shouldIncludeLpHistoryPerPlayer() {
        Dashboard dashboard = Dashboard.create();
        TrackedPlayer player = TrackedPlayer.create(dashboard, RiotRegion.EUW, "Test", "EUW", "puuid-123", 0);
        ReflectionTestUtils.setField(player, "id", 1L);
        LpSnapshot snapshot1 = LpSnapshot.create(player, 40, "GOLD", "III");
        LpSnapshot snapshot2 = LpSnapshot.create(player, 75, "GOLD", "III");

        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(playerRepository.findByDashboardId(any())).thenReturn(List.of(player));
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, "puuid-123"))
                .thenReturn(new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "DIAMOND", "II", 75, 20, 10));
        when(lpSnapshotRepository.findByPlayerIdInAndTimestampAfterOrderByTimestampAsc(any(), any())).thenReturn(List.of(snapshot1, snapshot2));

        DashboardResponse response = dashboardService.getDashboard(TOKEN);

        List<LpSnapshotResponse> history = response.players().get(0).lpHistory();
        assertThat(history).hasSize(2);
        assertThat(history).extracting(LpSnapshotResponse::lp).containsExactly(40, 75);
    }

    @Test
    void getDashboard_shouldReturnNullWinrateWhenUnranked() {
        Dashboard dashboard = Dashboard.create();
        TrackedPlayer player = TrackedPlayer.create(dashboard, RiotRegion.EUW, "Test", "EUW", "puuid-123", 0);

        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(playerRepository.findByDashboardId(any())).thenReturn(List.of(player));
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, "puuid-123"))
                .thenReturn(RiotLeagueEntryResponse.unranked());
        when(lpSnapshotRepository.findByPlayerIdInAndTimestampAfterOrderByTimestampAsc(any(), any())).thenReturn(List.of());

        DashboardResponse response = dashboardService.getDashboard(TOKEN);

        // unranked = 0 games played → winrate null (column shows "—")
        assertThat(response.players()).hasSize(1);
        assertThat(response.players().get(0).winrate()).isNull();
    }

    @Test
    void getDashboard_shouldCaptureSnapshotOnViewWhenLpChanged() {
        Dashboard dashboard = Dashboard.create();
        TrackedPlayer player = TrackedPlayer.create(dashboard, RiotRegion.EUW, "Test", "EUW", "puuid-123", 0);
        ReflectionTestUtils.setField(player, "id", 1L);
        LpSnapshot old = LpSnapshot.create(player, 40, "GOLD", "III");
        ReflectionTestUtils.setField(old, "timestamp", Instant.now().minus(Duration.ofHours(24)));

        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(playerRepository.findByDashboardId(any())).thenReturn(List.of(player));
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, "puuid-123"))
                .thenReturn(new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "DIAMOND", "II", 75, 20, 10));
        when(lpSnapshotRepository.findByPlayerIdInAndTimestampAfterOrderByTimestampAsc(any(), any())).thenReturn(List.of(old));

        dashboardService.getDashboard(TOKEN);

        // the dashboard GET appends a snapshot opportunistically (LP/rank changed) — no extra Riot API call
        verify(lpSnapshotRepository).save(any(LpSnapshot.class));
    }

    @Test
    void getDashboard_shouldNotCaptureSnapshotWhenFreshAndUnchanged() {
        Dashboard dashboard = Dashboard.create();
        TrackedPlayer player = TrackedPlayer.create(dashboard, RiotRegion.EUW, "Test", "EUW", "puuid-123", 0);
        ReflectionTestUtils.setField(player, "id", 1L);
        // fresh snapshot (now) with identical rank/LP as league-v4 → no duplicate
        LpSnapshot fresh = LpSnapshot.create(player, 75, "DIAMOND", "II");

        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(playerRepository.findByDashboardId(any())).thenReturn(List.of(player));
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, "puuid-123"))
                .thenReturn(new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "DIAMOND", "II", 75, 20, 10));
        when(lpSnapshotRepository.findByPlayerIdInAndTimestampAfterOrderByTimestampAsc(any(), any())).thenReturn(List.of(fresh));

        dashboardService.getDashboard(TOKEN);

        verify(lpSnapshotRepository, never()).save(any());
    }

    @Test
    void getDashboard_shouldThrowWhenDashboardNotFound() {
        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dashboardService.getDashboard(TOKEN))
                .isInstanceOf(DashboardNotFoundException.class)
                .hasMessageContaining(TOKEN);
    }
}
