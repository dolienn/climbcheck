package pl.dolien.climbcheck.riot;

import lombok.Getter;

@Getter
public enum RiotRegion {
    EUW("europe", "euw1"),
    EUNE("europe", "eun1");

    private final String regionalRouting;
    private final String platformRouting;

    RiotRegion(String regionalRouting, String platformRouting) {
        this.regionalRouting = regionalRouting;
        this.platformRouting = platformRouting;
    }
}
