package pl.dolien.climbcheck.stats;

import org.springframework.stereotype.Service;
import pl.dolien.climbcheck.dashboard.DashboardRepository;
import pl.dolien.climbcheck.player.LpSnapshotRepository;
import pl.dolien.climbcheck.player.PlayerRepository;

@Service
public class StatsService {

    private final DashboardRepository dashboardRepository;
    private final PlayerRepository playerRepository;
    private final LpSnapshotRepository lpSnapshotRepository;

    public StatsService(DashboardRepository dashboardRepository,
                        PlayerRepository playerRepository,
                        LpSnapshotRepository lpSnapshotRepository) {
        this.dashboardRepository = dashboardRepository;
        this.playerRepository = playerRepository;
        this.lpSnapshotRepository = lpSnapshotRepository;
    }

    public StatsResponse getStats() {
        return new StatsResponse(
                dashboardRepository.count(),
                playerRepository.count(),
                lpSnapshotRepository.count()
        );
    }
}
