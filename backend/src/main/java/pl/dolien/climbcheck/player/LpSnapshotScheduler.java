package pl.dolien.climbcheck.player;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pl.dolien.climbcheck.riot.RiotApiClient;
import pl.dolien.climbcheck.riot.RiotLeagueEntryResponse;
import pl.dolien.climbcheck.riot.RiotRetryer;

import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.lp-snapshot.enabled", havingValue = "true", matchIfMissing = true)
public class LpSnapshotScheduler {

    private final PlayerRepository playerRepository;
    private final LpSnapshotRepository lpSnapshotRepository;
    private final RiotApiClient riotApiClient;
    private final RiotRetryer riotRetryer;

    public LpSnapshotScheduler(PlayerRepository playerRepository,
                               LpSnapshotRepository lpSnapshotRepository,
                               RiotApiClient riotApiClient,
                               RiotRetryer riotRetryer) {
        this.playerRepository = playerRepository;
        this.lpSnapshotRepository = lpSnapshotRepository;
        this.riotApiClient = riotApiClient;
        this.riotRetryer = riotRetryer;
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
        RiotLeagueEntryResponse league = riotRetryer.execute(
                () -> riotApiClient.getLeagueEntry(player.getRegion(), player.getPuuid()));
        int currentLp = league.leaguePoints();

        Optional<LpSnapshot> lastSnapshot =
                lpSnapshotRepository.findTopByPlayerIdOrderByTimestampDesc(player.getId());

        if (LpSnapshot.shouldCapture(lastSnapshot, currentLp, league.tier(), league.rank())) {
            lpSnapshotRepository.save(LpSnapshot.create(player, currentLp, league.tier(), league.rank()));
        }
    }

}
