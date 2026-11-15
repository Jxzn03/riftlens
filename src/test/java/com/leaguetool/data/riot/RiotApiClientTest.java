package com.leaguetool.data.riot;

import com.fasterxml.jackson.databind.JsonNode;
import com.leaguetool.data.http.HttpResult;
import com.leaguetool.model.Tier;
import com.leaguetool.testutil.Fixtures;
import org.junit.jupiter.api.Test;

import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.leaguetool.data.riot.FakeTimeSource.SECOND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiotApiClientTest {

    private static final String KEY = "RGAPI-test-0000";
    private static final String EUW = "https://euw1.api.riotgames.com";
    private static final String EUROPE = "https://europe.api.riotgames.com";

    private final FakeTimeSource time = new FakeTimeSource();
    private final RateLimiter limiter = RateLimiter.riotDevKey(time);
    private final ScriptedFetcher http = new ScriptedFetcher();

    private RiotApiClient client() {
        return new RiotApiClient(http, () -> Optional.of(KEY), Platform.EUW1, limiter, time);
    }

    @Test
    void routesPlatformAndRegionalEndpoints() throws Exception {
        http.respond(EUW + "/lol/league/v4/entries/RANKED_SOLO_5x5/GOLD/II?page=2", Fixtures.read("riot/league-entries.json"))
                .respond(EUW + "/lol/league/v4/challengerleagues/by-queue/RANKED_SOLO_5x5",
                        Fixtures.read("riot/challenger-league.json"))
                .respond(EUW + "/lol/league/v4/grandmasterleagues/by-queue/RANKED_SOLO_5x5",
                        "{\"tier\":\"GRANDMASTER\",\"queue\":\"RANKED_SOLO_5x5\",\"entries\":[]}")
                .respond(EUW + "/lol/league/v4/masterleagues/by-queue/RANKED_SOLO_5x5",
                        "{\"tier\":\"MASTER\",\"queue\":\"RANKED_SOLO_5x5\",\"entries\":[]}")
                .respond(EUW + "/lol/summoner/v4/summoners/test-summoner-03", "{\"puuid\":\"p-3\",\"summonerLevel\":500}")
                .respond(EUROPE + "/riot/account/v1/accounts/by-riot-id/Tester/EUW",
                        "{\"puuid\":\"p-acc\",\"gameName\":\"Tester\",\"tagLine\":\"EUW\"}")
                .respond(EUROPE + "/lol/match/v5/matches/by-puuid/p-1/ids?queue=420&start=0&count=20",
                        "[\"EUW1_1\",\"EUW1_2\"]")
                .respond(EUROPE + "/lol/match/v5/matches/by-puuid/p-1/ids?queue=420&start=20&count=5&startTime=1700000000",
                        "[]")
                .respond(EUROPE + "/lol/match/v5/matches/EUW1_7000000001", Fixtures.read("riot/match-ok.json"))
                .respond(EUROPE + "/lol/match/v5/matches/EUW1_7000000001/timeline",
                        "{\"metadata\":{\"matchId\":\"EUW1_7000000001\"},\"info\":{\"frameInterval\":60000,\"frames\":[]}}")
                .respond(EUW + "/lol/status/v4/platform-data", Fixtures.read("riot/platform-status.json"));

        RiotApiClient c = client();
        assertEquals(Platform.EUW1, c.platform());
        assertEquals(3, c.leagueEntries(RiotApi.RANKED_SOLO_QUEUE, Tier.GOLD, "II", 2).size());
        assertEquals(3, c.apexLeague(RiotApi.RANKED_SOLO_QUEUE, Tier.CHALLENGER).size());
        assertTrue(c.apexLeague(RiotApi.RANKED_SOLO_QUEUE, Tier.GRANDMASTER).isEmpty());
        assertTrue(c.apexLeague(RiotApi.RANKED_SOLO_QUEUE, Tier.MASTER).isEmpty());
        assertEquals("p-3", c.puuidBySummonerId("test-summoner-03"));
        assertEquals(new RiotAccount("p-acc", "Tester", "EUW"), c.accountByRiotId("Tester", "EUW"));
        assertEquals(List.of("EUW1_1", "EUW1_2"), c.matchIdsByPuuid("p-1", 420, 0, 20, null));
        assertTrue(c.matchIdsByPuuid("p-1", 420, 20, 5, 1_700_000_000L).isEmpty());
        JsonNode match = c.match("EUW1_7000000001");
        assertEquals("16.22.712.5432", match.path("info").path("gameVersion").asText());
        assertEquals(60000, c.timeline("EUW1_7000000001").path("info").path("frameInterval").asInt());
        assertEquals("EUW1", c.platformStatus().path("id").asText());

        assertEquals(11, http.requests().size());
        for (ScriptedFetcher.Request r : http.requests()) {
            assertEquals(KEY, r.headers().get("X-Riot-Token"), r.url());
            assertFalse(r.url().contains(KEY));
        }
        assertTrue(http.urls().stream().noneMatch(u -> u.contains("api_key")));
    }

    @Test
    void otherPlatformsUseTheirRegion() throws Exception {
        http.respond("https://kr.api.riotgames.com/lol/status/v4/platform-data", "{}")
                .respond("https://asia.api.riotgames.com/lol/match/v5/matches/KR_1", "{}")
                .respond("https://sea.api.riotgames.com/lol/match/v5/matches/OC1_1", "{}")
                .respond("https://americas.api.riotgames.com/riot/account/v1/accounts/by-riot-id/A/OCE",
                        "{\"puuid\":\"x\"}");
        RiotApiClient kr = new RiotApiClient(http, () -> Optional.of(KEY), Platform.KR, limiter, time);
        kr.platformStatus();
        kr.match("KR_1");
        RiotApiClient oce = new RiotApiClient(http, () -> Optional.of(KEY), Platform.OC1, limiter, time);
        oce.match("OC1_1");
        assertEquals(new RiotAccount("x", "A", "OCE"), oce.accountByRiotId("A", "OCE"));
        assertEquals(4, http.requests().size());
    }

    @Test
    void encodesPathSegments() throws Exception {
        String url = EUROPE + "/riot/account/v1/accounts/by-riot-id/Hide%20on%20bush/KR%201";
        String unicode = EUROPE + "/riot/account/v1/accounts/by-riot-id/Zo%C3%AB%20%E9%BE%99/%C3%A9%2F%23";
        http.respond(url, "{\"puuid\":\"p\",\"gameName\":\"Hide on bush\",\"tagLine\":\"KR 1\"}")
                .respond(unicode, "{\"puuid\":\"q\",\"gameName\":\"Zoë 龙\",\"tagLine\":\"é/#\"}");
        assertEquals("Hide on bush", client().accountByRiotId("Hide on bush", "KR 1").gameName());
        assertEquals("q", client().accountByRiotId("Zoë 龙", "é/#").puuid());
        assertEquals(List.of(url, unicode), http.urls());
        assertEquals("a%2Bb%20c", RiotApiClient.segment("a+b c"));
    }

    @Test
    void parsesLeagueEntriesWithAndWithoutPuuid() throws Exception {
        http.respond(EUW + "/lol/league/v4/entries/RANKED_SOLO_5x5/GOLD/II?page=1", Fixtures.read("riot/league-entries.json"));
        List<LeagueEntry> entries = client().leagueEntries(RiotApi.RANKED_SOLO_QUEUE, Tier.GOLD, "II", 1);
        assertEquals(new LeagueEntry("test-puuid-g01", "test-summoner-g01", "RANKED_SOLO_5x5", Tier.GOLD, "II", 41, 31, 29),
                entries.get(0));
        assertEquals("test-puuid-g02", entries.get(1).puuid());
        assertNull(entries.get(1).summonerId());
        assertNull(entries.get(2).puuid());
        assertEquals("test-summoner-g03", entries.get(2).summonerId());
    }

    @Test
    void parsesApexLeagueSortedByLeaguePoints() throws Exception {
        http.respond(EUW + "/lol/league/v4/challengerleagues/by-queue/RANKED_SOLO_5x5",
                Fixtures.read("riot/challenger-league.json"));
        List<LeagueEntry> entries = client().apexLeague(RiotApi.RANKED_SOLO_QUEUE, Tier.CHALLENGER);
        assertEquals(List.of(1650, 1200, 900), entries.stream().map(LeagueEntry::leaguePoints).toList());
        LeagueEntry top = entries.get(0);
        assertEquals(new LeagueEntry("test-puuid-c02", "test-summoner-02", "RANKED_SOLO_5x5", Tier.CHALLENGER, "I",
                1650, 202, 152), top);
        assertNull(entries.get(2).puuid());
        assertEquals("test-summoner-03", entries.get(2).summonerId());
    }

    @Test
    void retriesAfter429WithRetryAfter() throws Exception {
        String url = EUW + "/lol/status/v4/platform-data";
        http.respond(url, ScriptedFetcher.status(429, "Retry-After", "3"), okStatus());
        JsonNode status = client().platformStatus();
        assertEquals("EUW1", status.path("id").asText());
        assertEquals(2, http.requests().size());
        assertEquals(List.of(3 * SECOND), time.sleeps());
    }

    @Test
    void retryAfterDefaultsToOneSecond() throws Exception {
        String url = EUW + "/lol/status/v4/platform-data";
        http.respond(url, ScriptedFetcher.status(429), ScriptedFetcher.status(429, "retry-after", "kaputt"),
                okStatus());
        client().platformStatus();
        assertEquals(List.of(SECOND, SECOND), time.sleeps());
    }

    @Test
    void givesUpAfterFiveRateLimitRetries() {
        String url = EUW + "/lol/status/v4/platform-data";
        http.respond(url, ScriptedFetcher.status(429, "Retry-After", "2"));
        RiotApiException e = assertThrows(RiotApiException.class, () -> client().platformStatus());
        assertEquals(429, e.status());
        assertEquals(6, http.requests().size());
        assertEquals(10 * SECOND, time.totalSlept());
    }

    @Test
    void retriesServerErrorsWithBackoff() throws Exception {
        String ok = EUW + "/lol/status/v4/platform-data";
        http.respond(ok, ScriptedFetcher.status(503), ScriptedFetcher.status(502), okStatus());
        client().platformStatus();
        assertEquals(List.of(SECOND, 2 * SECOND), time.sleeps());

        String failing = EUROPE + "/lol/match/v5/matches/EUW1_9";
        http.respond(failing, ScriptedFetcher.status(500));
        RiotApiException e = assertThrows(RiotApiException.class, () -> client().match("EUW1_9"));
        assertEquals(500, e.status());
        assertEquals(4, http.urls().stream().filter(failing::equals).count());
        assertEquals(List.of(SECOND, 2 * SECOND, SECOND, 2 * SECOND, 4 * SECOND), time.sleeps());
    }

    @Test
    void clientErrorsAreReportedInGerman() {
        String url = EUW + "/lol/status/v4/platform-data";
        http.respond(url, ScriptedFetcher.status(403));
        RiotApiException forbidden = assertThrows(RiotApiException.class, () -> client().platformStatus());
        assertTrue(forbidden.isAuthProblem());
        assertEquals(403, forbidden.status());
        assertTrue(forbidden.getMessage().contains("Key ungültig oder abgelaufen (Dev-Keys laufen nach 24 h ab)"),
                forbidden.getMessage());
        assertFalse(forbidden.getMessage().contains(KEY));
        assertEquals(1, http.requests().size());

        RiotApiException notFound = assertThrows(RiotApiException.class, () -> client().match("EUW1_404"));
        assertTrue(notFound.isNotFound());
        assertTrue(notFound.getMessage().contains("nicht gefunden"), notFound.getMessage());
        assertTrue(notFound.getMessage().contains("/lol/match/v5/matches/EUW1_404"), notFound.getMessage());

        http.respond(EUW + "/lol/summoner/v4/summoners/x", ScriptedFetcher.status(400));
        assertEquals(400, assertThrows(RiotApiException.class, () -> client().puuidBySummonerId("x")).status());
    }

    @Test
    void missingKeyFailsWithoutHttpCall() {
        for (Optional<String> key : List.of(Optional.<String>empty(), Optional.of("  "))) {
            RiotApiClient c = new RiotApiClient(http, () -> key, Platform.EUW1, limiter, time);
            RiotApiException e = assertThrows(RiotApiException.class, c::platformStatus);
            assertEquals(401, e.status());
            assertTrue(e.isAuthProblem());
            assertTrue(e.getMessage().startsWith("Kein Riot-API-Key konfiguriert"), e.getMessage());
        }
        assertTrue(http.requests().isEmpty());
    }

    @Test
    void everyRequestPassesTheRateLimiter() throws Exception {
        RateLimiter tight = new RateLimiter(time, List.of(new RateLimiter.Window(1, Duration.ofSeconds(1))));
        http.respond(EUW + "/lol/status/v4/platform-data", "{}");
        RiotApiClient c = new RiotApiClient(http, () -> Optional.of(KEY), Platform.EUW1, tight, time);
        c.platformStatus();
        c.platformStatus();
        c.platformStatus();
        assertEquals(List.of(SECOND, SECOND), time.sleeps());
    }

    @Test
    void validatesArguments() {
        RiotApiClient c = client();
        assertThrows(IllegalArgumentException.class, () -> c.leagueEntries(RiotApi.RANKED_SOLO_QUEUE, Tier.MASTER, "I", 1));
        assertThrows(IllegalArgumentException.class, () -> c.leagueEntries(RiotApi.RANKED_SOLO_QUEUE, Tier.GOLD, "V", 1));
        assertThrows(IllegalArgumentException.class, () -> c.leagueEntries(RiotApi.RANKED_SOLO_QUEUE, Tier.GOLD, "I", 0));
        assertThrows(IllegalArgumentException.class, () -> c.apexLeague(RiotApi.RANKED_SOLO_QUEUE, Tier.GOLD));
        assertThrows(IllegalArgumentException.class, () -> c.matchIdsByPuuid("p", 420, 0, 0, null));
        assertThrows(IllegalArgumentException.class, () -> c.matchIdsByPuuid("p", 420, 0, 101, null));
        assertThrows(IllegalArgumentException.class, () -> c.match(""));
        assertTrue(http.requests().isEmpty());
    }

    @Test
    void interruptionBecomesInterruptedIoException() {
        RateLimiter.TimeSource interrupting = new RateLimiter.TimeSource() {
            @Override
            public long nanoTime() {
                return 0;
            }

            @Override
            public void sleepNanos(long nanos) throws InterruptedException {
                throw new InterruptedException("Test");
            }
        };
        RateLimiter paused = new RateLimiter(interrupting, List.of(new RateLimiter.Window(5, Duration.ofSeconds(1))));
        paused.pauseUntil(SECOND);
        RiotApiClient c = new RiotApiClient(http, () -> Optional.of(KEY), Platform.EUW1, paused, interrupting);
        try {
            assertThrows(InterruptedIOException.class, c::platformStatus);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertTrue(http.requests().isEmpty());
    }

    /** Erfolgreiche Status-Antwort. */
    private static HttpResult okStatus() {
        return HttpResult.ok(Fixtures.read("riot/platform-status.json"));
    }
}
