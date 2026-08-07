package pl.dolien.climbcheck.stats;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pl.dolien.climbcheck.dashboard.DashboardRepository;
import pl.dolien.climbcheck.player.LpSnapshotRepository;
import pl.dolien.climbcheck.player.PlayerRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatsServiceTest {

    @Mock
    private DashboardRepository dashboardRepository;
    @Mock
    private PlayerRepository playerRepository;
    @Mock
    private LpSnapshotRepository lpSnapshotRepository;

    private StatsService statsService;

    @BeforeEach
    void setUp() {
        statsService = new StatsService(dashboardRepository, playerRepository, lpSnapshotRepository);
    }

    @Test
    void getStats_shouldReturnLiveCountsFromTheDatabase() {
        when(dashboardRepository.count()).thenReturn(12L);
        when(playerRepository.count()).thenReturn(34L);
        when(lpSnapshotRepository.count()).thenReturn(567L);

        StatsResponse stats = statsService.getStats();

        assertThat(stats.dashboards()).isEqualTo(12);
        assertThat(stats.players()).isEqualTo(34);
        assertThat(stats.lpPoints()).isEqualTo(567);
    }

    @Test
    void getStats_shouldReturnZerosForAnEmptyDatabase() {
        when(dashboardRepository.count()).thenReturn(0L);
        when(playerRepository.count()).thenReturn(0L);
        when(lpSnapshotRepository.count()).thenReturn(0L);

        StatsResponse stats = statsService.getStats();

        assertThat(stats.dashboards()).isZero();
        assertThat(stats.players()).isZero();
        assertThat(stats.lpPoints()).isZero();
    }
}
