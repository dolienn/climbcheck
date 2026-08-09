package pl.dolien.climbcheck.player;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface LpSnapshotRepository extends JpaRepository<LpSnapshot, Long> {

    List<LpSnapshot> findByPlayerIdOrderByTimestampAsc(Long playerId);

    /**
     * History from a given moment (chart window, e.g. 30 days) — instead of loading and
     * serializing the ENTIRE history (200+ points per player) on every dashboard GET.
     * Uses the (player_id, timestamp) index.
     */
    List<LpSnapshot> findByPlayerIdInAndTimestampAfterOrderByTimestampAsc(List<Long> playerIds, Instant after);

    Optional<LpSnapshot> findTopByPlayerIdOrderByTimestampDesc(Long playerId);

    /** Deletes snapshots older than the given moment (retention cleanup, e.g. 90 days). */
    long deleteByTimestampBefore(Instant cutoff);
}
