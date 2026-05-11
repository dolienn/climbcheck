package pl.dolien.climbcheck.dashboard;

import jakarta.persistence.*;
import lombok.Getter;
import pl.dolien.climbcheck.player.TrackedPlayer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static jakarta.persistence.CascadeType.ALL;

@Entity
@Getter
public class Dashboard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false)
    private String token;

    /** Secret token for mutations (adding/removing players) — the view is public via token. */
    @Column(name = "admin_token", nullable = false, unique = true, updatable = false)
    private String adminToken;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "dashboard", cascade = ALL, orphanRemoval = true)
    private List<TrackedPlayer> players = new ArrayList<>();

    protected Dashboard() {} // JPA

    public static Dashboard create() {
        Dashboard dashboard = new Dashboard();
        dashboard.token = UUID.randomUUID().toString();
        dashboard.adminToken = UUID.randomUUID().toString();
        dashboard.createdAt = Instant.now();
        return dashboard;
    }
    // getters only — no setters for id/token/adminToken/createdAt
}
