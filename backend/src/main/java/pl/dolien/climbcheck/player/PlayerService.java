package pl.dolien.climbcheck.player;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import pl.dolien.climbcheck.dashboard.Dashboard;
import pl.dolien.climbcheck.dashboard.DashboardRepository;
import pl.dolien.climbcheck.exception.DashboardNotFoundException;
import pl.dolien.climbcheck.exception.PlayerAlreadyTrackedException;
import pl.dolien.climbcheck.exception.PlayerNotFoundException;
import pl.dolien.climbcheck.exception.RiotRateLimitException;
import pl.dolien.climbcheck.exception.UnauthorizedException;
import pl.dolien.climbcheck.riot.RiotApiClient;
import pl.dolien.climbcheck.riot.RiotLeagueEntryResponse;
import pl.dolien.climbcheck.riot.RiotMatchResponse;
import pl.dolien.climbcheck.riot.RiotRetryer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class PlayerService {

    private final PlayerRepository playerRepository;
    private final DashboardRepository dashboardRepository;
    private final RiotApiClient riotApiClient;
    private final PlayerMapper playerMapper;
    private final LpSnapshotRepository lpSnapshotRepository;
    private final RiotRetryer riotRetryer;

    public PlayerService(PlayerRepository playerRepository,
                         DashboardRepository dashboardRepository,
                         RiotApiClient riotApiClient,
                         PlayerMapper playerMapper,
                         LpSnapshotRepository lpSnapshotRepository,
                         RiotRetryer riotRetryer) {
        this.playerRepository = playerRepository;
        this.dashboardRepository = dashboardRepository;
        this.riotApiClient = riotApiClient;
        this.playerMapper = playerMapper;
        this.lpSnapshotRepository = lpSnapshotRepository;
        this.riotRetryer = riotRetryer;
    }

    private static final int RECENT_MATCH_IDS_COUNT = 100;
    private static final int QUEUE_RANKED_SOLO = 420;
    /** How many recent ranked games we show in the matches list. */
    private static final int MAX_RECENT_MATCHES = 10;
    /**
     * How many recent ranked games we scan for the Most Played aggregation (cap on
     * match-v5 calls; responses are cached 60 min, so repeated views are free).
     * Riot does not expose per-champion season stats, so the aggregation reads the
     * newest ranked games — a much larger sample than the visible 10 matches.
     */
    private static final int MAX_CHAMPION_GAMES = 50;

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

    // readOnly: the ownership check below touches the lazy dashboard association, which
    // needs an open transaction (open-in-view is disabled)
    @Transactional(readOnly = true)
    public PlayerMatchesResponse getRecentMatches(String dashboardToken, Long playerId) {
        Dashboard dashboard = dashboardRepository.findByToken(dashboardToken)
                .orElseThrow(() -> new DashboardNotFoundException("Dashboard not found for token: " + dashboardToken));

        TrackedPlayer player = playerRepository.findById(playerId)
                .orElseThrow(() -> new PlayerNotFoundException("Player not found: " + playerId));

        if (!Objects.equals(player.getDashboard().getId(), dashboard.getId())) {
            throw new PlayerNotFoundException("Player not found in dashboard: " + playerId);
        }

        List<String> matchIds = riotApiClient.getRecentMatchIds(player.getRegion(), player.getPuuid(), RECENT_MATCH_IDS_COUNT);

        // Walk through the newest ranked matches (max MAX_CHAMPION_GAMES) — from one sample
        // we take the visible matches (10 newest), the current streak and the champions
        // for the Most Played aggregation.
        List<MatchData> rankedNewestFirst = new ArrayList<>();
        for (String matchId : matchIds) {
            RiotMatchResponse match = riotRetryer.execute(
                    () -> riotApiClient.getMatch(player.getRegion(), matchId));
            if (match.info() == null || match.info().queueId() != QUEUE_RANKED_SOLO) {
                continue;
            }
            match.info().participants().stream()
                    .filter(participant -> Objects.equals(participant.puuid(), player.getPuuid()))
                    .findFirst()
                    .ifPresent(participant -> rankedNewestFirst.add(new MatchData(
                            participant.championName(),
                            participant.championId(),
                            participant.win(),
                            participant.kills(),
                            participant.deaths(),
                            participant.assists(),
                            // CS = lane minions + jungle monsters (e.g. junglers have
                            // few totalMinionsKilled but many neutralMinionsKilled)
                            participant.totalMinionsKilled() + participant.neutralMinionsKilled(),
                            match.info().gameDuration(),
                            match.info().gameEndTimestamp(),
                            participant.lane(),
                            participant.role())));
            if (rankedNewestFirst.size() == MAX_CHAMPION_GAMES) {
                break;
            }
        }

        List<MatchData> visible = rankedNewestFirst.stream().limit(MAX_RECENT_MATCHES).toList();
        List<PlayerMatchResponse> matches = attachLpChanges(player, visible);
        return new PlayerMatchesResponse(matches, computeStreak(rankedNewestFirst),
                computeTopChampions(rankedNewestFirst));
    }

    /**
     * Top 3 most-played champions from recent ranked games (max MAX_CHAMPION_GAMES)
     * with winrate. Sorting: by games first, then winrate (tie → game order).
     */
    private List<ChampionStatsResponse> computeTopChampions(List<MatchData> rankedNewestFirst) {
        return rankedNewestFirst.stream()
                .collect(Collectors.groupingBy(MatchData::championName,
                        Collectors.toList()))
                .values().stream()
                .map(games -> {
                    int wins = (int) games.stream().filter(MatchData::win).count();
                    int winrate = Math.round(wins * 100f / games.size());
                    MatchData first = games.get(0);
                    return new ChampionStatsResponse(first.championName(), first.championId(),
                            games.size(), wins, winrate);
                })
                .sorted(Comparator.comparingInt(ChampionStatsResponse::games).reversed()
                        .thenComparing(Comparator.comparingInt(ChampionStatsResponse::winrate).reversed())
                        .thenComparing(ChampionStatsResponse::championName))
                .limit(3)
                .toList();
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

    /**
     * Current streak: how many of the most recent games are wins (positive) or losses
     * (negative) at the start of the newest-match list. null when the player has no ranked games.
     */
    private Integer computeStreak(List<MatchData> rankedNewestFirst) {
        if (rankedNewestFirst.isEmpty()) {
            return null;
        }
        boolean firstWon = rankedNewestFirst.get(0).win();
        int count = 0;
        for (MatchData match : rankedNewestFirst) {
            if (match.win() != firstWon) {
                break;
            }
            count++;
        }
        return firstWon ? count : -count;
    }

    /**
     * Assigns each match an LP change based on the player's snapshot history.
     * Riot match-v5 does not expose LP gained/lost per game, so the total delta of each
     * snapshot interval (including "live" league-v4 LP as a virtual "now" snapshot) is
     * assigned to the newest match played within that interval; the remaining matches in
     * the same interval have no data (null) — they will get values once further snapshots
     * narrow the intervals to single matches.
     */
    private List<PlayerMatchResponse> attachLpChanges(TrackedPlayer player, List<MatchData> matchesNewestFirst) {
        if (matchesNewestFirst.isEmpty()) {
            return List.of();
        }

        List<SnapshotPoint> points = new ArrayList<>();
        for (LpSnapshot snapshot : lpSnapshotRepository.findByPlayerIdOrderByTimestampAsc(player.getId())) {
            points.add(new SnapshotPoint(snapshot.getTimestamp(), snapshot.getLp()));
        }
        try {
            RiotLeagueEntryResponse live = riotRetryer.execute(
                    () -> riotApiClient.getLeagueEntry(player.getRegion(), player.getPuuid()));
            points.add(new SnapshotPoint(Instant.now(), live.leaguePoints()));
        } catch (RiotRateLimitException ex) {
            // LP attribution is best-effort: when league-v4 does not answer after retries,
            // we skip the virtual "now" snapshot and return matches with null (LP for
            // intervals between real snapshots still appears), instead of failing the
            // whole endpoint despite the loaded match data.
        }

        List<MatchData> matchesOldestFirst = new ArrayList<>(matchesNewestFirst);
        Collections.reverse(matchesOldestFirst);

        int n = matchesOldestFirst.size();
        Map<Integer, List<Integer>> matchesByInterval = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int interval = findInterval(points, matchesOldestFirst.get(i).gameEndTimestamp());
            if (interval >= 0) {
                matchesByInterval.computeIfAbsent(interval, key -> new ArrayList<>()).add(i);
            }
        }

        Integer[] deltas = new Integer[n];
        for (Map.Entry<Integer, List<Integer>> entry : matchesByInterval.entrySet()) {
            int interval = entry.getKey();
            if (interval >= points.size() - 1) {
                continue; // no closing snapshot — data will arrive with the next snapshot
            }
            int delta = points.get(interval + 1).lp() - points.get(interval).lp();
            List<Integer> indices = entry.getValue();
            deltas[indices.get(indices.size() - 1)] = delta; // newest match in the interval
        }

        List<PlayerMatchResponse> result = new ArrayList<>();
        for (int i = n - 1; i >= 0; i--) {
            result.add(matchesOldestFirst.get(i).toResponse(deltas[i]));
        }
        return result;
    }

    private int findInterval(List<SnapshotPoint> points, long gameEndTimestamp) {
        Instant matchEnd = Instant.ofEpochMilli(gameEndTimestamp);
        for (int i = 0; i < points.size() - 1; i++) {
            if (!points.get(i).timestamp().isAfter(matchEnd)
                    && points.get(i + 1).timestamp().isAfter(matchEnd)) {
                return i;
            }
        }
        return -1;
    }

    private record SnapshotPoint(Instant timestamp, int lp) {
    }

    private record MatchData(
            String championName,
            int championId,
            boolean win,
            int kills,
            int deaths,
            int assists,
            int cs,
            long gameDuration,
            long gameEndTimestamp,
            String lane,
            String role
    ) {
        PlayerMatchResponse toResponse(Integer lpChange) {
            return new PlayerMatchResponse(championName, championId, win, kills, deaths, assists,
                    cs, gameDuration, gameEndTimestamp, lane, role, lpChange);
        }
    }

}
