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
import pl.dolien.climbcheck.exception.RiotRateLimitException;
import pl.dolien.climbcheck.exception.UnauthorizedException;
import pl.dolien.climbcheck.riot.RetryPolicy;
import pl.dolien.climbcheck.riot.RiotApiClient;
import pl.dolien.climbcheck.riot.RiotLeagueEntryResponse;
import pl.dolien.climbcheck.riot.RiotMatchResponse;
import pl.dolien.climbcheck.riot.RiotRegion;
import pl.dolien.climbcheck.riot.RiotRetryer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
                new PlayerMapper(), lpSnapshotRepository, new RiotRetryer(new RetryPolicy(3, 10, 1000)));
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

    @Test
    void getRecentMatches_shouldOnlyIncludeRankedSoloMatchesOfThePlayer() {
        Dashboard dashboard = Dashboard.create();
        ReflectionTestUtils.setField(dashboard, "id", 10L);
        TrackedPlayer player = TrackedPlayer.create(dashboard, RiotRegion.EUW, "Test", "EUW", "puuid-123", 0);
        ReflectionTestUtils.setField(player, "id", 1L);

        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(playerRepository.findById(1L)).thenReturn(Optional.of(player));
        when(riotApiClient.getRecentMatchIds(RiotRegion.EUW, "puuid-123", 100))
                .thenReturn(List.of("EUW1_flex", "EUW1_ranked", "EUW1_other"));
        when(lpSnapshotRepository.findByPlayerIdOrderByTimestampAsc(1L)).thenReturn(List.of());
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, "puuid-123"))
                .thenReturn(new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "GOLD", "III", 55, 10, 5));

        RiotMatchResponse flex = new RiotMatchResponse(new RiotMatchResponse.Info(100L, 1800L, 440,
                List.of(new RiotMatchResponse.Participant("puuid-123", "Zed", 238, true, 5, 1, 9, 180, 0, "MIDDLE", "SOLO"))));
        RiotMatchResponse ranked = new RiotMatchResponse(new RiotMatchResponse.Info(200L, 1983L, 420,
                List.of(new RiotMatchResponse.Participant("puuid-123", "Yasuo", 157, false, 2, 12, 4, 201, 0, "MIDDLE", "SOLO"))));
        RiotMatchResponse other = new RiotMatchResponse(new RiotMatchResponse.Info(300L, 1900L, 420,
                List.of(new RiotMatchResponse.Participant("other-puuid", "Garen", 86, true, 1, 0, 2, 100, 0, "TOP", "SOLO"))));

        when(riotApiClient.getMatch(RiotRegion.EUW, "EUW1_flex")).thenReturn(flex);
        when(riotApiClient.getMatch(RiotRegion.EUW, "EUW1_ranked")).thenReturn(ranked);
        when(riotApiClient.getMatch(RiotRegion.EUW, "EUW1_other")).thenReturn(other);

        List<PlayerMatchResponse> matches = playerService.getRecentMatches(TOKEN, 1L).matches();

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).championName()).isEqualTo("Yasuo");
        assertThat(matches.get(0).win()).isFalse();
        assertThat(matches.get(0).kills()).isEqualTo(2);
        assertThat(matches.get(0).deaths()).isEqualTo(12);
        assertThat(matches.get(0).assists()).isEqualTo(4);
        assertThat(matches.get(0).cs()).isEqualTo(201);
        assertThat(matches.get(0).gameEndTimestamp()).isEqualTo(200L);
        assertThat(matches.get(0).lpChange()).isNull();
    }

    @Test
    void getRecentMatches_shouldCountJungleMonstersInCs() {
        Dashboard dashboard = Dashboard.create();
        ReflectionTestUtils.setField(dashboard, "id", 10L);
        TrackedPlayer player = TrackedPlayer.create(dashboard, RiotRegion.EUW, "Test", "EUW", "puuid-123", 0);
        ReflectionTestUtils.setField(player, "id", 1L);

        // jungler: 8 lane minions + 200 jungle monsters → CS must be 208, not 8
        RiotMatchResponse ranked = new RiotMatchResponse(new RiotMatchResponse.Info(
                200L, 2400L, 420,
                List.of(new RiotMatchResponse.Participant("puuid-123", "LeeSin", 234, true, 5, 2, 6, 8, 200, "JUNGLE", "NONE"))));

        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(playerRepository.findById(1L)).thenReturn(Optional.of(player));
        when(riotApiClient.getRecentMatchIds(RiotRegion.EUW, "puuid-123", 100))
                .thenReturn(List.of("EUW1_ranked"));
        when(riotApiClient.getMatch(RiotRegion.EUW, "EUW1_ranked")).thenReturn(ranked);
        when(lpSnapshotRepository.findByPlayerIdOrderByTimestampAsc(1L)).thenReturn(List.of());
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, "puuid-123"))
                .thenReturn(new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "GOLD", "III", 55, 10, 5));

        List<PlayerMatchResponse> matches = playerService.getRecentMatches(TOKEN, 1L).matches();

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).cs()).isEqualTo(208);
    }

    @Test
    void getRecentMatches_shouldRetryAfterRateLimit() {
        Dashboard dashboard = Dashboard.create();
        ReflectionTestUtils.setField(dashboard, "id", 10L);
        TrackedPlayer player = TrackedPlayer.create(dashboard, RiotRegion.EUW, "Test", "EUW", "puuid-123", 0);
        ReflectionTestUtils.setField(player, "id", 1L);
        RiotMatchResponse ranked = new RiotMatchResponse(new RiotMatchResponse.Info(200L, 1983L, 420,
                List.of(new RiotMatchResponse.Participant("puuid-123", "Yasuo", 157, false, 2, 12, 4, 201, 0, "MIDDLE", "SOLO"))));

        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(playerRepository.findById(1L)).thenReturn(Optional.of(player));
        when(riotApiClient.getRecentMatchIds(RiotRegion.EUW, "puuid-123", 100))
                .thenReturn(List.of("EUW1_ranked"));
        when(lpSnapshotRepository.findByPlayerIdOrderByTimestampAsc(1L)).thenReturn(List.of());
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, "puuid-123"))
                .thenReturn(new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "GOLD", "III", 55, 10, 5));
        when(riotApiClient.getMatch(RiotRegion.EUW, "EUW1_ranked"))
                .thenThrow(new RiotRateLimitException("rate limited", Duration.ofMillis(5), Map.of()))
                .thenReturn(ranked);

        List<PlayerMatchResponse> matches = playerService.getRecentMatches(TOKEN, 1L).matches();

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).championName()).isEqualTo("Yasuo");
        verify(riotApiClient, times(2)).getMatch(RiotRegion.EUW, "EUW1_ranked");
    }

    @Test
    void getRecentMatches_shouldThrowWhenPlayerNotFound() {
        Dashboard dashboard = Dashboard.create();
        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(playerRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> playerService.getRecentMatches(TOKEN, 1L))
                .isInstanceOf(PlayerNotFoundException.class)
                .hasMessageContaining("Player not found: 1");
    }

    @Test
    void getRecentMatches_shouldThrowWhenPlayerBelongsToAnotherDashboard() {
        Dashboard dashboard = Dashboard.create();
        ReflectionTestUtils.setField(dashboard, "id", 10L);
        Dashboard otherDashboard = Dashboard.create();
        ReflectionTestUtils.setField(otherDashboard, "id", 20L);
        TrackedPlayer player = TrackedPlayer.create(otherDashboard, RiotRegion.EUW, "Test", "EUW", "puuid-123", 0);
        ReflectionTestUtils.setField(player, "id", 1L);

        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(playerRepository.findById(1L)).thenReturn(Optional.of(player));

        assertThatThrownBy(() -> playerService.getRecentMatches(TOKEN, 1L))
                .isInstanceOf(PlayerNotFoundException.class)
                .hasMessageContaining("Player not found in dashboard: 1");
    }

    @Test
    void getRecentMatches_shouldAttributeLpDeltaToSingleMatchInSnapshotInterval() {
        Dashboard dashboard = Dashboard.create();
        ReflectionTestUtils.setField(dashboard, "id", 10L);
        TrackedPlayer player = TrackedPlayer.create(dashboard, RiotRegion.EUW, "Test", "EUW", "puuid-123", 0);
        ReflectionTestUtils.setField(player, "id", 1L);

        Instant t0 = Instant.parse("2026-07-01T12:00:00Z");
        LpSnapshot before = LpSnapshot.create(player, 100, "GOLD", "IV");
        ReflectionTestUtils.setField(before, "timestamp", t0);
        LpSnapshot after = LpSnapshot.create(player, 120, "GOLD", "IV");
        ReflectionTestUtils.setField(after, "timestamp", t0.plusSeconds(3600));

        RiotMatchResponse ranked = new RiotMatchResponse(new RiotMatchResponse.Info(
                t0.plusSeconds(1800).toEpochMilli(), 1983L, 420,
                List.of(new RiotMatchResponse.Participant("puuid-123", "Yasuo", 157, true, 6, 2, 8, 201, 0, "MIDDLE", "SOLO"))));

        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(playerRepository.findById(1L)).thenReturn(Optional.of(player));
        when(riotApiClient.getRecentMatchIds(RiotRegion.EUW, "puuid-123", 100))
                .thenReturn(List.of("EUW1_ranked"));
        when(riotApiClient.getMatch(RiotRegion.EUW, "EUW1_ranked")).thenReturn(ranked);
        when(lpSnapshotRepository.findByPlayerIdOrderByTimestampAsc(1L)).thenReturn(List.of(before, after));
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, "puuid-123"))
                .thenReturn(new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "GOLD", "III", 120, 10, 5));

        List<PlayerMatchResponse> matches = playerService.getRecentMatches(TOKEN, 1L).matches();

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).lpChange()).isEqualTo(20);
    }

    @Test
    void getRecentMatches_shouldAttributeCombinedDeltaToNewestMatchInInterval() {
        Dashboard dashboard = Dashboard.create();
        ReflectionTestUtils.setField(dashboard, "id", 10L);
        TrackedPlayer player = TrackedPlayer.create(dashboard, RiotRegion.EUW, "Test", "EUW", "puuid-123", 0);
        ReflectionTestUtils.setField(player, "id", 1L);

        Instant t0 = Instant.parse("2026-07-01T12:00:00Z");
        LpSnapshot before = LpSnapshot.create(player, 100, "GOLD", "IV");
        ReflectionTestUtils.setField(before, "timestamp", t0);
        LpSnapshot after = LpSnapshot.create(player, 140, "GOLD", "IV");
        ReflectionTestUtils.setField(after, "timestamp", t0.plusSeconds(3600));

        RiotMatchResponse older = new RiotMatchResponse(new RiotMatchResponse.Info(
                t0.plusSeconds(1200).toEpochMilli(), 1983L, 420,
                List.of(new RiotMatchResponse.Participant("puuid-123", "Yasuo", 157, true, 6, 2, 8, 201, 0, "MIDDLE", "SOLO"))));
        RiotMatchResponse newer = new RiotMatchResponse(new RiotMatchResponse.Info(
                t0.plusSeconds(2400).toEpochMilli(), 1983L, 420,
                List.of(new RiotMatchResponse.Participant("puuid-123", "Zed", 238, true, 5, 1, 9, 180, 0, "MIDDLE", "SOLO"))));

        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(playerRepository.findById(1L)).thenReturn(Optional.of(player));
        when(riotApiClient.getRecentMatchIds(RiotRegion.EUW, "puuid-123", 100))
                .thenReturn(List.of("EUW1_newer", "EUW1_older"));
        when(riotApiClient.getMatch(RiotRegion.EUW, "EUW1_newer")).thenReturn(newer);
        when(riotApiClient.getMatch(RiotRegion.EUW, "EUW1_older")).thenReturn(older);
        when(lpSnapshotRepository.findByPlayerIdOrderByTimestampAsc(1L)).thenReturn(List.of(before, after));
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, "puuid-123"))
                .thenReturn(new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "GOLD", "III", 140, 10, 5));

        List<PlayerMatchResponse> matches = playerService.getRecentMatches(TOKEN, 1L).matches();

        // newest first: the newest match in the interval gets the combined delta
        assertThat(matches).hasSize(2);
        assertThat(matches.get(0).championName()).isEqualTo("Zed");
        assertThat(matches.get(0).lpChange()).isEqualTo(40);
        assertThat(matches.get(1).championName()).isEqualTo("Yasuo");
        assertThat(matches.get(1).lpChange()).isNull();
    }

    @Test
    void getRecentMatches_shouldAttributeLiveLpDeltaToMatchAfterLastSnapshot() {
        Dashboard dashboard = Dashboard.create();
        ReflectionTestUtils.setField(dashboard, "id", 10L);
        TrackedPlayer player = TrackedPlayer.create(dashboard, RiotRegion.EUW, "Test", "EUW", "puuid-123", 0);
        ReflectionTestUtils.setField(player, "id", 1L);

        Instant t0 = Instant.parse("2026-07-01T12:00:00Z");
        LpSnapshot snapshot = LpSnapshot.create(player, 100, "GOLD", "IV");
        ReflectionTestUtils.setField(snapshot, "timestamp", t0);

        RiotMatchResponse ranked = new RiotMatchResponse(new RiotMatchResponse.Info(
                t0.plusSeconds(7200).toEpochMilli(), 1983L, 420,
                List.of(new RiotMatchResponse.Participant("puuid-123", "Yasuo", 157, true, 6, 2, 8, 201, 0, "MIDDLE", "SOLO"))));

        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(playerRepository.findById(1L)).thenReturn(Optional.of(player));
        when(riotApiClient.getRecentMatchIds(RiotRegion.EUW, "puuid-123", 100))
                .thenReturn(List.of("EUW1_ranked"));
        when(riotApiClient.getMatch(RiotRegion.EUW, "EUW1_ranked")).thenReturn(ranked);
        when(lpSnapshotRepository.findByPlayerIdOrderByTimestampAsc(1L)).thenReturn(List.of(snapshot));
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, "puuid-123"))
                .thenReturn(new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "GOLD", "III", 135, 10, 5));

        List<PlayerMatchResponse> matches = playerService.getRecentMatches(TOKEN, 1L).matches();

        // a match after the last snapshot gets the live LP delta (league-v4) as a "virtual now" snapshot
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).lpChange()).isEqualTo(35);
    }

    @Test
    void getRecentMatches_shouldLeaveLpChangeNullWhenNoSnapshots() {
        Dashboard dashboard = Dashboard.create();
        ReflectionTestUtils.setField(dashboard, "id", 10L);
        TrackedPlayer player = TrackedPlayer.create(dashboard, RiotRegion.EUW, "Test", "EUW", "puuid-123", 0);
        ReflectionTestUtils.setField(player, "id", 1L);

        RiotMatchResponse ranked = new RiotMatchResponse(new RiotMatchResponse.Info(
                1_000_000L, 1983L, 420,
                List.of(new RiotMatchResponse.Participant("puuid-123", "Yasuo", 157, true, 6, 2, 8, 201, 0, "MIDDLE", "SOLO"))));

        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(playerRepository.findById(1L)).thenReturn(Optional.of(player));
        when(riotApiClient.getRecentMatchIds(RiotRegion.EUW, "puuid-123", 100))
                .thenReturn(List.of("EUW1_ranked"));
        when(riotApiClient.getMatch(RiotRegion.EUW, "EUW1_ranked")).thenReturn(ranked);
        when(lpSnapshotRepository.findByPlayerIdOrderByTimestampAsc(1L)).thenReturn(List.of());
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, "puuid-123"))
                .thenReturn(new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "GOLD", "III", 120, 10, 5));

        List<PlayerMatchResponse> matches = playerService.getRecentMatches(TOKEN, 1L).matches();

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).lpChange()).isNull();
    }

    @Test
    void getRecentMatches_shouldDegradeGracefullyWhenLiveLeagueRateLimited() {
        Dashboard dashboard = Dashboard.create();
        ReflectionTestUtils.setField(dashboard, "id", 10L);
        TrackedPlayer player = TrackedPlayer.create(dashboard, RiotRegion.EUW, "Test", "EUW", "puuid-123", 0);
        ReflectionTestUtils.setField(player, "id", 1L);

        Instant t0 = Instant.parse("2026-07-01T12:00:00Z");
        LpSnapshot snapshot = LpSnapshot.create(player, 100, "GOLD", "IV");
        ReflectionTestUtils.setField(snapshot, "timestamp", t0);

        RiotMatchResponse ranked = new RiotMatchResponse(new RiotMatchResponse.Info(
                t0.plusSeconds(7200).toEpochMilli(), 1983L, 420,
                List.of(new RiotMatchResponse.Participant("puuid-123", "Yasuo", 157, true, 6, 2, 8, 201, 0, "MIDDLE", "SOLO"))));

        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(playerRepository.findById(1L)).thenReturn(Optional.of(player));
        when(riotApiClient.getRecentMatchIds(RiotRegion.EUW, "puuid-123", 100))
                .thenReturn(List.of("EUW1_ranked"));
        when(riotApiClient.getMatch(RiotRegion.EUW, "EUW1_ranked")).thenReturn(ranked);
        when(lpSnapshotRepository.findByPlayerIdOrderByTimestampAsc(1L)).thenReturn(List.of(snapshot));
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, "puuid-123"))
                .thenThrow(new RiotRateLimitException("rate limited", Duration.ofMillis(5), Map.of()));

        List<PlayerMatchResponse> matches = playerService.getRecentMatches(TOKEN, 1L).matches();

        // matches still return despite 429 on league-v4 — LP attribution is best-effort (null)
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).lpChange()).isNull();
    }

    @Test
    void getRecentMatches_shouldAttributeMatchEndingExactlyAtSnapshotToFollowingInterval() {
        Dashboard dashboard = Dashboard.create();
        ReflectionTestUtils.setField(dashboard, "id", 10L);
        TrackedPlayer player = TrackedPlayer.create(dashboard, RiotRegion.EUW, "Test", "EUW", "puuid-123", 0);
        ReflectionTestUtils.setField(player, "id", 1L);

        Instant t0 = Instant.parse("2026-07-01T12:00:00Z");
        LpSnapshot before = LpSnapshot.create(player, 100, "GOLD", "IV");
        ReflectionTestUtils.setField(before, "timestamp", t0);
        LpSnapshot atMatchEnd = LpSnapshot.create(player, 120, "GOLD", "IV");
        ReflectionTestUtils.setField(atMatchEnd, "timestamp", t0.plusSeconds(3600));

        // the match ends exactly at the 120-LP snapshot — the snapshot already contains the match result
        RiotMatchResponse ranked = new RiotMatchResponse(new RiotMatchResponse.Info(
                t0.plusSeconds(3600).toEpochMilli(), 1983L, 420,
                List.of(new RiotMatchResponse.Participant("puuid-123", "Yasuo", 157, true, 6, 2, 8, 201, 0, "MIDDLE", "SOLO"))));

        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(playerRepository.findById(1L)).thenReturn(Optional.of(player));
        when(riotApiClient.getRecentMatchIds(RiotRegion.EUW, "puuid-123", 100))
                .thenReturn(List.of("EUW1_ranked"));
        when(riotApiClient.getMatch(RiotRegion.EUW, "EUW1_ranked")).thenReturn(ranked);
        when(lpSnapshotRepository.findByPlayerIdOrderByTimestampAsc(1L)).thenReturn(List.of(before, atMatchEnd));
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, "puuid-123"))
                .thenReturn(new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "GOLD", "III", 135, 10, 5));

        List<PlayerMatchResponse> matches = playerService.getRecentMatches(TOKEN, 1L).matches();

        // match in the interval [120-LP snapshot, now (live 135)] → delta +15
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).lpChange()).isEqualTo(15);
    }

    // Streak

    private RiotMatchResponse rankedMatch(String matchId, boolean win) {
        return rankedMatch(matchId, win, "Yasuo", 157);
    }

    private RiotMatchResponse rankedMatch(String matchId, boolean win, String champion, int championId) {
        long ts = 1_000_000_000_000L + Long.parseLong(matchId.replaceAll("\\D", ""));
        return new RiotMatchResponse(new RiotMatchResponse.Info(
                ts, 1983L, 420,
                List.of(new RiotMatchResponse.Participant("puuid-123", champion, championId, win,
                        2, 12, 4, 201, 0, "MIDDLE", "SOLO"))));
    }

    private PlayerMatchesResponse matchesWithStreak(List<RiotMatchResponse> matchesNewestFirst) {
        Dashboard dashboard = Dashboard.create();
        ReflectionTestUtils.setField(dashboard, "id", 10L);
        TrackedPlayer player = TrackedPlayer.create(dashboard, RiotRegion.EUW, "Test", "EUW", "puuid-123", 0);
        ReflectionTestUtils.setField(player, "id", 1L);

        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < matchesNewestFirst.size(); i++) {
            ids.add("EUW1_" + (matchesNewestFirst.size() - i));
        }
        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(playerRepository.findById(1L)).thenReturn(Optional.of(player));
        when(riotApiClient.getRecentMatchIds(RiotRegion.EUW, "puuid-123", 100)).thenReturn(ids);
        for (int i = 0; i < matchesNewestFirst.size(); i++) {
            when(riotApiClient.getMatch(RiotRegion.EUW, ids.get(i))).thenReturn(matchesNewestFirst.get(i));
        }
        when(lpSnapshotRepository.findByPlayerIdOrderByTimestampAsc(1L)).thenReturn(List.of());
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, "puuid-123"))
                .thenReturn(new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "GOLD", "III", 55, 10, 5));
        return playerService.getRecentMatches(TOKEN, 1L);
    }

    @Test
    void getRecentMatches_shouldReturnWinStreak() {
        PlayerMatchesResponse response = matchesWithStreak(List.of(
                rankedMatch("5", true),  // newest — win
                rankedMatch("4", true),
                rankedMatch("3", true),
                rankedMatch("2", true),
                rankedMatch("1", false))); // streak breaker

        assertThat(response.streak()).isEqualTo(4);
    }

    @Test
    void getRecentMatches_shouldReturnLossStreak() {
        PlayerMatchesResponse response = matchesWithStreak(List.of(
                rankedMatch("4", false),
                rankedMatch("3", false),
                rankedMatch("2", false),
                rankedMatch("1", true)));

        assertThat(response.streak()).isEqualTo(-3);
    }

    @Test
    void getRecentMatches_shouldReturnStreakOfOneAfterImmediateFlip() {
        PlayerMatchesResponse response = matchesWithStreak(List.of(
                rankedMatch("2", true),
                rankedMatch("1", false)));

        assertThat(response.streak()).isEqualTo(1);
    }

    @Test
    void getRecentMatches_shouldReturnTopChampionsWithWinrate() {
        // 5 games: Zed 3 (2W 1L → 67%), Yasuo 2 (1W 1L → 50%) — order: Zed (more games), Yasuo
        PlayerMatchesResponse response = matchesWithStreak(List.of(
                rankedMatch("5", true, "Zed", 238),
                rankedMatch("4", false, "Zed", 238),
                rankedMatch("3", true, "Zed", 238),
                rankedMatch("2", true, "Yasuo", 157),
                rankedMatch("1", false, "Yasuo", 157)));

        assertThat(response.topChampions()).hasSize(2);
        assertThat(response.topChampions().get(0).championName()).isEqualTo("Zed");
        assertThat(response.topChampions().get(0).championId()).isEqualTo(238);
        assertThat(response.topChampions().get(0).games()).isEqualTo(3);
        assertThat(response.topChampions().get(0).wins()).isEqualTo(2);
        assertThat(response.topChampions().get(0).winrate()).isEqualTo(67);
        assertThat(response.topChampions().get(1).championName()).isEqualTo("Yasuo");
        assertThat(response.topChampions().get(1).games()).isEqualTo(2);
        assertThat(response.topChampions().get(1).winrate()).isEqualTo(50);
    }

    @Test
    void getRecentMatches_shouldSortTopChampionsByGamesThenWinrate() {
        // after 2 games: LeeSin 2W (100%) before Garen 1W 1L (50%) — same games, higher winrate first
        PlayerMatchesResponse response = matchesWithStreak(List.of(
                rankedMatch("4", true, "LeeSin", 234),
                rankedMatch("3", true, "LeeSin", 234),
                rankedMatch("2", true, "Garen", 86),
                rankedMatch("1", false, "Garen", 86)));

        assertThat(response.topChampions()).hasSize(2);
        assertThat(response.topChampions().get(0).championName()).isEqualTo("LeeSin");
        assertThat(response.topChampions().get(0).winrate()).isEqualTo(100);
        assertThat(response.topChampions().get(1).championName()).isEqualTo("Garen");
        assertThat(response.topChampions().get(1).winrate()).isEqualTo(50);
    }

    @Test
    void getRecentMatches_shouldLimitTopChampionsToThree() {
        // 4 different champions → only top 3 is returned
        PlayerMatchesResponse response = matchesWithStreak(List.of(
                rankedMatch("4", true, "Zed", 238),
                rankedMatch("3", true, "Yasuo", 157),
                rankedMatch("2", true, "Garen", 86),
                rankedMatch("1", true, "LeeSin", 234)));

        assertThat(response.topChampions()).hasSize(3);
        // each has 1 game and 100% → alphabetical tie-break
        assertThat(response.topChampions()).extracting(ChampionStatsResponse::championName)
                .containsExactly("Garen", "LeeSin", "Yasuo");
    }

    @Test
    void getRecentMatches_shouldAggregateChampionsFromMoreThanTheVisibleMatches() {
        // 15 ranked games all on the same champion — the Most Played aggregation must
        // scan more games than the 10 visible matches (Riot has no per-season champion
        // stats, so the sample size is the only lever for a meaningful "Most Played").
        java.util.ArrayList<RiotMatchResponse> games = new java.util.ArrayList<>();
        for (int i = 15; i >= 1; i--) {
            games.add(rankedMatch(String.valueOf(i), true, "Zed", 238));
        }
        PlayerMatchesResponse response = matchesWithStreak(games);

        assertThat(response.matches()).hasSize(10); // visible list stays at 10
        assertThat(response.topChampions().get(0).championName()).isEqualTo("Zed");
        assertThat(response.topChampions().get(0).games()).isEqualTo(15);
    }

    @Test
    void getRecentMatches_shouldReturnEmptyTopChampionsWhenNoRankedGames() {
        Dashboard dashboard = Dashboard.create();
        ReflectionTestUtils.setField(dashboard, "id", 10L);
        TrackedPlayer player = TrackedPlayer.create(dashboard, RiotRegion.EUW, "Test", "EUW", "puuid-123", 0);
        ReflectionTestUtils.setField(player, "id", 1L);

        // only flex (queue 440) — no ranked games → streak null, empty list
        RiotMatchResponse flex = new RiotMatchResponse(new RiotMatchResponse.Info(
                100L, 1983L, 440,
                List.of(new RiotMatchResponse.Participant("puuid-123", "Zed", 238, true, 5, 1, 9, 180, 0, "MIDDLE", "SOLO"))));

        when(dashboardRepository.findByToken(TOKEN)).thenReturn(Optional.of(dashboard));
        when(playerRepository.findById(1L)).thenReturn(Optional.of(player));
        when(riotApiClient.getRecentMatchIds(RiotRegion.EUW, "puuid-123", 100))
                .thenReturn(List.of("EUW1_flex"));
        when(riotApiClient.getMatch(RiotRegion.EUW, "EUW1_flex")).thenReturn(flex);

        PlayerMatchesResponse response = playerService.getRecentMatches(TOKEN, 1L);

        assertThat(response.matches()).isEmpty();
        assertThat(response.streak()).isNull();
        assertThat(response.topChampions()).isEmpty();
    }
}
