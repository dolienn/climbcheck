package pl.dolien.climbcheck.player;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import pl.dolien.climbcheck.dashboard.Dashboard;
import pl.dolien.climbcheck.dashboard.DashboardRepository;
import pl.dolien.climbcheck.exception.DashboardNotFoundException;
import pl.dolien.climbcheck.exception.PlayerAlreadyTrackedException;
import pl.dolien.climbcheck.exception.PlayerNotFoundException;
import pl.dolien.climbcheck.exception.UnauthorizedException;
import pl.dolien.climbcheck.riot.RiotApiClient;
import pl.dolien.climbcheck.riot.RiotLeagueEntryResponse;
import pl.dolien.climbcheck.riot.RiotRegion;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlayerServiceTest {

    private static final String TOKEN = "dashboard-token";
    private static final AddPlayerRequest REQUEST = new AddPlayerRequest(RiotRegion.EUW, "Test", "EUW");

    @Mock
    private PlayerRepository playerRepository;
    @Mock
    private DashboardRepository dashboardRepository;
    @Mock
    private RiotApiClient riotApiClient;
    @Mock
    private LpSnapshotRepository lpSnapshotRepository;

    private PlayerService playerService;

    @BeforeEach
    void setUp() {
        playerService = new PlayerService(playerRepository, dashboardRepository, riotApiClient,
                new PlayerMapper(), lpSnapshotRepository);
    }

    @Test
    void addPlayer_shouldReturnRankedResponseWithSnapshot() {
        Dashboard dashboard = Dashboard.create();
        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(riotApiClient.getPuuid(RiotRegion.EUW, "Test", "EUW")).thenReturn("puuid-123");
        when(playerRepository.existsByDashboardIdAndPuuid(any(), any())).thenReturn(false);
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, "puuid-123"))
                .thenReturn(new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "DIAMOND", "II", 45, 10, 5));
        when(riotApiClient.getProfileIconId(RiotRegion.EUW, "puuid-123")).thenReturn(7);
        when(lpSnapshotRepository.save(any(LpSnapshot.class))).thenAnswer(inv -> inv.getArgument(0));

        PlayerResponse response = playerService.addPlayer(TOKEN, REQUEST, dashboard.getAdminToken());

        assertThat(response.gameName()).isEqualTo("Test");
        assertThat(response.tagLine()).isEqualTo("EUW");
        assertThat(response.region()).isEqualTo(RiotRegion.EUW);
        assertThat(response.profileIconId()).isEqualTo(7);
        assertThat(response.tier()).isEqualTo("DIAMOND");
        assertThat(response.rank()).isEqualTo("II");
        assertThat(response.leaguePoints()).isEqualTo(45);
        assertThat(response.lpHistory()).hasSize(1);
        assertThat(response.lpHistory().get(0).lp()).isEqualTo(45);
        // snapshot stores the league-v4 rank at the moment the player is added
        assertThat(response.lpHistory().get(0).tier()).isEqualTo("DIAMOND");
        assertThat(response.lpHistory().get(0).rank()).isEqualTo("II");
        // winrate from league-v4: 10W 5L → 67% (as in the LoL client)
        assertThat(response.winrate()).isEqualTo(67);
        verify(playerRepository).save(any(TrackedPlayer.class));
        verify(lpSnapshotRepository).save(any(LpSnapshot.class));
    }

    @Test
    void addPlayer_shouldReturnUnrankedWhenNoSoloQueueEntry() {
        Dashboard dashboard = Dashboard.create();
        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(riotApiClient.getPuuid(RiotRegion.EUW, "Test", "EUW")).thenReturn("puuid-123");
        when(playerRepository.existsByDashboardIdAndPuuid(any(), any())).thenReturn(false);
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, "puuid-123"))
                .thenReturn(RiotLeagueEntryResponse.unranked());
        when(riotApiClient.getProfileIconId(RiotRegion.EUW, "puuid-123")).thenReturn(0);
        when(lpSnapshotRepository.save(any(LpSnapshot.class))).thenAnswer(inv -> inv.getArgument(0));

        PlayerResponse response = playerService.addPlayer(TOKEN, REQUEST, dashboard.getAdminToken());

        assertThat(response.tier()).isEqualTo("UNRANKED");
        assertThat(response.profileIconId()).isEqualTo(0);
        assertThat(response.leaguePoints()).isZero();
        assertThat(response.lpHistory()).hasSize(1);
        assertThat(response.lpHistory().get(0).lp()).isZero();
        // unranked = 0 games played → winrate null (column shows "—")
        assertThat(response.winrate()).isNull();
    }

    @Test
    void addPlayer_shouldThrowWhenDashboardNotFound() {
        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.empty());

        // dashboard does not exist → the error is thrown before the admin-token check
        assertThatThrownBy(() -> playerService.addPlayer(TOKEN, REQUEST, null))
                .isInstanceOf(DashboardNotFoundException.class)
                .hasMessageContaining(TOKEN);

        verify(playerRepository, never()).save(any());
        verify(lpSnapshotRepository, never()).save(any());
    }

    @Test
    void getLpHistory_shouldReturnSnapshotsOrderedByTimestamp() {
        Dashboard dashboard = Dashboard.create();
        TrackedPlayer player = TrackedPlayer.create(dashboard, RiotRegion.EUW, "Test", "EUW", "puuid-123", 0);
        ReflectionTestUtils.setField(player, "id", 1L);
        LpSnapshot snapshot1 = LpSnapshot.create(player, 40, "GOLD", "III");
        LpSnapshot snapshot2 = LpSnapshot.create(player, 75, "GOLD", "III");

        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(playerRepository.findById(1L)).thenReturn(Optional.of(player));
        when(lpSnapshotRepository.findByPlayerIdOrderByTimestampAsc(1L))
                .thenReturn(List.of(snapshot1, snapshot2));

        List<LpSnapshotResponse> history = playerService.getLpHistory(TOKEN, 1L);

        assertThat(history).hasSize(2);
        assertThat(history).extracting(LpSnapshotResponse::lp).containsExactly(40, 75);
    }

    @Test
    void getLpHistory_shouldThrowWhenDashboardNotFound() {
        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> playerService.getLpHistory(TOKEN, 1L))
                .isInstanceOf(DashboardNotFoundException.class)
                .hasMessageContaining(TOKEN);
    }

    @Test
    void getLpHistory_shouldThrowWhenPlayerNotFound() {
        Dashboard dashboard = Dashboard.create();
        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(playerRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> playerService.getLpHistory(TOKEN, 1L))
                .isInstanceOf(PlayerNotFoundException.class)
                .hasMessageContaining("Player not found: 1");
    }

    @Test
    void getLpHistory_shouldThrowWhenPlayerBelongsToAnotherDashboard() {
        Dashboard dashboard = Dashboard.create();
        ReflectionTestUtils.setField(dashboard, "id", 10L);
        Dashboard otherDashboard = Dashboard.create();
        ReflectionTestUtils.setField(otherDashboard, "id", 20L);
        TrackedPlayer player = TrackedPlayer.create(otherDashboard, RiotRegion.EUW, "Test", "EUW", "puuid-123", 0);
        ReflectionTestUtils.setField(player, "id", 1L);

        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(playerRepository.findById(1L)).thenReturn(Optional.of(player));

        assertThatThrownBy(() -> playerService.getLpHistory(TOKEN, 1L))
                .isInstanceOf(PlayerNotFoundException.class)
                .hasMessageContaining("Player not found in dashboard: 1");
    }

    @Test
    void addPlayer_shouldThrowWhenPlayerAlreadyTracked() {
        Dashboard dashboard = Dashboard.create();
        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(riotApiClient.getPuuid(RiotRegion.EUW, "Test", "EUW")).thenReturn("puuid-123");
        when(playerRepository.existsByDashboardIdAndPuuid(any(), any())).thenReturn(true);

        assertThatThrownBy(() -> playerService.addPlayer(TOKEN, REQUEST, dashboard.getAdminToken()))
                .isInstanceOf(PlayerAlreadyTrackedException.class)
                .hasMessageContaining("Test#EUW");

        verify(playerRepository, never()).save(any());
        verify(riotApiClient, never()).getLeagueEntry(any(), any());
        verify(riotApiClient, never()).getProfileIconId(any(), any());
        verify(lpSnapshotRepository, never()).save(any());
    }

    @Test
    void removePlayer_shouldDeletePlayerFromDashboard() {
        Dashboard dashboard = Dashboard.create();
        ReflectionTestUtils.setField(dashboard, "id", 10L);
        TrackedPlayer player = TrackedPlayer.create(dashboard, RiotRegion.EUW, "Test", "EUW", "puuid-123", 0);
        ReflectionTestUtils.setField(player, "id", 1L);

        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(playerRepository.findById(1L)).thenReturn(Optional.of(player));

        playerService.removePlayer(TOKEN, 1L, dashboard.getAdminToken());

        verify(playerRepository).delete(player);
    }

    @Test
    void removePlayer_shouldThrowWhenDashboardNotFound() {
        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> playerService.removePlayer(TOKEN, 1L, null))
                .isInstanceOf(DashboardNotFoundException.class)
                .hasMessageContaining(TOKEN);

        verify(playerRepository, never()).delete(any());
    }

    @Test
    void removePlayer_shouldThrowWhenPlayerNotFound() {
        Dashboard dashboard = Dashboard.create();
        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(playerRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> playerService.removePlayer(TOKEN, 1L, dashboard.getAdminToken()))
                .isInstanceOf(PlayerNotFoundException.class)
                .hasMessageContaining("Player not found: 1");

        verify(playerRepository, never()).delete(any());
    }

    @Test
    void removePlayer_shouldThrowWhenPlayerBelongsToAnotherDashboard() {
        Dashboard dashboard = Dashboard.create();
        ReflectionTestUtils.setField(dashboard, "id", 10L);
        Dashboard otherDashboard = Dashboard.create();
        ReflectionTestUtils.setField(otherDashboard, "id", 20L);
        TrackedPlayer player = TrackedPlayer.create(otherDashboard, RiotRegion.EUW, "Test", "EUW", "puuid-123", 0);
        ReflectionTestUtils.setField(player, "id", 1L);

        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(playerRepository.findById(1L)).thenReturn(Optional.of(player));

        assertThatThrownBy(() -> playerService.removePlayer(TOKEN, 1L, dashboard.getAdminToken()))
                .isInstanceOf(PlayerNotFoundException.class)
                .hasMessageContaining("Player not found in dashboard: 1");

        verify(playerRepository, never()).delete(any());
    }

    @Test
    void addPlayer_shouldThrowWhenAdminTokenMissing() {
        Dashboard dashboard = Dashboard.create();
        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));

        assertThatThrownBy(() -> playerService.addPlayer(TOKEN, REQUEST, null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("X-Admin-Token");

        verify(playerRepository, never()).save(any());
        verify(riotApiClient, never()).getPuuid(any(), any(), any());
    }

    @Test
    void addPlayer_shouldThrowWhenAdminTokenInvalid() {
        Dashboard dashboard = Dashboard.create();
        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));

        assertThatThrownBy(() -> playerService.addPlayer(TOKEN, REQUEST, "wrong-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("X-Admin-Token");

        verify(playerRepository, never()).save(any());
    }

    @Test
    void removePlayer_shouldThrowWhenAdminTokenMissing() {
        Dashboard dashboard = Dashboard.create();
        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));

        assertThatThrownBy(() -> playerService.removePlayer(TOKEN, 1L, null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("X-Admin-Token");

        verify(playerRepository, never()).delete(any());
    }
}
