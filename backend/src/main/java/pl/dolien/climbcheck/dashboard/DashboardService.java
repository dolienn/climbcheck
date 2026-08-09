package pl.dolien.climbcheck.dashboard;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.dolien.climbcheck.exception.DashboardNotFoundException;
import pl.dolien.climbcheck.player.LpSnapshot;
import pl.dolien.climbcheck.player.LpSnapshotRepository;
import pl.dolien.climbcheck.player.LpSnapshotResponse;
import pl.dolien.climbcheck.player.PlayerMapper;
import pl.dolien.climbcheck.player.PlayerRepository;
import pl.dolien.climbcheck.player.PlayerResponse;
import pl.dolien.climbcheck.player.TrackedPlayer;
import pl.dolien.climbcheck.riot.RankOrder;
import pl.dolien.climbcheck.riot.RiotApiClient;
import pl.dolien.climbcheck.riot.RiotLeagueEntryResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class DashboardService {

    /** The LP Progression Trends chart shows the last month — that's how much history we load. */
    private static final Duration HISTORY_WINDOW = Duration.ofDays(30);

    private final DashboardRepository dashboardRepository;
    private final PlayerRepository playerRepository;
    private final LpSnapshotRepository lpSnapshotRepository;
    private final RiotApiClient riotApiClient;
    private final PlayerMapper playerMapper;
    private final Clock clock;

    public DashboardService(DashboardRepository dashboardRepository,
                            PlayerRepository playerRepository,
                            LpSnapshotRepository lpSnapshotRepository,
                            RiotApiClient riotApiClient,
                            PlayerMapper playerMapper,
                            Clock clock) {
        this.dashboardRepository = dashboardRepository;
        this.playerRepository = playerRepository;
        this.lpSnapshotRepository = lpSnapshotRepository;
        this.riotApiClient = riotApiClient;
        this.playerMapper = playerMapper;
        this.clock = clock;
    }

    @Transactional
    public DashboardCreatedResponse createDashboard() {
        Dashboard dashboard = dashboardRepository.save(Dashboard.create());
        return new DashboardCreatedResponse(
                dashboard.getToken(), dashboard.getAdminToken(), dashboard.getCreatedAt());
    }

    // Transactional even though it's a GET: the LP history grouping touches the lazy
    // player association of each snapshot, and the opportunistic snapshot write below
    // commits with the read (open-in-view is disabled).
    @Transactional
    public DashboardResponse getDashboard(String token) {
        Dashboard dashboard = dashboardRepository.findByToken(token)
                .orElseThrow(() -> new DashboardNotFoundException("Dashboard not found for token: " + token));

        List<TrackedPlayer> players = playerRepository.findByDashboardId(dashboard.getId());
        Map<Long, List<LpSnapshot>> snapshotsByPlayer = loadLpHistory(players);

        List<PlayerResponse> ranking = players.stream()
                .map(player -> {
                    RiotLeagueEntryResponse league = riotApiClient.getLeagueEntry(player.getRegion(), player.getPuuid());
                    List<LpSnapshot> history = snapshotsByPlayer.getOrDefault(player.getId(), List.of());
                    captureSnapshotIfChanged(player, league, history);
                    // winrate from league-v4 (wins/losses of the current season) — exactly what the LoL client shows;
                    // zero extra Riot API calls because the league entry is already fetched for rank/LP
                    return playerMapper.toResponse(player, league,
                            history.stream().map(LpSnapshotResponse::of).toList(),
                            league.winrate(),
                            league.totalGames());
                })
                .sorted(RankOrder.byRank())
                .toList();

        // Admin token only for legacy (admin_token == token): new dashboards' management
        // key is secret and never appears in the GET view response.
        String adminToken = dashboard.getToken().equals(dashboard.getAdminToken())
                ? dashboard.getAdminToken()
                : null;
        return new DashboardResponse(dashboard.getToken(), dashboard.getCreatedAt(), ranking, adminToken);
    }

    /**
     * Opportunistic snapshot: the dashboard GET already fetches league-v4 for every player — if
     * LP/rank changed since the last snapshot or more than 12h passed, we append a point right
     * away (without an extra Riot API call). This way the LP Progression Trends curve gets a
     * point on every dashboard open, not just every 2h from the cron.
     */
    private void captureSnapshotIfChanged(TrackedPlayer player, RiotLeagueEntryResponse league, List<LpSnapshot> history) {
        Optional<LpSnapshot> last = history.isEmpty()
                ? Optional.empty()
                : Optional.of(history.get(history.size() - 1));
        if (LpSnapshot.shouldCapture(last, league.leaguePoints(), league.tier(), league.rank(), clock.instant())) {
            lpSnapshotRepository.save(LpSnapshot.create(player, league.leaguePoints(), league.tier(), league.rank()));
        }
    }

    private Map<Long, List<LpSnapshot>> loadLpHistory(List<TrackedPlayer> players) {
        List<Long> playerIds = players.stream().map(TrackedPlayer::getId).toList();
        if (playerIds.isEmpty()) {
            return Map.of();
        }
        // Only the chart window (30 days), not the whole history — with 200+ snapshots per player
        // loading and serializing the full history slowed down the dashboard GET. The
        // (player_id, timestamp) index serves this query entirely.
        Instant cutoff = Instant.now().minus(HISTORY_WINDOW);
        return lpSnapshotRepository
                .findByPlayerIdInAndTimestampAfterOrderByTimestampAsc(playerIds, cutoff)
                .stream()
                .collect(Collectors.groupingBy(snapshot -> snapshot.getPlayer().getId()));
    }
}
