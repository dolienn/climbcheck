package pl.dolien.climbcheck.dashboard;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboards")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @PostMapping
    public ResponseEntity<DashboardCreatedResponse> createDashboard() {
        return ResponseEntity.status(HttpStatus.CREATED).body(dashboardService.createDashboard());
    }

    @GetMapping("/{token}")
    public ResponseEntity<DashboardResponse> getDashboard(@PathVariable String token) {
        return ResponseEntity.ok(dashboardService.getDashboard(token));
    }
}
