package pl.dolien.climbcheck.riot;

public record RiotLeagueEntryResponse(
        String queueType,
        String tier,
        String rank,
        int leaguePoints,
        int wins,
        int losses
) {
    /** The queue this entry describes — the only one the leaderboard tracks. */
    public static final String RANKED_SOLO_5x5 = "RANKED_SOLO_5x5";

    public static RiotLeagueEntryResponse unranked() {
        return new RiotLeagueEntryResponse(RANKED_SOLO_5x5, "UNRANKED", "", 0, 0, 0);
    }

    /**
     * Winrate (%) for the current season/split — exactly what the LoL client shows
     * (wins / games played). null when the player has no ranked games yet.
     */
    public Integer winrate() {
        int total = totalGames();
        if (total == 0) {
            return null;
        }
        return (int) Math.round(wins * 100.0 / total);
    }

    /**
     * Number of ranked games played in the current season/split (wins + losses).
     * 0 for players without games (unranked).
     */
    public int totalGames() {
        return wins + losses;
    }
}
