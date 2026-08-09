package pl.dolien.climbcheck.player;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/dashboards/{token}/players")
public class PlayerController {

    private final PlayerService playerService;
    private final MatchInsightsService matchInsightsService;

    public PlayerController(PlayerService playerService, MatchInsightsService matchInsightsService) {
        this.playerService = playerService;
        this.matchInsightsService = matchInsightsService;
    }

    @DeleteMapping("/{playerId}")
    public ResponseEntity<Void> removePlayer(
            @PathVariable String token,
            @PathVariable Long playerId,
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken) {
        playerService.removePlayer(token, playerId, adminToken);
        return ResponseEntity.noContent().build();
    }

    @PostMapping
    public ResponseEntity<PlayerResponse> addPlayer(
            @PathVariable String token,
            @Valid @RequestBody AddPlayerRequest request,
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(playerService.addPlayer(token, request, adminToken));
    }

    @GetMapping("/{playerId}/lp-history")
    public ResponseEntity<List<LpSnapshotResponse>> getLpHistory(
            @PathVariable String token,
            @PathVariable Long playerId) {
        return ResponseEntity.ok(playerService.getLpHistory(token, playerId));
    }

    @GetMapping("/{playerId}/matches")
    public ResponseEntity<PlayerMatchesResponse> getRecentMatches(
            @PathVariable String token,
            @PathVariable Long playerId) {
        return ResponseEntity.ok(matchInsightsService.getRecentMatches(token, playerId));
    }
}
