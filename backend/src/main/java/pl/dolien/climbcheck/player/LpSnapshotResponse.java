package pl.dolien.climbcheck.player;

import java.time.Instant;

public record LpSnapshotResponse(
        int lp,
        Instant timestamp,
        String tier,
        String rank
) {
    public static LpSnapshotResponse of(LpSnapshot snapshot) {
        return new LpSnapshotResponse(snapshot.getLp(), snapshot.getTimestamp(),
                snapshot.getTier(), snapshot.getRank());
    }
}
