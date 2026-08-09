package pl.dolien.climbcheck.player;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import pl.dolien.climbcheck.riot.RiotRegion;

public record AddPlayerRequest(
        @NotNull(message = "Region is required")
        RiotRegion region,
        @NotBlank(message = "GameName must not be blank")
        // \p{L} = Unicode letters — Riot IDs allow accented characters (e.g. OłJeleń), not just ASCII
        @Pattern(regexp = "^[\\p{L}0-9 _.-]{3,16}$",
                message = "Invalid gameName: 3-16 characters allowed (letters, digits, space, _ . -)")
        String gameName,
        @NotBlank(message = "TagLine must not be blank")
        @Pattern(regexp = "^[\\p{L}0-9]{3,5}$",
                message = "Invalid tagLine: 3-5 letters or digits allowed")
        String tagLine
) {
}
