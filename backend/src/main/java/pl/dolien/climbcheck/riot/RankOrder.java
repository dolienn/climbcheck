package pl.dolien.climbcheck.riot;

import pl.dolien.climbcheck.player.PlayerResponse;

import java.util.Comparator;
import java.util.Map;

public final class RankOrder {

    private static final Map<String, Integer> TIER_WEIGHT = Map.of(
            "IRON", 1, "BRONZE", 2, "SILVER", 3, "GOLD", 4, "PLATINUM", 5,
            "EMERALD", 6, "DIAMOND", 7, "MASTER", 8, "GRANDMASTER", 9, "CHALLENGER", 10);

    private static final Map<String, Integer> DIVISION_WEIGHT = Map.of(
            "IV", 1, "III", 2, "II", 3, "I", 4);

    private RankOrder() {}

    /**
     * Sorts players by rank: tier (highest first), then division (I before IV),
     * then LP. UNRANKED players (tier weight 0) end up last.
     */
    public static Comparator<PlayerResponse> byRank() {
        return Comparator
                .comparingInt((PlayerResponse p) -> TIER_WEIGHT.getOrDefault(p.tier(), 0))
                .thenComparingInt(RankOrder::divisionWeight)
                .thenComparingInt(PlayerResponse::leaguePoints)
                .reversed();
    }

    private static int divisionWeight(PlayerResponse player) {
        return DIVISION_WEIGHT.getOrDefault(player.rank(), 0);
    }
}
