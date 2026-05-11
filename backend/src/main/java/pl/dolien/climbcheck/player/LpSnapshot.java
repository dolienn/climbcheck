package pl.dolien.climbcheck.player;

import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import static jakarta.persistence.FetchType.LAZY;

@Entity
@Getter
@Table(name = "lp_snapshot")
public class LpSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "player_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private TrackedPlayer player;

    @Column(nullable = false)
    private int lp;

    @Column(name = "timestamp", nullable = false, updatable = false)
    private Instant timestamp;

    /** Rank (tier/division) at snapshot time — null for rows written before V4. */
    @Column(length = 16)
    private String tier;

    @Column(length = 8)
    private String rank;

    /**
     * Guarantees ≥1 point per day on the chart: a snapshot is also written when LP and
     * rank are unchanged but the last one is older than 12h, so the curve has a daily point.
     */
    public static final Duration DAILY_GRANULARITY = Duration.ofHours(12);

    protected LpSnapshot() {} // JPA

    public static LpSnapshot create(TrackedPlayer player, int lp, String tier, String rank) {
        LpSnapshot snapshot = new LpSnapshot();
        snapshot.player = player;
        snapshot.lp = lp;
        snapshot.timestamp = Instant.now();
        snapshot.tier = tier;
        snapshot.rank = rank;
        return snapshot;
    }

    /**
     * Whether to append a new snapshot: no previous one, LP/rank changed, or the last one
     * is older than 12h. Shared by the scheduler (every 2h) and the opportunistic write on
     * GET dashboard (league-v4 is fetched for the ranking anyway — snapshot costs 0 Riot calls).
     */
    public static boolean shouldCapture(Optional<LpSnapshot> lastSnapshot, int lp, String tier, String rank) {
        if (lastSnapshot.isEmpty()) {
            return true;
        }
        LpSnapshot last = lastSnapshot.get();
        boolean changed = last.getLp() != lp
                || !Objects.equals(last.getTier(), tier)
                || !Objects.equals(last.getRank(), rank);
        boolean stale = last.getTimestamp().isBefore(Instant.now().minus(DAILY_GRANULARITY));
        return changed || stale;
    }
}
