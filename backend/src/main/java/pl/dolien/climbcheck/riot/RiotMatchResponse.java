package pl.dolien.climbcheck.riot;

import java.util.List;

public record RiotMatchResponse(
        Info info
) {
    public record Info(
            long gameEndTimestamp,
            long gameDuration,
            int queueId,
            List<Participant> participants
    ) {
    }

    public record Participant(
            String puuid,
            String championName,
            int championId,
            boolean win,
            int kills,
            int deaths,
            int assists,
            int totalMinionsKilled,
            int neutralMinionsKilled,
            String lane,
            String role
    ) {
    }
}
