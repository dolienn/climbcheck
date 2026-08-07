package pl.dolien.climbcheck.stats;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /** Public usage counters for the landing page — no token required. */
    @GetMapping
    public StatsResponse getStats() {
        return statsService.getStats();
    }
}
