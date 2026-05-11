package pl.dolien.climbcheck.riot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import pl.dolien.climbcheck.exception.PlayerNotFoundException;
import pl.dolien.climbcheck.exception.RiotRateLimitException;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RiotApiClientTest {

    private static final String API_KEY = "test-api-key";

    private MockRestServiceServer server;
    private RiotApiClient riotApiClient;
    private RestClient.Builder builder;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        riotApiClient = new RiotApiClient(API_KEY,
                "https://{routing}.api.riotgames.com",
                builder.build(),
                Duration.ofMinutes(60), Duration.ofMinutes(10));
    }

    @Test
    void getPuuid_shouldUseConfiguredBaseUrl() {
        // riot.base-url overridden (e.g. to a local mock in e2e tests) — the request
        // goes to that address, with the routing substituted in {routing}
        RiotApiClient mockClient = new RiotApiClient(API_KEY,
                "http://localhost:9099/{routing}",
                builder.build(),
                Duration.ofMinutes(60), Duration.ofMinutes(10));
        server.expect(requestTo("http://localhost:9099/europe/riot/account/v1/accounts/by-riot-id/Test/EUW"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Riot-Token", API_KEY))
                .andRespond(withSuccess("""
                        {"puuid":"puuid-mock","gameName":"Test","tagLine":"EUW"}
                        """, MediaType.APPLICATION_JSON));

        String puuid = mockClient.getPuuid(RiotRegion.EUW, "Test", "EUW");

        assertThat(puuid).isEqualTo("puuid-mock");
        server.verify();
    }

    @Test
    void getPuuid_shouldReturnPuuidFromRiotApi() {
        server.expect(requestTo("https://europe.api.riotgames.com/riot/account/v1/accounts/by-riot-id/Test/EUW"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Riot-Token", API_KEY))
                .andRespond(withSuccess("""
                        {"puuid":"puuid-123","gameName":"Test","tagLine":"EUW"}
                        """, MediaType.APPLICATION_JSON));

        String puuid = riotApiClient.getPuuid(RiotRegion.EUW, "Test", "EUW");

        assertThat(puuid).isEqualTo("puuid-123");
        server.verify();
    }

    @Test
    void getPuuid_shouldThrowRateLimitExceptionWhenRateLimited() {
        server.expect(requestTo("https://europe.api.riotgames.com/riot/account/v1/accounts/by-riot-id/Test/EUW"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Riot-Token", API_KEY))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "30")
                        .header("X-Method-Rate-Limit", "euw1:100:120")
                        .header("X-Method-Rate-Limit-Count", "euw1:100:120"));

        assertThatThrownBy(() -> riotApiClient.getPuuid(RiotRegion.EUW, "Test", "EUW"))
                .isInstanceOf(RiotRateLimitException.class)
                .satisfies(ex -> {
                    RiotRateLimitException rateLimit = (RiotRateLimitException) ex;
                    assertThat(rateLimit.getRetryAfter()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(rateLimit.getRateLimitHeaders())
                            .containsEntry("Retry-After", "30")
                            .containsEntry("X-Method-Rate-Limit", "euw1:100:120");
                });
        server.verify();
    }

    @Test
    void getLeagueEntry_shouldThrowRateLimitExceptionWhenRateLimited() {
        server.expect(requestTo("https://euw1.api.riotgames.com/lol/league/v4/entries/by-puuid/puuid-123"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Riot-Token", API_KEY))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "10"));

        assertThatThrownBy(() -> riotApiClient.getLeagueEntry(RiotRegion.EUW, "puuid-123"))
                .isInstanceOf(RiotRateLimitException.class)
                .satisfies(ex -> {
                    RiotRateLimitException rateLimit = (RiotRateLimitException) ex;
                    assertThat(rateLimit.getRetryAfter()).isEqualTo(Duration.ofSeconds(10));
                });
        server.verify();
    }

    @Test
    void getProfileIconId_shouldReturnIconIdFromRiotApi() {
        server.expect(requestTo("https://euw1.api.riotgames.com/lol/summoner/v4/summoners/by-puuid/puuid-123"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Riot-Token", API_KEY))
                .andRespond(withSuccess("""
                        {"id":"summoner-1","accountId":"acc-1","puuid":"puuid-123","name":"Test",
                         "profileIconId":7,"revisionDate":1,"summonerLevel":50}
                        """, MediaType.APPLICATION_JSON));

        int iconId = riotApiClient.getProfileIconId(RiotRegion.EUW, "puuid-123");

        assertThat(iconId).isEqualTo(7);
        server.verify();
    }

    @Test
    void getProfileIconId_shouldThrowRateLimitExceptionWhenRateLimited() {
        server.expect(requestTo("https://euw1.api.riotgames.com/lol/summoner/v4/summoners/by-puuid/puuid-123"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Riot-Token", API_KEY))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "5"));

        assertThatThrownBy(() -> riotApiClient.getProfileIconId(RiotRegion.EUW, "puuid-123"))
                .isInstanceOf(RiotRateLimitException.class)
                .satisfies(ex -> {
                    RiotRateLimitException rateLimit = (RiotRateLimitException) ex;
                    assertThat(rateLimit.getRetryAfter()).isEqualTo(Duration.ofSeconds(5));
                });
        server.verify();
    }

    @Test
    void getProfileIconId_shouldThrowPlayerNotFoundExceptionWhenSummonerNotFound() {
        server.expect(requestTo("https://euw1.api.riotgames.com/lol/summoner/v4/summoners/by-puuid/puuid-123"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Riot-Token", API_KEY))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> riotApiClient.getProfileIconId(RiotRegion.EUW, "puuid-123"))
                .isInstanceOf(PlayerNotFoundException.class)
                .hasMessageContaining("puuid-123");
        server.verify();
    }

    @Test
    void getRecentMatchIds_shouldReturnMatchIds() {
        server.expect(requestTo("https://europe.api.riotgames.com/lol/match/v5/matches/by-puuid/puuid-123/ids?start=0&count=3"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Riot-Token", API_KEY))
                .andRespond(withSuccess("[\"EUW1_1\",\"EUW1_2\"]", MediaType.APPLICATION_JSON));

        List<String> ids = riotApiClient.getRecentMatchIds(RiotRegion.EUW, "puuid-123", 3);

        assertThat(ids).containsExactly("EUW1_1", "EUW1_2");
        server.verify();
    }

    @Test
    void getRecentMatchIds_shouldServeFromCacheOnSecondCall() {
        server.expect(requestTo("https://europe.api.riotgames.com/lol/match/v5/matches/by-puuid/puuid-123/ids?start=0&count=3"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Riot-Token", API_KEY))
                .andRespond(withSuccess("[\"EUW1_1\",\"EUW1_2\"]", MediaType.APPLICATION_JSON));

        List<String> first = riotApiClient.getRecentMatchIds(RiotRegion.EUW, "puuid-123", 3);
        List<String> second = riotApiClient.getRecentMatchIds(RiotRegion.EUW, "puuid-123", 3);

        assertThat(second).isSameAs(first);
        server.verify(); // one expected request = the second was served from cache
    }

    @Test
    void getRecentMatchIds_shouldCachePerPuuidAndCount() {
        server.expect(requestTo("https://europe.api.riotgames.com/lol/match/v5/matches/by-puuid/puuid-123/ids?start=0&count=3"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Riot-Token", API_KEY))
                .andRespond(withSuccess("[\"EUW1_1\"]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://europe.api.riotgames.com/lol/match/v5/matches/by-puuid/puuid-456/ids?start=0&count=3"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Riot-Token", API_KEY))
                .andRespond(withSuccess("[\"EUW1_2\"]", MediaType.APPLICATION_JSON));

        riotApiClient.getRecentMatchIds(RiotRegion.EUW, "puuid-123", 3);
        riotApiClient.getRecentMatchIds(RiotRegion.EUW, "puuid-456", 3);

        server.verify();
    }

    @Test
    void getMatch_shouldMapParticipantData() {
        server.expect(requestTo("https://europe.api.riotgames.com/lol/match/v5/matches/EUW1_1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Riot-Token", API_KEY))
                .andRespond(withSuccess("""
                        {
                          "metadata": {"matchId": "EUW1_1"},
                          "info": {
                            "queueId": 420,
                            "gameDuration": 1983,
                            "gameEndTimestamp": 1700000000000,
                            "participants": [
                              {"puuid": "puuid-123", "championName": "Yasuo", "championId": 157,
                               "win": false, "kills": 2, "deaths": 12, "assists": 4,
                               "totalMinionsKilled": 201, "neutralMinionsKilled": 37,
                               "lane": "MIDDLE", "role": "SOLO"}
                            ]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        RiotMatchResponse match = riotApiClient.getMatch(RiotRegion.EUW, "EUW1_1");

        assertThat(match.info().queueId()).isEqualTo(420);
        assertThat(match.info().gameDuration()).isEqualTo(1983);
        assertThat(match.info().participants()).hasSize(1);
        RiotMatchResponse.Participant participant = match.info().participants().get(0);
        assertThat(participant.puuid()).isEqualTo("puuid-123");
        assertThat(participant.championName()).isEqualTo("Yasuo");
        assertThat(participant.championId()).isEqualTo(157);
        assertThat(participant.kills()).isEqualTo(2);
        assertThat(participant.totalMinionsKilled()).isEqualTo(201);
        assertThat(participant.neutralMinionsKilled()).isEqualTo(37);
        server.verify();
    }

    @Test
    void getMatch_shouldServeFromCacheOnSecondCall() {
        server.expect(requestTo("https://europe.api.riotgames.com/lol/match/v5/matches/EUW1_1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Riot-Token", API_KEY))
                .andRespond(withSuccess("""
                        {
                          "metadata": {"matchId": "EUW1_1"},
                          "info": {
                            "queueId": 420,
                            "gameDuration": 1983,
                            "gameEndTimestamp": 1700000000000,
                            "participants": [
                              {"puuid": "puuid-123", "championName": "Yasuo", "championId": 157,
                               "win": false, "kills": 2, "deaths": 12, "assists": 4,
                               "totalMinionsKilled": 201, "neutralMinionsKilled": 0,
                               "lane": "MIDDLE", "role": "SOLO"}
                            ]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        RiotMatchResponse first = riotApiClient.getMatch(RiotRegion.EUW, "EUW1_1");
        RiotMatchResponse second = riotApiClient.getMatch(RiotRegion.EUW, "EUW1_1");

        assertThat(second).isSameAs(first);
        server.verify(); // one expected request = the second was served from cache
    }

    @Test
    void getMatch_shouldNotCacheRateLimitFailure() {
        server.expect(requestTo("https://europe.api.riotgames.com/lol/match/v5/matches/EUW1_1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Riot-Token", API_KEY))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "5"));
        server.expect(requestTo("https://europe.api.riotgames.com/lol/match/v5/matches/EUW1_1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Riot-Token", API_KEY))
                .andRespond(withSuccess("""
                        {"metadata": {"matchId": "EUW1_1"},
                         "info": {"queueId": 420, "gameDuration": 1983, "gameEndTimestamp": 1700000000000,
                                  "participants": []}}
                        """, MediaType.APPLICATION_JSON));

        // 429 is NOT cached: the first call throws, the second hits the server again and succeeds
        assertThatThrownBy(() -> riotApiClient.getMatch(RiotRegion.EUW, "EUW1_1"))
                .isInstanceOf(RiotRateLimitException.class)
                .satisfies(ex -> assertThat(((RiotRateLimitException) ex).getRetryAfter())
                        .isEqualTo(Duration.ofSeconds(5)));

        RiotMatchResponse second = riotApiClient.getMatch(RiotRegion.EUW, "EUW1_1");

        assertThat(second.info().queueId()).isEqualTo(420);
        server.verify(); // two requests = the failure was not cached
    }

    @Test
    void getMatch_shouldCachePerMatchId() {
        server.expect(requestTo("https://europe.api.riotgames.com/lol/match/v5/matches/EUW1_1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Riot-Token", API_KEY))
                .andRespond(withSuccess("""
                        {"metadata": {"matchId": "EUW1_1"},
                         "info": {"queueId": 420, "gameDuration": 1983, "gameEndTimestamp": 1700000000000,
                                  "participants": []}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://europe.api.riotgames.com/lol/match/v5/matches/EUW1_2"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Riot-Token", API_KEY))
                .andRespond(withSuccess("""
                        {"metadata": {"matchId": "EUW1_2"},
                         "info": {"queueId": 420, "gameDuration": 1500, "gameEndTimestamp": 1700000000000,
                                  "participants": []}}
                        """, MediaType.APPLICATION_JSON));

        riotApiClient.getMatch(RiotRegion.EUW, "EUW1_1");
        riotApiClient.getMatch(RiotRegion.EUW, "EUW1_2");

        server.verify(); // different matchId = different cache keys, two requests
    }

    @Test
    void getPuuid_shouldThrowPlayerNotFoundExceptionWhenAccountNotFound() {
        server.expect(requestTo("https://europe.api.riotgames.com/riot/account/v1/accounts/by-riot-id/Ghost/EUW"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Riot-Token", API_KEY))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> riotApiClient.getPuuid(RiotRegion.EUW, "Ghost", "EUW"))
                .isInstanceOf(PlayerNotFoundException.class)
                .hasMessageContaining("Ghost#EUW");
        server.verify();
    }

    @Test
    void getLeagueEntry_shouldReturnUnrankedWhenNoEntries() {
        server.expect(requestTo("https://euw1.api.riotgames.com/lol/league/v4/entries/by-puuid/puuid-123"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Riot-Token", API_KEY))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        RiotLeagueEntryResponse entry = riotApiClient.getLeagueEntry(RiotRegion.EUW, "puuid-123");

        assertThat(entry.tier()).isEqualTo("UNRANKED");
        assertThat(entry.leaguePoints()).isZero();
        server.verify();
    }

    @Test
    void getLeagueEntry_shouldReturnUnrankedWhenOnlyFlexQueueEntry() {
        server.expect(requestTo("https://euw1.api.riotgames.com/lol/league/v4/entries/by-puuid/puuid-123"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Riot-Token", API_KEY))
                .andRespond(withSuccess("""
                        [
                          {"queueType":"RANKED_FLEX_SR","tier":"GOLD","rank":"II","leaguePoints":30,"wins":10,"losses":5}
                        ]
                        """, MediaType.APPLICATION_JSON));

        RiotLeagueEntryResponse entry = riotApiClient.getLeagueEntry(RiotRegion.EUW, "puuid-123");

        assertThat(entry.tier()).isEqualTo("UNRANKED");
        assertThat(entry.leaguePoints()).isZero();
        server.verify();
    }

    @Test
    void getLeagueEntry_shouldPickRankedSoloQueueEntry() {
        server.expect(requestTo("https://euw1.api.riotgames.com/lol/league/v4/entries/by-puuid/puuid-123"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Riot-Token", API_KEY))
                .andRespond(withSuccess("""
                        [
                          {"queueType":"RANKED_FLEX_SR","tier":"GOLD","rank":"II","leaguePoints":30,"wins":10,"losses":5},
                          {"queueType":"RANKED_SOLO_5x5","tier":"PLATINUM","rank":"I","leaguePoints":75,"wins":20,"losses":15}
                        ]
                        """, MediaType.APPLICATION_JSON));

        RiotLeagueEntryResponse entry = riotApiClient.getLeagueEntry(RiotRegion.EUW, "puuid-123");

        assertThat(entry.tier()).isEqualTo("PLATINUM");
        assertThat(entry.rank()).isEqualTo("I");
        assertThat(entry.leaguePoints()).isEqualTo(75);
        server.verify();
    }
}
