package pl.dolien.climbcheck.player;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pl.dolien.climbcheck.exception.RiotRateLimitException;
import pl.dolien.climbcheck.riot.RetryPolicy;
import pl.dolien.climbcheck.riot.RiotApiClient;
import pl.dolien.climbcheck.riot.RiotLeagueEntryResponse;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.lp-snapshot.enabled", havingValue = "true", matchIfMissing = true)
public class LpSnapshotScheduler {

    private final PlayerRepository playerRepository;
    private final LpSnapshotRepository lpSnapshotRepository;
    private final RiotApiClient riotApiClient;
    private final RetryPolicy retryPolicy;

    public LpSnapshotScheduler(PlayerRepository playerRepository,
                               LpSnapshotRepository lpSnapshotRepository,
                               RiotApiClient riotApiClient,
                               RetryPolicy retryPolicy) {
        this.playerRepository = playerRepository;
        this.lpSnapshotRepository = lpSnapshotRepository;
        this.riotApiClient = riotApiClient;
        this.retryPolicy = retryPolicy;
    }

    @Scheduled(cron = "${app.lp-snapshot.cron}")
    public void captureSnapshots() {
        List<TrackedPlayer> players = playerRepository.findAll();
        for (TrackedPlayer player : players) {
            try {
                captureSnapshot(player);
            } catch (RuntimeException ex) {
                log.warn("Failed to capture LP snapshot for player id={}", player.getId(), ex);
            }
        }
    }

    void captureSnapshot(TrackedPlayer player) {
        RiotLeagueEntryResponse league = getLeagueEntryWithRetry(player);
        int currentLp = league.leaguePoints();

        Optional<LpSnapshot> lastSnapshot =
                lpSnapshotRepository.findTopByPlayerIdOrderByTimestampDesc(player.getId());

        if (LpSnapshot.shouldCapture(lastSnapshot, currentLp, league.tier(), league.rank())) {
            lpSnapshotRepository.save(LpSnapshot.create(player, currentLp, league.tier(), league.rank()));
        }
    }

    private RiotLeagueEntryResponse getLeagueEntryWithRetry(TrackedPlayer player) {
        for (int attempt = 1; ; attempt++) {
            try {
                return riotApiClient.getLeagueEntry(player.getRegion(), player.getPuuid());
            } catch (RiotRateLimitException ex) {
                if (attempt >= retryPolicy.maxAttempts()) {
                    throw ex;
                }
                Duration delay = retryPolicy.delayForAttempt(attempt, ex.getRetryAfter());
                log.warn("Riot API rate limited for player id={}, retrying in {}ms (attempt {}/{})",
                        player.getId(), delay.toMillis(), attempt, retryPolicy.maxAttempts());
                sleep(delay);
            }
        }
    }

    private void sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for rate limit retry", ex);
        }
    }
}
