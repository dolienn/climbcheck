package pl.dolien.climbcheck.riot;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import pl.dolien.climbcheck.exception.PlayerNotFoundException;
import pl.dolien.climbcheck.exception.RiotRateLimitException;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class RiotApiClient {

    /**
     * Default base URL with a {routing} placeholder (regional: europe / platform: euw1, eun1).
     * Overridable via riot.base-url (e.g. RIOT_BASE_URL in e2e points at a local mock,
     * so tests don't depend on the real API or a dev key).
     */
    private static final String DEFAULT_BASE_URL = "https://{routing}.api.riotgames.com";

    private static final String ACCOUNT_V1_PATH =
            "/riot/account/v1/accounts/by-riot-id/{gameName}/{tagLine}";
    private static final String LEAGUE_V4_PATH =
            "/lol/league/v4/entries/by-puuid/{puuid}";
    private static final String SUMMONER_V4_PATH =
            "/lol/summoner/v4/summoners/by-puuid/{puuid}";
    private static final String MATCH_V5_IDS_PATH =
            "/lol/match/v5/matches/by-puuid/{puuid}/ids?start=0&count={count}";
    private static final String MATCH_V5_PATH =
            "/lol/match/v5/matches/{matchId}";
    private static final String RANKED_SOLO_5x5 = "RANKED_SOLO_5x5";
    private static final List<String> RATE_LIMIT_HEADERS = List.of(
            "X-App-Rate-Limit", "X-App-Rate-Limit-Count",
            "X-Method-Rate-Limit", "X-Method-Rate-Limit-Count",
            "Retry-After");

    private final RestClient restClient;
    private final String apiKey;
    private final String baseUrl;

    /**
     * Cache for match-v5 responses. Played matches are immutable, so they are kept longer
     * (match-ttl); the recent-match list grows with new games, so it gets a shorter TTL
     * (match-ids-ttl). Protects the dev-key rate limit — expanding a match row reads from
     * memory instead of hitting Riot on every refresh.
     */
    private final Cache<String, RiotMatchResponse> matchCache;
    private final Cache<String, List<String>> matchIdsCache;

    /**
     * Short-lived cache of league-v4 entries. Every dashboard GET does one league-v4 call
     * per player; with the frontend refreshing every 5 minutes that can pile up on a dev
     * key. A 60s TTL keeps the ranking "live enough" while cutting repeat calls during a
     * refresh burst.
     */
    private final Cache<String, RiotLeagueEntryResponse> leagueCache;

    public RiotApiClient(@Value("${riot.api-key}") String apiKey,
                         @Value("${riot.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
                         RestClient restClient,
                         @Value("${app.match-cache.match-ttl:60m}") Duration matchTtl,
                         @Value("${app.match-cache.match-ids-ttl:10m}") Duration matchIdsTtl,
                         @Value("${app.league-cache.league-ttl:60s}") Duration leagueTtl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.restClient = restClient;
        this.matchCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(matchTtl)
                .build();
        this.matchIdsCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(matchIdsTtl)
                .build();
        this.leagueCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(leagueTtl)
                .build();
    }

    /** Full URL: base (with {routing} substituted) + endpoint path. */
    private String url(String routing, String path) {
        return baseUrl.replace("{routing}", routing) + path;
    }

    public String getPuuid(RiotRegion region, String gameName, String tagLine) {
        return restClient.get()
                .uri(url(region.getRegionalRouting(), ACCOUNT_V1_PATH), gameName, tagLine)
                .header("X-Riot-Token", apiKey)
                .retrieve()
                .onStatus(status -> status.value() == 404, (status, headers) -> {
                    throw new PlayerNotFoundException(
                            "Player not found: " + gameName + "#" + tagLine);
                })
                .onStatus(status -> status.value() == 429, this::throwRateLimitException)
                .body(RiotAccountResponse.class)
                .puuid();
    }

    public RiotLeagueEntryResponse getLeagueEntry(RiotRegion region, String puuid) {
        String key = region.getPlatformRouting() + ":" + puuid;
        return leagueCache.get(key, ignored -> fetchLeagueEntry(region, puuid));
    }

    private RiotLeagueEntryResponse fetchLeagueEntry(RiotRegion region, String puuid) {
        RiotLeagueEntryResponse[] entries = restClient.get()
                .uri(url(region.getPlatformRouting(), LEAGUE_V4_PATH), puuid)
                .header("X-Riot-Token", apiKey)
                .retrieve()
                .onStatus(status -> status.value() == 429, this::throwRateLimitException)
                .body(RiotLeagueEntryResponse[].class);

        return Arrays.stream(entries)
                .filter(entry -> RANKED_SOLO_5x5.equals(entry.queueType()))
                .findFirst()
                .orElse(RiotLeagueEntryResponse.unranked());
    }

    public int getProfileIconId(RiotRegion region, String puuid) {
        RiotSummonerResponse summoner = restClient.get()
                .uri(url(region.getPlatformRouting(), SUMMONER_V4_PATH), puuid)
                .header("X-Riot-Token", apiKey)
                .retrieve()
                .onStatus(status -> status.value() == 404, (status, headers) -> {
                    throw new PlayerNotFoundException(
                            "Summoner profile not found for puuid: " + puuid);
                })
                .onStatus(status -> status.value() == 429, this::throwRateLimitException)
                .body(RiotSummonerResponse.class);
        return summoner.profileIconId();
    }

    public List<String> getRecentMatchIds(RiotRegion region, String puuid, int count) {
        String key = region.getRegionalRouting() + ":" + puuid + ":" + count;
        return matchIdsCache.get(key, ignored -> fetchRecentMatchIds(region, puuid, count));
    }

    private List<String> fetchRecentMatchIds(RiotRegion region, String puuid, int count) {
        String[] ids = restClient.get()
                .uri(url(region.getRegionalRouting(), MATCH_V5_IDS_PATH), puuid, count)
                .header("X-Riot-Token", apiKey)
                .retrieve()
                .onStatus(status -> status.value() == 429, this::throwRateLimitException)
                .body(String[].class);
        return List.of(ids);
    }

    public RiotMatchResponse getMatch(RiotRegion region, String matchId) {
        String key = region.getRegionalRouting() + ":" + matchId;
        return matchCache.get(key, ignored -> fetchMatch(region, matchId));
    }

    private RiotMatchResponse fetchMatch(RiotRegion region, String matchId) {
        return restClient.get()
                .uri(url(region.getRegionalRouting(), MATCH_V5_PATH), matchId)
                .header("X-Riot-Token", apiKey)
                .retrieve()
                .onStatus(status -> status.value() == 429, this::throwRateLimitException)
                .body(RiotMatchResponse.class);
    }

    private void throwRateLimitException(HttpRequest request, ClientHttpResponse response) throws IOException {
        throw new RiotRateLimitException(
                "Riot API rate limit exceeded",
                parseRetryAfter(response.getHeaders().getFirst("Retry-After")),
                captureRateLimitHeaders(response.getHeaders()));
    }

    private Duration parseRetryAfter(String retryAfter) {
        if (retryAfter == null || retryAfter.isBlank()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(retryAfter.trim()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Map<String, String> captureRateLimitHeaders(HttpHeaders headers) {
        Map<String, String> captured = new HashMap<>();
        for (String header : RATE_LIMIT_HEADERS) {
            String value = headers.getFirst(header);
            if (value != null) {
                captured.put(header, value);
            }
        }
        return Map.copyOf(captured);
    }
}
