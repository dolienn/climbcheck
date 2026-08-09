package pl.dolien.climbcheck.player;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.dolien.climbcheck.dashboard.Dashboard;
import pl.dolien.climbcheck.dashboard.DashboardRepository;
import pl.dolien.climbcheck.exception.DashboardNotFoundException;
import pl.dolien.climbcheck.exception.PlayerAlreadyTrackedException;
import pl.dolien.climbcheck.exception.PlayerNotFoundException;
import pl.dolien.climbcheck.exception.UnauthorizedException;
import pl.dolien.climbcheck.riot.RiotApiClient;
import pl.dolien.climbcheck.riot.RiotLeagueEntryResponse;

import java.util.List;
import java.util.Objects;

@Service
public class PlayerService {

    private final PlayerRepository playerRepository;
    private final DashboardRepository dashboardRepository;
    private final RiotApiClient riotApiClient;
    private final PlayerMapper playerMapper;
    private final LpSnapshotRepository lpSnapshotRepository;

    public PlayerService(PlayerRepository playerRepository,
                         DashboardRepository dashboardRepository,
                         RiotApiClient riotApiClient,
                         PlayerMapper playerMapper,
                         LpSnapshotRepository lpSnapshotRepository) {
        this.playerRepository = playerRepository;
        this.dashboardRepository = dashboardRepository;
        this.riotApiClient = riotApiClient;
        this.playerMapper = playerMapper;
        this.lpSnapshotRepository = lpSnapshotRepository;
    }

    @Transactional
    public PlayerResponse addPlayer(String dashboardToken, AddPlayerRequest request, String adminToken) {
        Dashboard dashboard = dashboardRepository.findByToken(dashboardToken)
                .orElseThrow(() -> new DashboardNotFoundException("Dashboard not found for token: " + dashboardToken));
        requireAdmin(dashboard, adminToken);

        String puuid = riotApiClient.getPuuid(request.region(), request.gameName(), request.tagLine());

        if (playerRepository.existsByDashboardIdAndPuuid(dashboard.getId(), puuid)) {
            throw new PlayerAlreadyTrackedException(
                    "Player already tracked: " + request.gameName() + "#" + request.tagLine());
        }

        int profileIconId = riotApiClient.getProfileIconId(request.region(), puuid);
        TrackedPlayer player = TrackedPlayer.create(dashboard, request.region(), request.gameName(),
                request.tagLine(), puuid, profileIconId);
        playerRepository.save(player);

        RiotLeagueEntryResponse league = riotApiClient.getLeagueEntry(request.region(), puuid);
        LpSnapshot snapshot = lpSnapshotRepository.save(
                LpSnapshot.create(player, league.leaguePoints(), league.tier(), league.rank()));
        List<LpSnapshotResponse> history = List.of(LpSnapshotResponse.of(snapshot));

        // winrate from league-v4 (wins/losses of the current season) — as in the LoL client;
        // the league entry is already fetched when adding a player, so we pass it right away
        return playerMapper.toResponse(player, league, history, league.winrate(), league.totalGames());
    }

    // readOnly: the ownership check below touches the lazy dashboard association, which
    // needs an open transaction (open-in-view is disabled)
    @Transactional(readOnly = true)
    public List<LpSnapshotResponse> getLpHistory(String dashboardToken, Long playerId) {
        Dashboard dashboard = dashboardRepository.findByToken(dashboardToken)
                .orElseThrow(() -> new DashboardNotFoundException("Dashboard not found for token: " + dashboardToken));

        TrackedPlayer player = playerRepository.findById(playerId)
                .orElseThrow(() -> new PlayerNotFoundException("Player not found: " + playerId));

        if (!Objects.equals(player.getDashboard().getId(), dashboard.getId())) {
            throw new PlayerNotFoundException("Player not found in dashboard: " + playerId);
        }

        return lpSnapshotRepository.findByPlayerIdOrderByTimestampAsc(player.getId()).stream()
                .map(LpSnapshotResponse::of)
                .toList();
    }

    @Transactional
    public void removePlayer(String dashboardToken, Long playerId, String adminToken) {
        Dashboard dashboard = dashboardRepository.findByToken(dashboardToken)
                .orElseThrow(() -> new DashboardNotFoundException("Dashboard not found for token: " + dashboardToken));
        requireAdmin(dashboard, adminToken);

        TrackedPlayer player = playerRepository.findById(playerId)
                .orElseThrow(() -> new PlayerNotFoundException("Player not found: " + playerId));

        if (!Objects.equals(player.getDashboard().getId(), dashboard.getId())) {
            throw new PlayerNotFoundException("Player not found in dashboard: " + playerId);
        }

        // LP snapshots are removed cascadingly at the DB level (@OnDelete(CASCADE) in LpSnapshot)
        playerRepository.delete(player);
    }

    /**
     * Mutations require a secret X-Admin-Token matching the dashboard. Legacy dashboards
     * (admin_token == token) accept the plain view link as the management key.
     */
    private void requireAdmin(Dashboard dashboard, String adminToken) {
        if (adminToken == null || !Objects.equals(dashboard.getAdminToken(), adminToken)) {
            throw new UnauthorizedException("Invalid or missing X-Admin-Token");
        }
    }
}
