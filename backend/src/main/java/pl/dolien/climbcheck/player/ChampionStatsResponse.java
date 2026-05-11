package pl.dolien.climbcheck.player;

/**
 * Champion stats from recent ranked games: games played, wins and winrate (%).
 * Used in the player modal (top 3 most-played).
 */
public record ChampionStatsResponse(
        String championName,
        int championId,
        int games,
        int wins,
        int winrate
) {
}
