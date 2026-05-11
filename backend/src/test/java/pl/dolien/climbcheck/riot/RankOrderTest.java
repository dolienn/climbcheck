package pl.dolien.climbcheck.riot;

import org.junit.jupiter.api.Test;
import pl.dolien.climbcheck.player.PlayerResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RankOrderTest {

    private static PlayerResponse player(String tier, String rank, int lp) {
        return new PlayerResponse(1L, "X", "EUW", RiotRegion.EUW, 0, tier, rank, lp, null, null, null, null, List.of());
    }

    @Test
    void shouldOrderByTierThenDivisionThenLp() {
        PlayerResponse silver1 = player("SILVER", "I", 76);
        PlayerResponse emerald4 = player("EMERALD", "IV", 20);
        PlayerResponse diamond1 = player("DIAMOND", "I", 0);
        PlayerResponse diamond4 = player("DIAMOND", "IV", 500);

        List<PlayerResponse> sorted = List.of(silver1, diamond4, emerald4, diamond1).stream()
                .sorted(RankOrder.byRank())
                .toList();

        // Higher tier first; within DIAMOND, division I before IV (despite lower LP).
        assertThat(sorted).containsExactly(diamond1, diamond4, emerald4, silver1);
    }

    @Test
    void shouldPlaceUnrankedLast() {
        PlayerResponse bronze = player("BRONZE", "IV", 10);
        PlayerResponse unranked = player("UNRANKED", "", 0);

        List<PlayerResponse> sorted = List.of(unranked, bronze).stream()
                .sorted(RankOrder.byRank())
                .toList();

        assertThat(sorted).containsExactly(bronze, unranked);
    }
}
