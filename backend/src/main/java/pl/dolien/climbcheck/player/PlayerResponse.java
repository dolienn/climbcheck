package pl.dolien.climbcheck.player;

import pl.dolien.climbcheck.riot.RiotRegion;

import java.util.List;

public record PlayerResponse(
        Long id,
        String gameName,
        String tagLine,
        RiotRegion region,
        Integer profileIconId,
        String tier,
        String rank,
        int leaguePoints,
        Integer winrate,
        Integer totalGames,
        Integer wins,
        Integer losses,
        List<LpSnapshotResponse> lpHistory
) {
}
