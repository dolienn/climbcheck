package pl.dolien.climbcheck.player;

import jakarta.persistence.*;
import lombok.Getter;
import pl.dolien.climbcheck.dashboard.Dashboard;
import pl.dolien.climbcheck.riot.RiotRegion;

import static jakarta.persistence.FetchType.LAZY;

@Entity
@Getter
@Table(name = "tracked_player",
        uniqueConstraints = @UniqueConstraint(name = "uk_tracked_player_dashboard_puuid",
                columnNames = {"dashboard_id", "puuid"}))
public class TrackedPlayer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "dashboard_id", nullable = false)
    private Dashboard dashboard;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RiotRegion region;

    @Column(nullable = false)
    private String gameName;

    @Column(nullable = false)
    private String tagLine;

    @Column(nullable = false)
    private String puuid;

    @Column(name = "profile_icon_id")
    private Integer profileIconId;

    protected TrackedPlayer() {} // JPA

    public static TrackedPlayer create(Dashboard dashboard, RiotRegion region,
                                       String gameName, String tagLine, String puuid,
                                       Integer profileIconId) {
        TrackedPlayer player = new TrackedPlayer();
        player.dashboard = dashboard;
        player.region = region;
        player.gameName = gameName;
        player.tagLine = tagLine;
        player.puuid = puuid;
        player.profileIconId = profileIconId;
        return player;
    }
}
