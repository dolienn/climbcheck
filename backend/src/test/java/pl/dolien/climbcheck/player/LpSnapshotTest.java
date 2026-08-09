package pl.dolien.climbcheck.player;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import pl.dolien.climbcheck.dashboard.Dashboard;
import pl.dolien.climbcheck.riot.RiotRegion;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LpSnapshotTest {

    private static final Instant NOW = Instant.parse("2026-08-09T12:00:00Z");

    private LpSnapshot snapshotAt(Instant timestamp) {
        TrackedPlayer player = TrackedPlayer.create(Dashboard.create(), RiotRegion.EUW, "Test", "EUW", "puuid-123", 0);
        LpSnapshot snapshot = LpSnapshot.create(player, 45, "GOLD", "III");
        ReflectionTestUtils.setField(snapshot, "timestamp", timestamp);
        return snapshot;
    }

    @Test
    void shouldCapture_whenThereIsNoPreviousSnapshot() {
        assertThat(LpSnapshot.shouldCapture(Optional.empty(), 45, "GOLD", "III", NOW)).isTrue();
    }

    @Test
    void shouldCapture_whenLpChanged() {
        assertThat(LpSnapshot.shouldCapture(
                Optional.of(snapshotAt(NOW.minus(Duration.ofHours(1)))), 60, "GOLD", "III", NOW)).isTrue();
    }

    @Test
    void shouldCapture_whenTierOrRankChanged() {
        assertThat(LpSnapshot.shouldCapture(
                Optional.of(snapshotAt(NOW.minus(Duration.ofHours(1)))), 45, "PLATINUM", "IV", NOW)).isTrue();
    }

    @Test
    void shouldNotCapture_whenUnchangedAndFresh() {
        assertThat(LpSnapshot.shouldCapture(
                Optional.of(snapshotAt(NOW.minus(Duration.ofHours(1)))), 45, "GOLD", "III", NOW)).isFalse();
    }

    @Test
    void shouldNotCapture_whenUnchangedAndExactlyAtGranularityBoundary() {
        // stale = timestamp < now - 12h — a snapshot exactly 12h old is NOT stale yet
        assertThat(LpSnapshot.shouldCapture(Optional.of(snapshotAt(NOW.minus(LpSnapshot.DAILY_GRANULARITY))),
                45, "GOLD", "III", NOW)).isFalse();
    }

    @Test
    void shouldCapture_whenUnchangedButOlderThanGranularity() {
        assertThat(LpSnapshot.shouldCapture(Optional.of(snapshotAt(
                        NOW.minus(LpSnapshot.DAILY_GRANULARITY).minusSeconds(1))),
                45, "GOLD", "III", NOW)).isTrue();
    }
}
