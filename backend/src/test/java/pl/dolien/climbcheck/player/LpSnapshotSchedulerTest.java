package pl.dolien.climbcheck.player;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import pl.dolien.climbcheck.dashboard.Dashboard;
import pl.dolien.climbcheck.exception.RiotRateLimitException;
import pl.dolien.climbcheck.riot.RetryPolicy;
import pl.dolien.climbcheck.riot.RiotApiClient;
import pl.dolien.climbcheck.riot.RiotLeagueEntryResponse;
import pl.dolien.climbcheck.riot.RiotRegion;
import pl.dolien.climbcheck.riot.RiotRetryer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LpSnapshotSchedulerTest {

    private static final String PUUID = "puuid-123";

    @Mock
    private PlayerRepository playerRepository;
    @Mock
    private LpSnapshotRepository lpSnapshotRepository;
    @Mock
    private RiotApiClient riotApiClient;

    private LpSnapshotScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new LpSnapshotScheduler(playerRepository, lpSnapshotRepository, riotApiClient,
                new RiotRetryer(new RetryPolicy(3, 1, 10)), Clock.systemUTC());
    }

    private TrackedPlayer player() {
        Dashboard dashboard = Dashboard.create();
        return TrackedPlayer.create(dashboard, RiotRegion.EUW, "Test", "EUW", PUUID, 0);
    }

    @Test
    void captureSnapshots_shouldSaveSnapshotWhenNoPreviousSnapshot() {
        TrackedPlayer player = player();
        when(playerRepository.findAll()).thenReturn(List.of(player));
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, PUUID))
                .thenReturn(new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "DIAMOND", "II", 45, 10, 5));
        when(lpSnapshotRepository.findTopByPlayerIdOrderByTimestampDesc(any())).thenReturn(Optional.empty());

        scheduler.captureSnapshots();

        verify(lpSnapshotRepository).save(any(LpSnapshot.class));
    }

    @Test
    void captureSnapshots_shouldSaveSnapshotWhenLpChanged() {
        TrackedPlayer player = player();
        when(playerRepository.findAll()).thenReturn(List.of(player));
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, PUUID))
                .thenReturn(new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "DIAMOND", "II", 55, 10, 5));
        when(lpSnapshotRepository.findTopByPlayerIdOrderByTimestampDesc(any()))
                .thenReturn(Optional.of(LpSnapshot.create(player, 45, "GOLD", "III")));

        scheduler.captureSnapshots();

        verify(lpSnapshotRepository).save(any(LpSnapshot.class));
    }

    @Test
    void captureSnapshots_shouldSkipWhenLpAndRankUnchanged() {
        TrackedPlayer player = player();
        when(playerRepository.findAll()).thenReturn(List.of(player));
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, PUUID))
                .thenReturn(new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "DIAMOND", "II", 45, 10, 5));
        when(lpSnapshotRepository.findTopByPlayerIdOrderByTimestampDesc(any()))
                .thenReturn(Optional.of(LpSnapshot.create(player, 45, "DIAMOND", "II")));

        scheduler.captureSnapshots();

        verify(lpSnapshotRepository, never()).save(any());
    }

    @Test
    void captureSnapshots_shouldSaveSnapshotWhenLastSnapshotIsStale() {
        TrackedPlayer player = player();
        when(playerRepository.findAll()).thenReturn(List.of(player));
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, PUUID))
                .thenReturn(new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "DIAMOND", "II", 45, 10, 5));
        // LP and rank unchanged, but the last snapshot is >12h old — append a point today,
        // so the chart has a snapshot for every day
        LpSnapshot stale = LpSnapshot.create(player, 45, "DIAMOND", "II");
        ReflectionTestUtils.setField(stale, "timestamp", Instant.now().minus(Duration.ofHours(24)));
        when(lpSnapshotRepository.findTopByPlayerIdOrderByTimestampDesc(any())).thenReturn(Optional.of(stale));

        scheduler.captureSnapshots();

        verify(lpSnapshotRepository).save(any(LpSnapshot.class));
    }

    @Test
    void captureSnapshots_shouldSaveSnapshotWhenRankChangedEvenIfLpUnchanged() {
        TrackedPlayer player = player();
        when(playerRepository.findAll()).thenReturn(List.of(player));
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, PUUID))
                .thenReturn(new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "DIAMOND", "II", 45, 10, 5));
        // promotion GOLD III → DIAMOND II with identical LP — promotion resets LP around the same value
        when(lpSnapshotRepository.findTopByPlayerIdOrderByTimestampDesc(any()))
                .thenReturn(Optional.of(LpSnapshot.create(player, 45, "GOLD", "III")));

        scheduler.captureSnapshots();

        verify(lpSnapshotRepository).save(any(LpSnapshot.class));
    }

    @Test
    void captureSnapshots_shouldRetryOnRateLimitAndSaveSnapshot() {
        TrackedPlayer player = player();
        ReflectionTestUtils.setField(player, "id", 1L);
        when(playerRepository.findAll()).thenReturn(List.of(player));
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, PUUID))
                .thenThrow(new RiotRateLimitException("rate limited", Duration.ofMillis(1), Map.of()))
                .thenReturn(new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "DIAMOND", "II", 45, 10, 5));
        when(lpSnapshotRepository.findTopByPlayerIdOrderByTimestampDesc(any())).thenReturn(Optional.empty());

        scheduler.captureSnapshots();

        verify(riotApiClient, times(2)).getLeagueEntry(RiotRegion.EUW, PUUID);
        verify(lpSnapshotRepository).save(any(LpSnapshot.class));
    }

    @Test
    void captureSnapshots_shouldGiveUpAfterMaxAttemptsOnRateLimit() {
        TrackedPlayer player = player();
        ReflectionTestUtils.setField(player, "id", 1L);
        when(playerRepository.findAll()).thenReturn(List.of(player));
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, PUUID))
                .thenThrow(new RiotRateLimitException("rate limited", Duration.ofMillis(1), Map.of()));

        scheduler.captureSnapshots();

        verify(riotApiClient, times(3)).getLeagueEntry(RiotRegion.EUW, PUUID);
        verify(lpSnapshotRepository, never()).save(any());
    }

    @Test
    void captureSnapshots_shouldContinueWhenRiotApiFailsForOnePlayer() {
        TrackedPlayer okPlayer = player();
        TrackedPlayer failingPlayer = TrackedPlayer.create(Dashboard.create(), RiotRegion.EUNE, "Fail", "EUW", "puuid-fail", 0);

        when(playerRepository.findAll()).thenReturn(List.of(failingPlayer, okPlayer));
        when(riotApiClient.getLeagueEntry(RiotRegion.EUNE, "puuid-fail"))
                .thenThrow(new RuntimeException("Riot API down"));
        when(riotApiClient.getLeagueEntry(RiotRegion.EUW, PUUID))
                .thenReturn(new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "DIAMOND", "II", 45, 10, 5));
        when(lpSnapshotRepository.findTopByPlayerIdOrderByTimestampDesc(any())).thenReturn(Optional.empty());

        scheduler.captureSnapshots();

        verify(lpSnapshotRepository).save(any(LpSnapshot.class));
    }
}
