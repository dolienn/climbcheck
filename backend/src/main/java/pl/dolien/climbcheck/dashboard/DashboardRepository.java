package pl.dolien.climbcheck.dashboard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DashboardRepository extends JpaRepository<Dashboard, Long> {
    Optional<Dashboard> findByToken(String token);
}
