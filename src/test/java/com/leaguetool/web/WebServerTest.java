package com.leaguetool.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leaguetool.config.AppSettings;
import com.leaguetool.data.http.HttpResult;
import com.leaguetool.data.lolalytics.LolalyticsPages;
import com.leaguetool.testutil.FakeHttpFetcher;
import com.leaguetool.testutil.Fixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebServerTest {

    private static final String BASE = "https://dd.test";
    private static final String PATCH = "16.22.1";
    private static final String SECRET_KEY = "RGAPI-secret-test-key";

    @TempDir
    Path home;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json = new ObjectMapper();
    private AppSettings settings;
    private FakeHttpFetcher fake;
    private WebContext ctx;
    private WebServer server;

    @BeforeEach
    void setUp() throws IOException {
        settings = AppSettings.load(home);
        settings.setWikiEnabled(false);
        settings.setLolalyticsEnabled(false);
        settings.setStoredRiotApiKey(SECRET_KEY);
        String data = BASE + "/cdn/" + PATCH + "/data/en_US/";
        fake = new FakeHttpFetcher()
                .respond(BASE + "/api/versions.json", Fixtures.read("ddragon/versions.json"))
                .respond(data + "champion.json", Fixtures.read("ddragon/champion.json"))
                .respond(data + "champion/Ahri.json", Fixtures.read("ddragon/champion-Ahri.json"))
                .respond(data + "item.json", Fixtures.read("ddragon/item.json"))
                .respond(data + "runesReforged.json", Fixtures.read("ddragon/runesReforged.json"))
                .respond(data + "summoner.json", Fixtures.read("ddragon/summoner.json"));
        ctx = new WebContext(settings, fake, BASE);
        server = WebServer.start(ctx, 0);
        settings.save();
        ctx.reload().join();
    }

    @AfterEach
    void tearDown() {
        // Ein noch laufendes Neuladen darf nicht mehr in das Temp-Verzeichnis schreiben, während es gelöscht wird.
        ctx.awaitData();
        server.close();
        ctx.close();
    }

    private HttpResponse<String> get(String path, boolean session) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(server.baseUrl() + path)).GET();
        if (session) {
            request.header("Cookie", server.sessionCookie());
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void requestsWithoutSessionAreRejected() throws Exception {
        assertEquals(401, get("/api/bootstrap", false).statusCode());
        assertEquals(401, get("/api/settings", false).statusCode());
        assertEquals(401, get("/", false).statusCode());
        assertEquals(401, get("/?t=falsches-token", false).statusCode());
    }

    @Test
    void entryUrlSetsStrictHttpOnlyCookieAndRedirects() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(server.entryUrl())).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(302, response.statusCode());
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(cookie.startsWith(server.sessionCookie()));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Strict"));
    }

    @Test
    void foreignHostHeaderIsRejectedEvenWithSession() throws Exception {
        URI uri = URI.create(server.baseUrl());
        try (Socket socket = new Socket(uri.getHost(), uri.getPort())) {
            OutputStream out = socket.getOutputStream();
            out.write(("GET /api/settings HTTP/1.1\r\nHost: evil.example\r\nCookie: " + server.sessionCookie()
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.startsWith("HTTP/1.1 403"), response);
        }
    }

    @Test
    void writesFromForeignOriginAreRejected() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/reload"))
                .header("Cookie", server.sessionCookie()).header("Origin", "https://evil.example")
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        assertEquals(403, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void settingsNeverExposeTheApiKey() throws Exception {
        HttpResponse<String> response = get("/api/settings", true);
        assertEquals(200, response.statusCode());
        assertFalse(response.body().contains(SECRET_KEY));
        assertTrue(json.readTree(response.body()).path("hasStoredKey").asBoolean());
    }

    @Test
    void savingSettingsWithoutKeyFieldKeepsStoredKey() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/settings"))
                .header("Cookie", server.sessionCookie()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"locale\":\"en_US\",\"riotEnabled\":true}")).build();
        assertEquals(200, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(SECRET_KEY, AppSettings.load(home).storedRiotApiKey());
    }

    @Test
    void bootstrapDeliversGameData() throws Exception {
        HttpResponse<String> response = get("/api/bootstrap", true);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode data = json.readTree(response.body());
        assertEquals(PATCH, data.path("patch").asText());
        assertEquals(3, data.path("champions").size());
        assertTrue(data.path("items").size() > 0);
        assertTrue(data.path("runeTrees").size() > 0);
        assertTrue(data.path("champions").path(0).path("icon").asText().startsWith("/img/" + PATCH + "/champion/"));
    }

    @Test
    void levelsContainAllEighteenLevels() throws Exception {
        HttpResponse<String> response = get("/api/champions/Ahri/levels", true);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode data = json.readTree(response.body());
        assertEquals(18, data.path("finals").size());
        assertEquals(18, data.path("base").path(0).path("values").size());
    }

    @Test
    void unknownChampionIsNotFound() throws Exception {
        assertEquals(404, get("/api/champions/GibtEsNicht/levels", true).statusCode());
    }

    @Test
    void invalidImagePathsAreRejected() throws Exception {
        assertEquals(404, get("/img/16.22.1/champion/..%2F..%2Fsettings.properties", true).statusCode());
        assertEquals(404, get("/img/rune/../../settings.png", true).statusCode());
        assertEquals(404, get("/img/1/unknown/a.png", true).statusCode());
    }

    @Test
    void lolalyticsSourceDeliversAllChampionsAndReport() throws Exception {
        settings.setLolalyticsEnabled(true);
        String list = "https://lolalytics.com/lol/tierlist/?tier=emerald_plus&lane=all&patch=30";
        fake.respond(list, LolalyticsPages.tierList());
        fake.respond("https://lolalytics.com/lol/ahri/build/?tier=emerald_plus&patch=30", LolalyticsPages.build());

        HttpResponse<String> champions = get("/api/stats/champions", true);
        assertEquals(200, champions.statusCode(), champions.body());
        JsonNode ahri = json.readTree(champions.body()).path("103");
        assertEquals("S", ahri.path("tier").asText());
        assertEquals("Mid", ahri.path("lane").asText());
        assertEquals(579_664, ahri.path("games").asInt());
        assertEquals(0.5311, ahri.path("winRate").asDouble(), 1e-9);
        assertEquals(14, ahri.path("pbi").asInt());
        assertFalse(ahri.has("delta"), "Delta ist bewusst nicht Teil der Daten");

        JsonNode meta = json.readTree(get("/api/stats/meta", true).body());
        assertEquals(57_802_290L, meta.path("analysed").asLong());
        assertEquals(1, fake.requests().stream().filter(list::equals).count(), "Liste nur einmal abrufen");

        HttpResponse<String> report = get("/api/stats/report/103", true);
        assertEquals(200, report.statusCode(), report.body());
        JsonNode r = json.readTree(report.body());
        assertEquals(579_664, r.path("games").asInt());
        assertEquals(List.of(3118, 3020, 4645), json.convertValue(r.path("coreBuilds").path("lines").path(0).path("key"),
                new TypeReference<List<Integer>>() { }));
        assertEquals("Q", r.path("skillOrders").path("lines").path(0).path("key").path(0).asText());
        JsonNode counters = r.path("counters");
        assertEquals("Mid", counters.path("lane").asText(), "ohne Rollenfilter gilt die Hauptlane");
        assertEquals(5797, counters.path("minGames").asInt(), "1 % der Spiele des Champions");
        assertTrue(counters.path("strong").isArray());
        assertTrue(counters.path("weak").isArray());
        assertTrue(counters.path("delta").isArray());
    }

    @Test
    void strengthIsOnlyDeliveredWithRoleFilterAndSufficientLaneShare() throws Exception {
        settings.setLolalyticsEnabled(true);
        fake.respond("https://lolalytics.com/lol/tierlist/?tier=emerald_plus&lane=all&patch=30", LolalyticsPages.tierList());
        fake.respond("https://lolalytics.com/lol/tierlist/?tier=emerald_plus&lane=middle&patch=30", LolalyticsPages.tierList());

        JsonNode all = json.readTree(get("/api/stats/champions", true).body());
        assertTrue(all.path("103").path("strength").isNull(), "ohne Rollenfilter kein Wert");

        JsonNode mid = json.readTree(get("/api/stats/champions?role=MIDDLE", true).body());
        assertTrue(mid.path("103").path("strength").isNumber());
        assertTrue(mid.path("1").path("strength").isNumber(), "30 % auf der Rolle reichen");
        assertTrue(mid.path("222").path("strength").isNull(), "0,19 % auf der Rolle reichen nicht");
    }

    @Test
    void lolalyticsTierDefaultsToEmeraldAndCanBeDiamond() throws Exception {
        settings.setLolalyticsEnabled(true);
        String emerald = "https://lolalytics.com/lol/tierlist/?tier=emerald_plus&lane=all&patch=30";
        String diamond = "https://lolalytics.com/lol/tierlist/?tier=diamond_plus&lane=all&patch=30";
        fake.respond(emerald, LolalyticsPages.tierList());
        fake.respond(diamond, LolalyticsPages.tierList());

        assertEquals(200, get("/api/stats/champions", true).statusCode());
        assertTrue(fake.requests().contains(emerald), "ohne Angabe gilt Emerald+");
        assertFalse(fake.requests().contains(diamond));

        assertEquals(200, get("/api/stats/champions?laTier=diamond_plus", true).statusCode());
        assertTrue(fake.requests().contains(diamond));

        int before = fake.requests().size();
        assertEquals(200, get("/api/stats/champions?laTier=unbekannt", true).statusCode());
        assertEquals(before, fake.requests().size(), "unbekannte Werte fallen auf den gecachten Standard zurück");
    }

    // ------------------------------------------------------------------ Summoner

    private static final String ACCOUNT = "https://europe.api.riotgames.com/riot/account/v1/accounts/by-riot-id/Test%20Name/EUW";
    private static final String MATCHES = "https://europe.api.riotgames.com/lol/match/v5/matches/";
    private static final String PLATFORM = "https://euw1.api.riotgames.com/lol/";

    private static String match(int queue, String mode, boolean win, int seconds) {
        return "{\"info\":{\"queueId\":" + queue + ",\"gameMode\":\"" + mode + "\",\"gameDuration\":" + seconds
                + ",\"gameEndTimestamp\":1700000000000,\"gameCreation\":1699999000000,\"participants\":["
                + "{\"puuid\":\"andere\",\"championId\":1,\"win\":" + !win + "},"
                + "{\"puuid\":\"p1\",\"championId\":103,\"championName\":\"Ahri\",\"teamPosition\":\"MIDDLE\",\"win\":" + win
                + ",\"kills\":7,\"deaths\":2,\"assists\":5,\"totalMinionsKilled\":180,\"neutralMinionsKilled\":4,"
                + "\"summoner1Id\":4,\"summoner2Id\":14,\"item0\":3089,\"item1\":0}]}}";
    }

    private void respondSummoner() {
        settings.setRiotEnabled(true);
        fake.respond(ACCOUNT, "{\"puuid\":\"p1\",\"gameName\":\"Test Name\",\"tagLine\":\"EUW\"}")
                .respond(PLATFORM + "summoner/v4/summoners/by-puuid/p1", "{\"summonerLevel\":321,\"profileIconId\":29}")
                .respond(PLATFORM + "league/v4/entries/by-puuid/p1", "[{\"queueType\":\"RANKED_SOLO_5x5\",\"tier\":\"EMERALD\","
                        + "\"rank\":\"II\",\"leaguePoints\":55,\"wins\":60,\"losses\":40},{\"queueType\":\"CHERRY\",\"wins\":1,\"losses\":1}]")
                .respond(PLATFORM + "champion-mastery/v4/champion-masteries/by-puuid/p1/top?count=10",
                        "[{\"championId\":103,\"championLevel\":12,\"championPoints\":250000,\"lastPlayTime\":1700000000000}]")
                .respond(MATCHES + "by-puuid/p1/ids?start=0&count=3", "[\"EUW1_1\",\"EUW1_2\",\"EUW1_3\"]")
                .respond(MATCHES + "EUW1_1", match(420, "CLASSIC", true, 1800))
                .respond(MATCHES + "EUW1_2", match(450, "ARAM", false, 1200))
                .respond(MATCHES + "EUW1_3", match(99999, "NEWMODE", false, 120));
    }

    private static final String SUMMONER_URL = "/api/summoner?riotId=Test%20Name%23EUW&platform=euw1&count=3";

    @Test
    void summonerLookupDeliversProfileRanksMasteryAndMatches() throws Exception {
        respondSummoner();
        HttpResponse<String> response = get(SUMMONER_URL, true);
        assertEquals(200, response.statusCode(), response.body());
        assertFalse(response.body().contains(SECRET_KEY));
        JsonNode r = json.readTree(response.body());
        assertEquals("Test Name", r.path("gameName").asText());
        assertEquals(321, r.path("level").asInt());
        assertTrue(r.path("icon").asText().endsWith("/profileicon/29.png"));
        assertEquals(1, r.path("ranked").size(), "nur Solo und Flex");
        assertEquals("Emerald", r.path("ranked").path(0).path("tier").asText());
        assertEquals(0.6, r.path("ranked").path(0).path("winRate").asDouble(), 1e-9);
        assertEquals(250000, r.path("mastery").path(0).path("points").asLong());

        JsonNode first = r.path("matches").path(0);
        assertEquals(3, r.path("matches").size());
        assertEquals("Ranked Solo/Duo", first.path("mode").asText());
        assertEquals(103, first.path("championKey").asInt());
        assertEquals(184, first.path("cs").asInt());
        assertEquals("Mid", first.path("position").asText());
        assertEquals(1, first.path("items").size(), "leere Slots fallen weg");
        assertFalse(first.path("remake").asBoolean());
        assertEquals("ARAM", r.path("matches").path(1).path("mode").asText());
        assertEquals("NEWMODE (Queue 99999)", r.path("matches").path(2).path("mode").asText(), "unbekannte Queue wird nicht geraten");
        assertTrue(r.path("matches").path(2).path("remake").asBoolean(), "unter 5 Minuten zählt als Remake");
    }

    @Test
    void repeatedSummonerLookupTakesMatchesFromCache() throws Exception {
        respondSummoner();
        assertEquals(200, get(SUMMONER_URL, true).statusCode());
        long before = fake.requests().stream().filter(u -> u.endsWith("EUW1_1")).count();
        assertEquals(200, get(SUMMONER_URL, true).statusCode());
        assertEquals(before, fake.requests().stream().filter(u -> u.endsWith("EUW1_1")).count());
    }

    @Test
    void summonerErrorsAreExplained() throws Exception {
        assertEquals(412, get(SUMMONER_URL, true).statusCode(), "ohne aktivierte Riot-API");
        settings.setRiotEnabled(true);

        HttpResponse<String> badFormat = get("/api/summoner?riotId=OhneTag&platform=euw1", true);
        assertEquals(400, badFormat.statusCode());
        assertTrue(badFormat.body().contains("Name#TAG"));

        HttpResponse<String> unknown = get(SUMMONER_URL, true);
        assertEquals(404, unknown.statusCode());
        assertTrue(unknown.body().contains("nicht gefunden"));

        fake.respond(ACCOUNT, new HttpResult(403, new byte[0], Map.of()));
        HttpResponse<String> expired = get(SUMMONER_URL, true);
        assertEquals(403, expired.statusCode());
        assertTrue(expired.body().contains("abgelaufen"));
        assertFalse(expired.body().contains(SECRET_KEY));
    }

    @Test
    void riotStatisticsAndCollectorAreGone() throws Exception {
        assertEquals(404, get("/api/collector", true).statusCode());
        settings.setLolalyticsEnabled(true);
        fake.respond("https://lolalytics.com/lol/tierlist/?tier=emerald_plus&lane=all&patch=30", LolalyticsPages.tierList());
        assertFalse(get("/api/stats/meta", true).body().contains("matchCount"));
    }

    @Test
    void failedExternalRequestReportsMessageInsteadOfDroppingConnection() throws Exception {
        settings.setLolalyticsEnabled(true);
        fake.respond("https://lolalytics.com/lol/tierlist/?tier=emerald_plus&lane=all&patch=30", "<html>blockiert</html>");
        HttpResponse<String> response = get("/api/stats/champions", true);
        assertEquals(502, response.statusCode());
        assertTrue(response.body().contains("Seitenzustand"), response.body());
    }

    private HttpResponse<String> postDraft(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/draft"))
                .header("Cookie", server.sessionCookie()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void draftNeedsLolalytics() throws Exception {
        assertEquals(412, postDraft("{}").statusCode());
    }

    @Test
    void invalidDraftsAreRejectedBeforeAnyRequest() throws Exception {
        settings.setLolalyticsEnabled(true);
        int before = fake.requests().size();
        assertEquals(400, postDraft("{\"ally\":[{\"champion\":999999}]}").statusCode());
        assertEquals(400, postDraft("{\"ally\":[{\"champion\":103}],\"enemy\":[{\"champion\":103}]}").statusCode());
        assertEquals(400, postDraft("{\"ally\":[{\"champion\":103,\"role\":\"FOO\"}]}").statusCode());
        assertEquals(400, postDraft("{\"risk\":\"FOO\"}").statusCode());
        assertEquals(before, fake.requests().size());
    }

    @Test
    void lolalyticsHasNoGlobalItemStats() throws Exception {
        settings.setLolalyticsEnabled(true);
        fake.respond("https://lolalytics.com/lol/tierlist/?tier=emerald_plus&lane=all&patch=30", LolalyticsPages.tierList());
        assertEquals(404, get("/api/stats/items", true).statusCode());
    }

    @Test
    void disabledLolalyticsIsReported() throws Exception {
        assertEquals(412, get("/api/stats/champions", true).statusCode());
    }

    @Test
    void uiStateRoundTrip() throws Exception {
        assertEquals("{}", get("/api/ui-state", true).body());
        HttpRequest put = HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/ui-state"))
                .header("Cookie", server.sessionCookie()).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"theme\":\"light\"}")).build();
        assertEquals(200, http.send(put, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals("{\"theme\":\"light\"}", get("/api/ui-state", true).body());
    }

    @Test
    void staticFilesCarryContentSecurityPolicy() throws Exception {
        HttpResponse<String> response = get("/", true);
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Security-Policy").orElse("").contains("script-src 'self'"));
        assertEquals(200, get("/vendor/htm-preact.js", true).statusCode());
        assertEquals(404, get("/js/../../secret.js", true).statusCode());
    }
}
