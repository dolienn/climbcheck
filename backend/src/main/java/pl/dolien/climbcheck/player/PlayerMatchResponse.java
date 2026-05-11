package pl.dolien.climbcheck.player;

public record PlayerMatchResponse(
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
        String role,
        Integer lpChange
) {
}
