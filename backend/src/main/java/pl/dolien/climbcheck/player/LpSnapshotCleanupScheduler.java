package pl.dolien.climbcheck.player;

import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * LP history retention: snapshots are written every 2h × number of players, so the
 * database grows unboundedly. The LP Progression Trends chart only shows the last
 * month, so older rows can be safely removed (default 90 days). Runs once a day;
 * the chart is rebuilt from current snapshots anyway.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.lp-snapshot.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class LpSnapshotCleanupScheduler {

    private final LpSnapshotRepository lpSnapshotRepository;
    private final Duration maxAge;

    public LpSnapshotCleanupScheduler(LpSnapshotRepository lpSnapshotRepository,
                                      @Value("${app.lp-snapshot.cleanup.max-age-days:90d}") Duration maxAge) {
        this.lpSnapshotRepository = lpSnapshotRepository;
        this.maxAge = maxAge;
    }

    @Scheduled(cron = "${app.lp-snapshot.cleanup.cron}")
    @Transactional
    public void purgeOldSnapshots() {
        Instant cutoff = Instant.now().minus(maxAge);
        long deleted = lpSnapshotRepository.deleteByTimestampBefore(cutoff);
        if (deleted > 0) {
            log.info("Deleted {} LP snapshots older than {} days", deleted, maxAge.toDays());
        }
    }
}
