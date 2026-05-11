package pl.dolien.climbcheck.player;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlayerRepository extends JpaRepository<TrackedPlayer, Long> {

    List<TrackedPlayer> findByDashboardId(Long dashboardId);

    boolean existsByDashboardIdAndPuuid(Long dashboardId, String puuid);
}
