package pl.dolien.climbcheck.player;

import java.util.List;

/**
 * Response of /players/{id}/matches: visible matches (max 3, newest) + the player's
 * current streak computed from a larger match sample (walk until the streak breaks)
 * and the top 3 most-played champions with winrate — both computed from the same
 * ranked sample, so no extra Riot API calls.
 * streak &gt; 0 = win streak, &lt; 0 = loss streak, null = no ranked games.
 */
public record PlayerMatchesResponse(
        List<PlayerMatchResponse> matches,
        Integer streak,
        List<ChampionStatsResponse> topChampions
) {
}
