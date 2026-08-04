package pl.dolien.climbcheck.player;

import java.util.List;

/**
 * Response of /players/{id}/matches: visible matches (max 10, newest) + the player's
 * current streak (walk until it breaks) and the top 3 most-played champions with
 * winrate. All three are computed from the same ranked sample (the champion
 * aggregation scans up to 50 games), so there are no extra Riot API calls.
 * streak &gt; 0 = win streak, &lt; 0 = loss streak, null = no ranked games.
 */
public record PlayerMatchesResponse(
        List<PlayerMatchResponse> matches,
        Integer streak,
        List<ChampionStatsResponse> topChampions
) {
}
