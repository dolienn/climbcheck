package pl.dolien.climbcheck.player;

import org.springframework.stereotype.Component;
import pl.dolien.climbcheck.riot.RiotLeagueEntryResponse;

import java.util.List;

@Component
public class PlayerMapper {

    public PlayerResponse toResponse(TrackedPlayer player, RiotLeagueEntryResponse league,
                                     List<LpSnapshotResponse> lpHistory, Integer winrate, Integer totalGames) {
        return new PlayerResponse(
                player.getId(),
                player.getGameName(),
                player.getTagLine(),
                player.getRegion(),
                player.getProfileIconId(),
                league.tier(),
                league.rank(),
                league.leaguePoints(),
                winrate,
                totalGames,
                league.wins(),
                league.losses(),
                lpHistory
        );
    }
}
