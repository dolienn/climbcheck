package pl.dolien.climbcheck.riot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RiotLeagueEntryResponseTest {

    @Test
    void winrate_shouldComputeFromWinsAndLosses() {
        RiotLeagueEntryResponse league = new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "PLATINUM", "I", 45, 229, 209);

        // 229/438 = 52.28% → 52% — exactly what the LoL client shows
        assertThat(league.winrate()).isEqualTo(52);
    }

    @Test
    void winrate_shouldRoundToNearestInteger() {
        assertThat(new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "GOLD", "IV", 0, 2, 3).winrate()).isEqualTo(40); // 2/5
        assertThat(new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "GOLD", "IV", 0, 1, 3).winrate()).isEqualTo(25); // 1/4
        assertThat(new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "GOLD", "IV", 0, 1, 0).winrate()).isEqualTo(100);
    }

    @Test
    void winrate_shouldBeNullWhenNoGamesPlayed() {
        assertThat(RiotLeagueEntryResponse.unranked().winrate()).isNull();
        assertThat(new RiotLeagueEntryResponse("RANKED_SOLO_5x5", "GOLD", "IV", 0, 0, 0).winrate()).isNull();
    }
}
