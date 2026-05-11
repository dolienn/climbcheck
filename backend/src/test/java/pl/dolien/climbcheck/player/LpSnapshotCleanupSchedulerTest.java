package pl.dolien.climbcheck.player;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LpSnapshotCleanupSchedulerTest {

    @Mock
    private LpSnapshotRepository lpSnapshotRepository;

    private LpSnapshotCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new LpSnapshotCleanupScheduler(lpSnapshotRepository, Duration.ofDays(90));
    }

    @Test
    void purgeOldSnapshots_shouldDeleteSnapshotsOlderThanMaxAge() {
        when(lpSnapshotRepository.deleteByTimestampBefore(org.mockito.ArgumentMatchers.any()))
                .thenReturn(42L);

        scheduler.purgeOldSnapshots();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(lpSnapshotRepository).deleteByTimestampBefore(cutoff.capture());

        // cutoff ≈ now − 90 days (tolerance for test execution time)
        Duration age = Duration.between(cutoff.getValue(), Instant.now());
        assertThat(age).isBetween(Duration.ofDays(90).minusSeconds(30), Duration.ofDays(90).plusSeconds(30));
    }

    @Test
    void purgeOldSnapshots_shouldRunEvenWhenNothingToDelete() {
        when(lpSnapshotRepository.deleteByTimestampBefore(org.mockito.ArgumentMatchers.any()))
                .thenReturn(0L);

        scheduler.purgeOldSnapshots();

        verify(lpSnapshotRepository).deleteByTimestampBefore(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void purgeOldSnapshots_shouldNotDeleteAnythingWhenCutoffIsInThePast() {
        // sanity: cutoff must be in the past (not before now)
        scheduler.purgeOldSnapshots();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(lpSnapshotRepository).deleteByTimestampBefore(cutoff.capture());
        assertThat(cutoff.getValue()).isBefore(Instant.now());
        assertThat(cutoff.getValue()).isBefore(Instant.now().minus(89, ChronoUnit.DAYS));
    }
}
