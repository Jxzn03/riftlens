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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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
    void preReleaseChannelIsOffByDefaultAndSwitchable() throws Exception {
        assertFalse(json.readTree(get("/api/settings", true).body()).path("updatePrerelease").asBoolean(true));
        HttpResponse<String> saved = send("POST", "/api/settings", "{\"offline\":true,\"updatePrerelease\":true}");
        assertEquals(200, saved.statusCode(), saved.body());
        assertTrue(json.readTree(saved.body()).path("updatePrerelease").asBoolean());
        assertTrue(AppSettings.load(home).updatePrereleaseEnabled());
        // Ohne Feld (ältere Oberfläche) bleibt der Kanal unverändert.
        send("POST", "/api/settings", "{\"offline\":true}");
        assertTrue(AppSettings.load(home).updatePrereleaseEnabled());
        send("POST", "/api/settings", "{\"offline\":true,\"updatePrerelease\":false}");
        assertFalse(AppSettings.load(home).updatePrereleaseEnabled());
    }

    @Test
    void releaseNotesOfAFreshInstallCountAsSeenAndSeenIsStored() throws Exception {
        // setUp legt die Einstellungsdatei erst nach dem Laden an: Erststart einer Neuinstallation, kein Popup.
        HttpResponse<String> response = get("/api/release-notes", true);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode notes = json.readTree(response.body());
        String current = notes.path("current").asText();
        assertTrue(notes.path("releases").size() > 0);
        assertEquals(0, notes.path("unseen").size());
        assertEquals(current, AppSettings.load(home).releaseNotesSeen());

        settings.setReleaseNotesSeen("1.0.0");
        assertTrue(json.readTree(get("/api/release-notes", true).body()).path("unseen").size() > 0);
        assertEquals(200, send("POST", "/api/release-notes/seen", null).statusCode());
        assertEquals(current, AppSettings.load(home).releaseNotesSeen());
        assertEquals(0, json.readTree(get("/api/release-notes", true).body()).path("unseen").size());
        assertEquals(404, send("POST", "/api/release-notes/other", null).statusCode());
    }

    @Test
    void bootstrapDeliversGameData() throws Exception {
        HttpResponse<String> response = get("/api/bootstrap", true);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode data = json.readTree(response.body());
        assertEquals(PATCH, data.path("patch").asText());
        // Programmversion kommt per Resource-Filtering aus der pom.xml, nie als unersetzter Platzhalter.
        assertTrue(data.path("appVersion").asText().matches("\\d+\\.\\d+\\.\\d+.*"), data.path("appVersion").asText());
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
    void insightsDeliverBuildHasteAndSummonerSpellHasteEvenWithoutWiki() throws Exception {
        // Das Fixture enthält keine Haste-Items; die Auswertung der Item-Texte prüfen die calc-Tests.
        HttpResponse<String> response = get("/api/champions/Ahri/insights?level=11&items=3157", true);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode data = json.readTree(response.body());
        assertTrue(data.path("disabled").asBoolean(), "Wiki ist im Test aus");
        assertEquals(1, data.path("itemCount").asInt());
        assertEquals(5, data.path("haste").size(), "Haste je Slot P, Q, W, E, R auch ohne Wiki");
        assertTrue(data.path("summonerSpellHaste").isNumber());
        assertEquals(0, data.path("staticCooldowns").size());
    }

    @Test
    void levelsIncludeTheRunePage() throws Exception {
        JsonNode plain = json.readTree(get("/api/champions/Ahri/levels", true).body());
        // Ultimate Hunter mit 5 Stapeln, Shards: Ability Haste, Adaptive Force, Health (65).
        HttpResponse<String> response = get("/api/champions/Ahri/levels?runes=8106&shards=5007,5008,5011&stacks=8106:5", true);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode withRunes = json.readTree(response.body());
        assertEquals(4, withRunes.path("runeCount").asInt());
        JsonNode level1 = withRunes.path("finals").path(0);
        double health = level1.path(0).path("total").asDouble();
        assertEquals(plain.path("finals").path(0).path(0).path("total").asDouble() + 65, health, 0.6);
        assertTrue(rowValue(level1, "Ability Haste").startsWith("8"), level1.toString());
        assertTrue(rowValue(level1, "Haste Ultimate").startsWith("39"), "8 + 6 + 5 × 5");

        assertEquals(400, get("/api/champions/Ahri/levels?runes=abc", true).statusCode());
        assertEquals(400, get("/api/champions/Ahri/levels?runes=8106&stacks=8106", true).statusCode());
    }

    @Test
    void startPrunesOldPatchesAndOutdatedLolalyticsEntriesOnce() throws Exception {
        java.nio.file.Path root = settings.cacheDir();
        for (String patch : List.of("16.19.1", "16.20.1", "16.21.1")) {
            java.nio.file.Files.createDirectories(root.resolve(patch).resolve("img/item"));
            java.nio.file.Files.writeString(root.resolve(patch).resolve("img/item/1001.png"), "x");
        }
        java.nio.file.Path oldFormat = root.resolve("_global/lolalytics/build_v3_ahri_all_middle_30d.json");
        java.nio.file.Files.createDirectories(oldFormat.getParent());
        java.nio.file.Files.writeString(oldFormat, "{}");

        WebContext second = new WebContext(settings, fake, BASE);
        try {
            second.reload().join();
            Instant deadline = Instant.now().plusSeconds(10);
            while (java.nio.file.Files.exists(root.resolve("16.19.1")) && Instant.now().isBefore(deadline)) {
                Thread.sleep(20);
            }
            assertFalse(java.nio.file.Files.exists(root.resolve("16.19.1")));
            assertFalse(java.nio.file.Files.exists(root.resolve("16.20.1")));
            assertTrue(java.nio.file.Files.isDirectory(root.resolve("16.21.1")), "ein älterer Patch bleibt als Rückfall");
            assertTrue(java.nio.file.Files.isDirectory(root.resolve(PATCH)));
            deadline = Instant.now().plusSeconds(10);
            while (java.nio.file.Files.exists(oldFormat) && Instant.now().isBefore(deadline)) {
                Thread.sleep(20);
            }
            assertFalse(java.nio.file.Files.exists(oldFormat), "Lolalytics-Eintrag im alten Format gelöscht");
        } finally {
            second.awaitData();
            second.close();
        }
    }

    private static String rowValue(JsonNode rows, String label) {
        for (JsonNode row : rows) {
            if (label.equals(row.path("label").asText())) {
                return row.path("total").asText();
            }
        }
        return "";
    }

    @Test
    void firstStartAfterAPatchChangeRefreshesOnceAndIsReported() throws Exception {
        // Letzter bekannter Patch älter als der aktuelle (16.22.1 laut Fixture): wie beim ersten Start nach einem Patch.
        java.nio.file.Path state = settings.cacheDir().resolve("_global/patch/state.json");
        java.nio.file.Files.writeString(state, "{\"patch\":\"16.21.1\",\"changedAt\":\"1970-01-01T00:00:00Z\"}");
        java.nio.file.Files.deleteIfExists(settings.cacheDir().resolve("_global/rune-effects/state.json"));

        WebContext second = new WebContext(settings, fake, BASE);
        try {
            second.reload().join();
            assertTrue(second.patchChange() != null && second.patchChange().isNew());
            assertEquals("16.21.1", second.patchChange().previous());
            assertEquals(PATCH, second.runeEffects().latest().patch(), "Runenwerte für den neuen Patch abgeglichen");
            assertTrue(java.nio.file.Files.readString(state).contains(PATCH));
        } finally {
            second.awaitData();
            second.close();
        }
        WebContext third = new WebContext(settings, fake, BASE);
        try {
            third.reload().join();
            assertEquals(null, third.patchChange(), "zweiter Start mit demselben Patch: kein erneuter Abgleich");
        } finally {
            third.awaitData();
            third.close();
        }
    }

    @Test
    void bootstrapDescribesRuneEffectsAndShardRows() throws Exception {
        JsonNode data = json.readTree(get("/api/bootstrap", true).body());
        assertEquals(3, data.path("shardRows").size());
        boolean transcendence = false;
        for (JsonNode e : data.path("runeEffects")) {
            transcendence |= e.path("id").asInt() == 8210 && "always".equals(e.path("kind").asText());
        }
        assertTrue(transcendence);
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
        fake.respond("https://lolalytics.com/lol/ahri/build/?tier=emerald_plus&lane=middle&patch=30", LolalyticsPages.build());

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
        JsonNode mostPlayed = r.path("coreBuilds").path("mostPlayed");
        assertEquals(3, mostPlayed.path("key").size(), "Meistgespielter Core-Build mit 3 Items");
        for (JsonNode line : r.path("coreBuilds").path("lines")) {
            assertTrue(mostPlayed.path("games").asInt() >= line.path("games").asInt(), "mostPlayed hat die meisten Spiele");
        }
        assertEquals("Q", r.path("skillOrders").path("lines").path(0).path("key").path(0).asText());
        assertEquals(List.of("W", "Q", "E", "Q", "Q", "R", "Q", "E", "Q", "E", "R", "E", "E", "W", "W", "R", "W", "W"),
                json.convertValue(r.path("skillPath"), new TypeReference<List<String>>() { }),
                "Level 1–15 aus der kompletten Skillung, 16–18 abgeleitet");
        assertEquals(15, r.path("skillPathMeasured").asInt());
        JsonNode counters = r.path("counters");
        assertEquals("Mid", counters.path("lane").asText(), "ohne Rollenfilter gilt die Hauptlane");
        assertEquals(5797, counters.path("minGames").asInt(), "1 % der Spiele des Champions");
        assertTrue(counters.path("best").isArray(), "Sicht des Gegners: beste Picks gegen den Champion");
        assertTrue(counters.path("worst").isArray());
        assertTrue(counters.path("delta").isArray());
        JsonNode lateItems = r.path("lateItems");
        assertEquals(2, lateItems.size(), "Core-Item und zu seltene Items fallen weg (lateItems)");
        assertEquals(3157, lateItems.path(0).path("item").asInt());
        // Platz 3: 119 752 Spiele in Minute 27, Platz 4: 105 612 in Minute 31, Platz 5: 8 924 in Minute 34.
        assertEquals(29, r.path("lateItemsMinute").asInt(), "Kaufminute über Platz 3–5, nach Spielen gewichtet");
        assertFalse(lateItems.path(0).has("minute"), "nicht je Item (Nutzerwunsch)");
        assertEquals(3089, lateItems.path(1).path("item").asInt());
        assertEquals(45_562 + 8_924, lateItems.path(1).path("games").asInt(), "lateItems: Spiele über alle Plätze");
        JsonNode first = r.path("firstItems");
        assertFalse(first.path("derived").asBoolean(), "Itemplatz 1 kommt von der Seite");
        assertEquals(3118, first.path("lines").path(0).path("key").path(0).asInt());
        assertEquals(3, first.path("lines").size(), "alle Einträge der Seite");
        assertEquals(3041, first.path("lines").path(2).path("key").path(0).asInt(), "Mejai's trotz bester Winrate am Ende");
        assertTrue(first.path("lines").path(2).path("snowball").asBoolean());
        assertFalse(first.path("lines").path(0).has("snowball"));
        JsonNode second = r.path("secondItems");
        assertTrue(second.path("derived").asBoolean(), "ohne Itemplatz 2 aus den Core-Builds");
        assertEquals(4645, second.path("lines").path(0).path("key").path(0).asInt(), "Boots (3020) zählen nicht als Item");
        assertEquals(102_489, second.path("lines").path(0).path("games").asInt());
        assertEquals(5, lateItems.path(1).path("slots").path(1).path("slot").asInt(), "lateItems: Aufteilung je Platz");
    }

    @Test
    void reportWithoutRoleSharesTheEntryOfTheMainLane() throws Exception {
        settings.setLolalyticsEnabled(true);
        String build = "https://lolalytics.com/lol/ahri/build/?tier=emerald_plus&lane=middle&patch=30";
        fake.respond("https://lolalytics.com/lol/tierlist/?tier=emerald_plus&lane=all&patch=30", LolalyticsPages.tierList());
        fake.respond("https://lolalytics.com/lol/tierlist/?tier=emerald_plus&lane=middle&patch=30", LolalyticsPages.tierList());
        fake.respond(build, LolalyticsPages.build());

        // Ohne Rolle wird die Hauptlane laut Liste (Mid) mit Lane abgefragt, nie die Seite ohne Lane.
        String auto = get("/api/stats/report/103", true).body();
        assertTrue(fake.requests().stream().noneMatch(u -> u.contains("/build/") && !u.contains("&lane=")), fake.requests().toString());
        // Dieselbe Rolle von Hand (wie im Draft): derselbe Cache-Eintrag, kein zweiter Abruf der Seite.
        String chosen = get("/api/stats/report/103?role=MIDDLE", true).body();
        assertEquals(1, fake.requests().stream().filter(build::equals).count());
        assertEquals(json.readTree(auto).path("games"), json.readTree(chosen).path("games"));
    }

    @Test
    void cachedOnlyReportNeverFetches() throws Exception {
        settings.setLolalyticsEnabled(true);
        String list = "https://lolalytics.com/lol/tierlist/?tier=emerald_plus&lane=all&patch=30";
        String build = "https://lolalytics.com/lol/ahri/build/?tier=emerald_plus&lane=middle&patch=30";
        fake.respond(list, LolalyticsPages.tierList());
        fake.respond(build, LolalyticsPages.build());

        // Leerer Cache: keine Antwort aus dem Cache und kein Abruf, weder Liste noch Seite.
        HttpResponse<String> empty = get("/api/stats/report/103?cachedOnly=1", true);
        assertEquals(200, empty.statusCode(), empty.body());
        assertFalse(json.readTree(empty.body()).path("cached").asBoolean(true));
        assertTrue(fake.requests().stream().noneMatch(u -> u.contains("lolalytics")), "kein Lolalytics-Abruf");

        // Nur die Liste im Cache (Champions-Liste geladen): die Seite fehlt weiter, es wird nichts abgerufen.
        get("/api/stats/champions", true);
        int afterList = fake.requests().size();
        JsonNode listOnly = json.readTree(get("/api/stats/report/103?cachedOnly=1", true).body());
        assertFalse(listOnly.path("cached").asBoolean(true));
        assertEquals("Mid", listOnly.path("lane").asText(), "Rollen-Fallback: Hauptlane aus der gecachten Liste");
        assertEquals(afterList, fake.requests().size());
        assertFalse(fake.requests().contains(build));

        // Nach dem normalen Abruf liefert die Cache-Variante denselben Report ohne weiteren Abruf.
        String normal = get("/api/stats/report/103", true).body();
        int afterReport = fake.requests().size();
        assertEquals(json.readTree(normal), json.readTree(get("/api/stats/report/103?cachedOnly=1", true).body()));
        assertEquals(afterReport, fake.requests().size());
    }

    @Test
    void cachedOnlyLaneFallbackUsesOutdatedListWithoutRefresh() throws Exception {
        settings.setLolalyticsEnabled(true);
        fake.respond("https://lolalytics.com/lol/tierlist/?tier=emerald_plus&lane=all&patch=30", LolalyticsPages.tierList());
        get("/api/stats/champions", true);
        int afterList = fake.requests().size();
        age("lolalytics/full_v4_emerald_plus_all_30d.json");

        JsonNode r = json.readTree(get("/api/stats/report/103?cachedOnly=1", true).body());
        assertFalse(r.path("cached").asBoolean(true));
        assertEquals("Mid", r.path("lane").asText(), "Hauptlane aus der veralteten Liste");
        Thread.sleep(300);
        assertEquals(afterList, fake.requests().size(), "kein Abruf, keine Erneuerung im Hintergrund");
    }

    @Test
    void outdatedThirtyDayStatisticsShowAtOnceAndRefreshOnceInTheBackground() throws Exception {
        settings.setLolalyticsEnabled(true);
        String list = "https://lolalytics.com/lol/tierlist/?tier=emerald_plus&lane=all&patch=30";
        String build = "https://lolalytics.com/lol/ahri/build/?tier=emerald_plus&lane=middle&patch=30";
        fake.respond(list, LolalyticsPages.tierList());
        fake.respond(build, LolalyticsPages.build());
        String fresh = get("/api/stats/report/103", true).body();
        Path listFile = age("lolalytics/full_v4_emerald_plus_all_30d.json");
        Path buildFile = age("lolalytics/build_v4_ahri_emerald_plus_middle_30d.json");

        // Abrufe angehalten: Müsste die Anfrage auf sie warten, liefe sie in die Zeitgrenze.
        CountDownLatch releaseList = fake.hold(list);
        CountDownLatch releaseBuild = fake.hold(build);
        try {
            HttpResponse<String> report = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> get("/api/stats/report/103", true));
            assertEquals(200, report.statusCode(), report.body());
            assertEquals(json.readTree(fresh), json.readTree(report.body()), "alte Werte sofort");
        } finally {
            releaseList.countDown();
            releaseBuild.countDown();
        }

        // Danach erneuert der Hintergrund jeden Eintrag genau einmal, mit der üblichen Drosselung.
        Instant deadline = Instant.now().plusSeconds(15);
        while ((isOld(listFile) || isOld(buildFile)) && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
        }
        assertFalse(isOld(listFile), "Liste erneuert");
        assertFalse(isOld(buildFile), "Seite erneuert");
        assertEquals(2, fake.requests().stream().filter(list::equals).count());
        assertEquals(2, fake.requests().stream().filter(build::equals).count());
    }

    private Path age(String key) throws IOException {
        Path file = settings.cacheDir().resolve("_global").resolve(key);
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        return file;
    }

    private static boolean isOld(Path file) throws IOException {
        return Files.getLastModifiedTime(file).toInstant().isBefore(Instant.now().minus(Duration.ofDays(1)));
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
                .respond(PLATFORM + "champion-mastery/v4/champion-masteries/by-puuid/p1",
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
    void ownAccountsAreStoredLocallyAndReadByTheDraftWithoutRiotCalls() throws Exception {
        assertEquals(0, json.readTree(get("/api/summoner/own", true).body()).path("accounts").size());
        respondSummoner();
        assertEquals(200, get(SUMMONER_URL, true).statusCode());
        fake.respond("https://europe.api.riotgames.com/riot/account/v1/accounts/by-riot-id/Zweit/EUW",
                "{\"puuid\":\"p2\",\"gameName\":\"Zweit\",\"tagLine\":\"EUW\"}");

        HttpResponse<String> saved = send("POST", "/api/summoner/own", "{\"riotId\":\" Test Name#EUW \",\"platform\":\"euw1\"}");
        assertEquals(200, saved.statusCode(), saved.body());
        assertEquals("Test Name#EUW", json.readTree(saved.body()).path("accounts").path(0).path("riotId").asText(), "Riot-ID laut Riot, ohne Leerraum");
        assertFalse(saved.body().contains("p1"), "PUUID bleibt im Backend");
        send("POST", "/api/summoner/own", "{\"riotId\":\"Zweit#EUW\",\"platform\":\"euw1\"}");
        send("POST", "/api/summoner/own", "{\"riotId\":\"Test Name#EUW\",\"platform\":\"euw1\"}");

        int before = fake.requests().size();
        settings.setRiotEnabled(false);
        JsonNode own = json.readTree(get("/api/summoner/own", true).body());
        assertEquals(before, fake.requests().size(), "Draft-Abfrage ohne Riot-Aufruf, auch ohne aktivierte Riot-API");
        assertEquals(List.of("Test Name#EUW", "Zweit#EUW"), List.of(own.path("accounts").path(0).path("riotId").asText(),
                own.path("accounts").path(1).path("riotId").asText()), "zwei Konten, dasselbe Konto nur einmal");
        assertTrue(own.path("matchups").path("own").has("103"), "Bilanz über alle Konten");

        assertEquals(200, send("POST", "/api/summoner/own", "{\"riotId\":\"test name#euw\",\"platform\":\"euw1\",\"remove\":true}").statusCode());
        JsonNode left = json.readTree(get("/api/summoner/own", true).body());
        assertEquals(1, left.path("accounts").size());
        assertEquals("Zweit#EUW", left.path("accounts").path(0).path("riotId").asText());
    }

    @Test
    void liveGameShowsNamesAndRanksAndLoadsChampionWinRateOnDemand() throws Exception {
        respondSummoner();
        String spectator = PLATFORM + "spectator/v5/active-games/by-summoner/p1";
        fake.respond(spectator, "{\"gameId\":5,\"gameQueueConfigId\":420,\"gameMode\":\"CLASSIC\",\"gameStartTime\":1,"
                        + "\"participants\":[{\"puuid\":\"p1\",\"teamId\":100,\"championId\":103,\"riotId\":\"Test Name#EUW\",\"spell1Id\":4,\"spell2Id\":14},"
                        + "{\"puuid\":\"foe\",\"teamId\":200,\"championId\":91,\"riotId\":\"Gegner#X1\",\"spell1Id\":4,\"spell2Id\":12}],"
                        + "\"bannedChampions\":[{\"championId\":157,\"teamId\":200,\"pickTurn\":1},{\"championId\":-1,\"teamId\":100}]}")
                .respond(PLATFORM + "league/v4/entries/by-puuid/foe", "[{\"queueType\":\"RANKED_SOLO_5x5\",\"tier\":\"GOLD\","
                        + "\"rank\":\"I\",\"leaguePoints\":10,\"wins\":5,\"losses\":5}]")
                .respond(MATCHES + "by-puuid/foe/ids?queue=420&start=0&count=20", "[\"EUW1_F\"]")
                .respond(MATCHES + "EUW1_F", "{\"info\":{\"gameDuration\":1800,\"gameEndTimestamp\":1,\"participants\":["
                        + "{\"puuid\":\"foe\",\"championId\":91,\"win\":true}]}}");

        JsonNode live = json.readTree(get("/api/summoner/live?riotId=Test%20Name%23EUW&platform=euw1", true).body());
        assertTrue(live.path("inGame").asBoolean());
        JsonNode foe = live.path("teams").path(1).path("players").path(0);
        assertEquals("Gegner", foe.path("name").asText());
        assertEquals("Gold", foe.path("solo").path("tier").asText());
        assertFalse(foe.has("champion"), "Champion-Winrate anderer Spieler nur auf Knopfdruck");
        assertEquals(List.of(157), json.convertValue(live.path("teams").path(1).path("bans"), List.class));
        assertFalse(live.toString().contains("\"foe\""), "PUUIDs gehen nicht an die Oberfläche");
        assertEquals(0, fake.requests().stream().filter(u -> u.contains("by-puuid/foe/ids")).count());

        JsonNode wr = json.readTree(get("/api/summoner/live/" + foe.path("slot").asInt() + "/champion", true).body());
        assertEquals(1, wr.path("games").asInt());
        assertEquals(1, wr.path("wins").asInt());

        fake.respond(spectator, new HttpResult(404, new byte[0], Map.of()));
        assertFalse(json.readTree(get("/api/summoner/live?riotId=Test%20Name%23EUW&platform=euw1", true).body()).path("inGame").asBoolean());
        assertEquals(409, get("/api/summoner/live/1/champion", true).statusCode(), "nach Spielende kein alter Platz");
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

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
                .header("Cookie", server.sessionCookie()).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static final String ARCHIVE_ID = "0b6f3f8e-6f3c-4d39-9a52-3d2f4f0e8a11";

    @Test
    void draftArchiveListsAndDeletesLocalEntriesWithoutLolalytics() throws Exception {
        java.nio.file.Files.writeString(home.resolve("draft-archive.json"), """
                {"version":1,"entries":[{"id":"%s","savedAt":"2026-11-14T12:00:00Z","patch":"16.22.1",
                 "ally":[{"champion":1,"role":"TOP"},{"champion":2},{"champion":3},{"champion":4},{"champion":5}],
                 "enemy":[{"champion":6},{"champion":7},{"champion":8},{"champion":9},{"champion":10}],"bans":[11]}]}"""
                .formatted(ARCHIVE_ID));

        JsonNode list = json.readTree(get("/api/draft-archive", true).body());
        assertEquals(100, list.path("limit").asInt(), "Standardgröße");
        assertEquals(3, list.path("minChanges").asInt());
        JsonNode entry = list.path("entries").get(0);
        assertEquals(ARCHIVE_ID, entry.path("id").asText());
        assertEquals("TOP", entry.path("ally").get(0).path("role").asText());
        assertTrue(entry.path("ally").get(1).path("role").isNull());
        assertEquals(11, entry.path("bans").get(0).asInt());

        assertEquals(404, send("DELETE", "/api/draft-archive/../settings", null).statusCode());
        assertEquals(200, send("DELETE", "/api/draft-archive/" + ARCHIVE_ID, null).statusCode());
        assertEquals(404, send("DELETE", "/api/draft-archive/" + ARCHIVE_ID, null).statusCode());
        assertEquals(0, json.readTree(get("/api/draft-archive", true).body()).path("entries").size());
    }

    @Test
    void draftArchiveResultIsSetByHand() throws Exception {
        java.nio.file.Files.writeString(home.resolve("draft-archive.json"), """
                {"version":1,"entries":[{"id":"%s","savedAt":"2026-11-14T12:00:00Z","patch":"16.22.1",
                 "ally":[{"champion":1},{"champion":2},{"champion":3},{"champion":4},{"champion":5}],
                 "enemy":[{"champion":6},{"champion":7},{"champion":8},{"champion":9},{"champion":10}],"bans":[]}]}"""
                .formatted(ARCHIVE_ID));
        assertTrue(json.readTree(get("/api/draft-archive", true).body()).path("entries").get(0).path("result").isNull(), "Alte Datei: offen");

        JsonNode won = json.readTree(send("PUT", "/api/draft-archive/" + ARCHIVE_ID, "{\"result\":\"WIN\"}").body());
        assertEquals("WIN", won.path("entry").path("result").asText());
        assertEquals("WIN", won.path("entries").get(0).path("result").asText());
        assertEquals("WIN", json.readTree(get("/api/draft-archive", true).body()).path("entries").get(0).path("result").asText());

        assertEquals(400, send("PUT", "/api/draft-archive/" + ARCHIVE_ID, "{\"result\":\"DRAW\"}").statusCode());
        assertEquals(404, send("PUT", "/api/draft-archive/" + ARCHIVE_ID.replace('0', '1'), "{\"result\":\"LOSS\"}").statusCode());
        JsonNode open = json.readTree(send("PUT", "/api/draft-archive/" + ARCHIVE_ID, "{\"result\":null}").body());
        assertTrue(open.path("entry").path("result").isNull());
    }

    @Test
    void deletedArchiveDraftCanBeRestoredOrPurgedFromTrash() throws Exception {
        java.nio.file.Files.writeString(home.resolve("draft-archive.json"), """
                {"version":1,"entries":[{"id":"%s","savedAt":"2026-11-14T12:00:00Z","patch":"16.22.1",
                 "ally":[{"champion":1},{"champion":2},{"champion":3},{"champion":4},{"champion":5}],
                 "enemy":[{"champion":6},{"champion":7},{"champion":8},{"champion":9},{"champion":10}],"bans":[]}]}"""
                .formatted(ARCHIVE_ID));

        JsonNode deleted = json.readTree(send("DELETE", "/api/draft-archive/" + ARCHIVE_ID, null).body());
        assertEquals(0, deleted.path("entries").size());
        assertEquals(7, deleted.path("trashDays").asInt());
        assertEquals(ARCHIVE_ID, deleted.path("trash").get(0).path("id").asText());
        assertTrue(deleted.path("trash").get(0).path("expiresAt").isTextual());

        assertEquals(404, send("POST", "/api/draft-archive/" + ARCHIVE_ID + "/foo", null).statusCode());
        JsonNode restored = json.readTree(send("POST", "/api/draft-archive/" + ARCHIVE_ID + "/restore", null).body());
        assertEquals(1, restored.path("entries").size());
        assertEquals(0, restored.path("trash").size());
        assertEquals(404, send("POST", "/api/draft-archive/" + ARCHIVE_ID + "/restore", null).statusCode());

        send("DELETE", "/api/draft-archive/" + ARCHIVE_ID, null);
        assertEquals(200, send("DELETE", "/api/draft-archive/trash/" + ARCHIVE_ID, null).statusCode());
        assertEquals(404, send("DELETE", "/api/draft-archive/trash/" + ARCHIVE_ID, null).statusCode());
        assertEquals(0, json.readTree(send("DELETE", "/api/draft-archive/trash", null).body()).path("trash").size());
    }

    @Test
    void draftArchiveTakesOnlyCompleteDrafts() throws Exception {
        assertEquals(400, send("POST", "/api/draft-archive", "{\"ally\":[{\"champion\":103}],\"enemy\":[]}").statusCode(),
                "Unvollständig, ohne Lolalytics trotzdem kein 412");
        assertEquals(400, send("POST", "/api/draft-archive", "{\"ally\":[{\"champion\":999999}]}").statusCode());
        assertFalse(java.nio.file.Files.exists(home.resolve("draft-archive.json")));
    }

    @Test
    void draftArchiveLimitIsASetting() throws Exception {
        JsonNode defaults = json.readTree(get("/api/settings", true).body());
        assertEquals(100, defaults.path("draftArchiveLimit").asInt(), "Ohne Einstellung 100 Drafts");
        assertEquals(100, defaults.path("draftArchiveDefault").asInt());
        assertEquals("[10,25,50,100,250,500,1000]", defaults.path("draftArchiveLimits").toString(), "Wählbare Größen");
        assertEquals(200, send("POST", "/api/settings", "{\"draftArchiveLimit\":50}").statusCode());
        assertEquals(50, json.readTree(get("/api/settings", true).body()).path("draftArchiveLimit").asInt());
        assertEquals(50, json.readTree(get("/api/draft-archive", true).body()).path("limit").asInt());
        assertEquals(200, send("POST", "/api/settings", "{\"draftArchiveLimit\":1000}").statusCode());
        assertEquals(1000, json.readTree(get("/api/draft-archive", true).body()).path("limit").asInt());
        assertEquals(200, send("POST", "/api/settings", "{\"draftArchiveLimit\":7}").statusCode());
        assertEquals(100, AppSettings.load(home).draftArchiveLimit(), "Unbekannte Größe ergibt den Standard");
    }

    @Test
    void finishedFearlessSeriesHaveTheirOwnLimit() throws Exception {
        JsonNode defaults = json.readTree(get("/api/settings", true).body());
        assertEquals(50, defaults.path("fearlessSeriesLimit").asInt(), "Ohne Einstellung 50 beendete Serien");
        assertEquals("[10,25,50,100]", defaults.path("fearlessSeriesLimits").toString());

        StringBuilder finished = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            finished.append(i == 0 ? "" : ",").append("""
                    {"id":"%08d-6f3c-4d39-9a52-3d2f4f0e8a11","label":"Serie %d","format":"BO3","startedAt":"2026-11-%02dT18:00:00Z",
                     "endedAt":"2026-11-%02dT19:00:00Z","games":[{"number":1,"playedAt":"2026-11-%02dT18:40:00Z","patch":"","result":null,
                     "ally":[{"champion":1},{"champion":2},{"champion":3},{"champion":4},{"champion":5}],
                     "enemy":[{"champion":6},{"champion":7},{"champion":8},{"champion":9},{"champion":10}],"bans":[]}]}"""
                    .formatted(i, i, 28 - i, 28 - i, 28 - i));
        }
        java.nio.file.Files.writeString(home.resolve("fearless-series.json"),
                "{\"version\":2,\"active\":null,\"finished\":[" + finished + "]}");
        assertEquals(12, json.readTree(get("/api/fearless", true).body()).path("finished").size());

        assertEquals(200, send("POST", "/api/settings", "{\"fearlessSeriesLimit\":10,\"draftArchiveLimit\":250}").statusCode());
        assertEquals(10, json.readTree(get("/api/fearless", true).body()).path("finished").size(), "Eigene Grenze für Serien");
        assertEquals(250, json.readTree(get("/api/draft-archive", true).body()).path("limit").asInt(), "Draft-Archiv unabhängig");
        assertEquals(200, send("POST", "/api/settings", "{\"fearlessSeriesLimit\":7}").statusCode());
        assertEquals(50, AppSettings.load(home).fearlessSeriesLimit(), "Unbekannte Zahl ergibt den Standard");
    }

    @Test
    void runningOpenSeriesEndsEarlyWhenItsNumberOfGamesIsSetToThePlayedOnes() throws Exception {
        java.nio.file.Files.writeString(home.resolve("fearless-series.json"), """
                {"version":2,"active":{"id":"%s","label":"Scrim","format":"OPEN","maxGames":5,"startedAt":"2026-11-16T18:00:00Z",
                 "endedAt":null,"games":[{"number":1,"playedAt":"2026-11-16T18:40:00Z","patch":"","result":null,
                 "ally":[{"champion":1},{"champion":2},{"champion":3},{"champion":4},{"champion":5}],
                 "enemy":[{"champion":6},{"champion":7},{"champion":8},{"champion":9},{"champion":10}],"bans":[]}]},
                 "finished":[]}""".formatted(ARCHIVE_ID));

        JsonNode ended = json.readTree(send("PUT", "/api/fearless/" + ARCHIVE_ID, "{\"label\":\"Scrim\",\"maxGames\":1}").body());
        assertTrue(ended.path("ended").asBoolean(), "„Jetzt beenden“: Anzahl Games = gespielte");
        assertTrue(ended.path("active").isNull());
        assertEquals(1, ended.path("finished").get(0).path("maxGames").asInt());
        assertEquals(0, ended.path("locked").size(), "Keine Sperren mehr");

        JsonNode reopened = json.readTree(send("PUT", "/api/fearless/" + ARCHIVE_ID, "{\"maxGames\":3}").body());
        assertTrue(reopened.path("reopened").asBoolean(), "Mehr Games öffnen sie wieder");
    }

    @Test
    void seriesGamesOfOlderFilesAppearUnderDraftsAndFullSeriesReopensWithMoreGames() throws Exception {
        java.nio.file.Files.writeString(home.resolve("fearless-series.json"), """
                {"version":1,"active":null,"finished":[{"id":"%s","label":"Scrim","format":"OPEN","maxGames":1,
                 "startedAt":"2026-11-16T18:00:00Z","endedAt":"2026-11-16T19:00:00Z",
                 "games":[{"number":1,"playedAt":"2026-11-16T18:40:00Z","patch":"16.22.1","result":"WIN",
                 "ally":[{"champion":1},{"champion":2},{"champion":3},{"champion":4},{"champion":5}],
                 "enemy":[{"champion":6},{"champion":7},{"champion":8},{"champion":9},{"champion":10}],"bans":[]}]}]}""".formatted(ARCHIVE_ID));

        JsonNode drafts = json.readTree(get("/api/draft-archive", true).body());
        assertEquals(1, drafts.path("entries").size(), "Das Serienspiel steht unter „Drafts“");
        assertEquals("2026-11-16T18:40:00Z", drafts.path("entries").get(0).path("savedAt").asText(), "Mit dem Spielzeitpunkt");
        get("/api/fearless", true);
        assertEquals(1, json.readTree(get("/api/draft-archive", true).body()).path("entries").size(), "Nur einmal nachgetragen");

        JsonNode reopened = json.readTree(send("PUT", "/api/fearless/" + ARCHIVE_ID, "{\"maxGames\":3}").body());
        assertTrue(reopened.path("reopened").asBoolean(), "Volle Serie läuft mit mehr Platz wieder");
        assertEquals(ARCHIVE_ID, reopened.path("active").path("id").asText());
        assertEquals(3, reopened.path("active").path("maxGames").asInt());
    }

    @Test
    void fearlessSeriesStartsRenamesAndEndsLocally() throws Exception {
        JsonNode empty = json.readTree(get("/api/fearless", true).body());
        assertTrue(empty.path("active").isNull());
        assertEquals(0, empty.path("locked").size());
        assertEquals(60, empty.path("maxLabelLength").asInt());

        assertEquals(400, send("POST", "/api/fearless", "{\"format\":\"BO7\"}").statusCode());
        assertEquals(400, send("POST", "/api/fearless", "{\"format\":\"BO3\",\"label\":\"" + "x".repeat(61) + "\"}").statusCode());
        HttpResponse<String> started = send("POST", "/api/fearless", "{\"format\":\"BO3\",\"label\":\" Scrim Dienstag \"}");
        assertEquals(200, started.statusCode());
        JsonNode active = json.readTree(started.body()).path("active");
        assertEquals("Scrim Dienstag", active.path("label").asText());
        assertEquals(1, active.path("nextGame").asInt());
        assertEquals(409, send("POST", "/api/fearless", "{\"format\":\"BO5\"}").statusCode(), "Nur eine Serie gleichzeitig");

        assertEquals(400, send("POST", "/api/fearless/game", "{\"ally\":[{\"champion\":103}],\"enemy\":[]}").statusCode(),
                "Nur vollständige Drafts");
        String id = active.path("id").asText();
        assertEquals(200, send("PUT", "/api/fearless/" + id, "{\"label\":\"Pro-Serie\"}").statusCode());
        assertEquals("Pro-Serie", json.readTree(get("/api/fearless", true).body()).path("active").path("label").asText());
        assertEquals(404, send("PUT", "/api/fearless/" + ARCHIVE_ID, "{\"label\":\"x\"}").statusCode());
        assertEquals(404, send("DELETE", "/api/fearless/../settings", null).statusCode());

        JsonNode ended = json.readTree(send("POST", "/api/fearless/end", null).body());
        assertTrue(ended.path("active").isNull());
        assertEquals(0, ended.path("finished").size(), "Ohne Spiel wird die Serie verworfen");

        assertEquals(400, send("POST", "/api/fearless", "{\"format\":\"OPEN\",\"maxGames\":11}").statusCode());
        assertEquals(400, send("POST", "/api/fearless", "{\"format\":\"OPEN\",\"maxGames\":\"4\"}").statusCode());
        JsonNode open = json.readTree(send("POST", "/api/fearless", "{\"format\":\"OPEN\",\"maxGames\":4}").body()).path("active");
        assertEquals(4, open.path("maxGames").asInt(), "Gewählte Spielzahl bei „Offen“");
        String openId = open.path("id").asText();
        assertEquals(400, send("PUT", "/api/fearless/" + openId, "{\"maxGames\":\"6\"}").statusCode());
        assertEquals(400, send("PUT", "/api/fearless/" + openId, "{\"maxGames\":0,\"label\":\"x\"}").statusCode());
        assertTrue(json.readTree(get("/api/fearless", true).body()).path("active").path("label").isNull(),
                "Ungültige Spielzahl ändert auch die Bezeichnung nicht");
        assertEquals(400, send("PUT", "/api/fearless/" + openId, "{\"maxGames\":6,\"label\":\"" + "x".repeat(61) + "\"}").statusCode());
        assertEquals(4, json.readTree(get("/api/fearless", true).body()).path("active").path("maxGames").asInt(),
                "Ungültige Bezeichnung ändert auch die Spielzahl nicht");
        JsonNode changed = json.readTree(send("PUT", "/api/fearless/" + openId, "{\"maxGames\":6,\"label\":\"Scrim\"}").body());
        assertEquals(6, changed.path("active").path("maxGames").asInt());
        assertEquals("Scrim", changed.path("active").path("label").asText());
        assertFalse(changed.path("ended").asBoolean());
        JsonNode deleted = json.readTree(send("DELETE", "/api/fearless/" + open.path("id").asText(), null).body());
        assertTrue(deleted.path("active").isNull(), "Laufende Serie lässt sich löschen");
        assertEquals(0, deleted.path("trash").size(), "Ohne Spiel ohne Papierkorb");
        JsonNode bo3 = json.readTree(send("POST", "/api/fearless", "{\"format\":\"BO3\",\"maxGames\":7}").body()).path("active");
        assertEquals(3, bo3.path("maxGames").asInt(), "Bo3 ignoriert die Spielzahl");
    }

    @Test
    void fearlessLocksAccumulateAndLastGameCanBeUndone() throws Exception {
        java.nio.file.Files.writeString(home.resolve("fearless-series.json"), """
                {"version":1,"active":{"id":"%s","label":"Scrim","format":"BO5","startedAt":"2026-11-16T18:00:00Z",
                 "endedAt":null,"games":[{"number":1,"playedAt":"2026-11-16T18:40:00Z","patch":"16.22.1","result":"WIN",
                 "ally":[{"champion":1,"role":"TOP"},{"champion":2},{"champion":3},{"champion":4},{"champion":5}],
                 "enemy":[{"champion":6},{"champion":7},{"champion":8},{"champion":9},{"champion":10}],"bans":[11]}]},
                 "finished":[]}""".formatted(ARCHIVE_ID));

        JsonNode state = json.readTree(get("/api/fearless", true).body());
        assertEquals(10, state.path("locked").size(), "Picks beider Teams, keine Bans");
        assertEquals(1, state.path("locked").get(0).path("game").asInt());
        assertEquals(1, state.path("active").path("wins").asInt());
        assertEquals(2, state.path("active").path("nextGame").asInt());

        assertEquals(400, send("PUT", "/api/fearless/" + ARCHIVE_ID, "{\"order\":[1,1]}").statusCode());
        assertEquals(400, send("PUT", "/api/fearless/" + ARCHIVE_ID, "{\"order\":\"1\"}").statusCode());
        assertEquals(400, send("PUT", "/api/fearless/" + ARCHIVE_ID, "{}").statusCode());
        assertEquals(200, send("PUT", "/api/fearless/" + ARCHIVE_ID, "{\"order\":[1]}").statusCode());
        assertEquals("Scrim", json.readTree(get("/api/fearless", true).body()).path("active").path("label").asText(),
                "Nur die Reihenfolge, Bezeichnung bleibt");

        JsonNode undone = json.readTree(send("DELETE", "/api/fearless/game", null).body());
        assertEquals("TOP", undone.path("undone").path("ally").get(0).path("role").asText(), "Der Draft geht zurück aufs Brett");
        assertEquals(0, undone.path("locked").size());
        assertEquals(409, send("DELETE", "/api/fearless/game", null).statusCode(), "Kein Spiel mehr übrig");
    }

    @Test
    void rolesOfStoredDraftsAndSeriesGamesCanBeOverwritten() throws Exception {
        java.nio.file.Files.writeString(home.resolve("draft-archive.json"), """
                {"version":1,"entries":[{"id":"aaaaaaaa-6f3c-4d39-9a52-3d2f4f0e8a11","savedAt":"2026-11-16T17:00:00Z","patch":"16.22.1",
                 "ally":[{"champion":1},{"champion":2},{"champion":3},{"champion":4},{"champion":5}],
                 "enemy":[{"champion":6},{"champion":7},{"champion":8},{"champion":9},{"champion":10}],"bans":[11]}]}""");
        java.nio.file.Files.writeString(home.resolve("fearless-series.json"), """
                {"version":1,"active":{"id":"%s","label":null,"format":"BO3","startedAt":"2026-11-16T18:00:00Z",
                 "endedAt":null,"games":[{"number":1,"playedAt":"2026-11-16T18:40:00Z","patch":"","result":null,
                 "ally":[{"champion":1},{"champion":2},{"champion":3},{"champion":4},{"champion":5}],
                 "enemy":[{"champion":6},{"champion":7},{"champion":8},{"champion":9},{"champion":10}],"bans":[]}]},
                 "finished":[]}""".formatted(ARCHIVE_ID));
        String roles = """
                {"ally":[{"champion":2,"role":"JUNGLE"},{"champion":1},{"champion":3},{"champion":4},{"champion":5}],
                 "enemy":[{"champion":6},{"champion":7},{"champion":8},{"champion":9},{"champion":10}]}""";
        String otherChampion = roles.replace("\"champion\":10", "\"champion\":12");

        JsonNode updated = json.readTree(send("PUT", "/api/draft-archive/aaaaaaaa-6f3c-4d39-9a52-3d2f4f0e8a11", roles).body());
        assertEquals("JUNGLE", updated.path("entry").path("ally").get(0).path("role").asText(), "Reihenfolge wie auf dem Brett");
        assertEquals(2, updated.path("entry").path("ally").get(0).path("champion").asInt());
        assertEquals(1, updated.path("seriesUpdated").asInt(), "Gleiches Fearless-Spiel geht mit");
        assertEquals(1, updated.path("entries").size());
        assertEquals("2026-11-16T17:00:00Z", updated.path("entries").get(0).path("savedAt").asText());
        assertEquals(11, updated.path("entries").get(0).path("bans").get(0).asInt());
        assertEquals(400, send("PUT", "/api/draft-archive/aaaaaaaa-6f3c-4d39-9a52-3d2f4f0e8a11", otherChampion).statusCode(),
                "Andere Champions überschreiben nichts");
        assertEquals(404, send("PUT", "/api/draft-archive/bbbbbbbb-6f3c-4d39-9a52-3d2f4f0e8a11", roles).statusCode());

        JsonNode series = json.readTree(send("PUT", "/api/fearless/" + ARCHIVE_ID + "/game/1", roles).body());
        assertEquals("JUNGLE", series.path("active").path("games").get(0).path("ally").get(0).path("role").asText());
        assertEquals(0, series.path("draftsUpdated").asInt(), "Der gleiche Draft hatte diese Rollen schon");
        JsonNode coupled = json.readTree(send("PUT", "/api/fearless/" + ARCHIVE_ID + "/game/1",
                roles.replace("{\"champion\":1}", "{\"champion\":1,\"role\":\"TOP\"}")).body());
        assertEquals(1, coupled.path("draftsUpdated").asInt(), "Gleicher Draft unter „Drafts“ geht mit");
        JsonNode archived = json.readTree(get("/api/draft-archive", true).body()).path("entries").get(0);
        assertEquals("JUNGLE", archived.path("ally").get(0).path("role").asText());
        assertEquals("TOP", archived.path("ally").get(1).path("role").asText());
        assertEquals(400, send("PUT", "/api/fearless/" + ARCHIVE_ID + "/game/1", otherChampion).statusCode());
        assertEquals(400, send("PUT", "/api/fearless/" + ARCHIVE_ID + "/game/2", roles).statusCode());

        // Draft eines Spiels ersetzen (geladenes Serienspiel geändert): nur vollständige Bretter mit bekannten Champions.
        assertEquals(400, send("PUT", "/api/fearless/" + ARCHIVE_ID + "/game/1",
                "{\"replace\":true,\"ally\":[{\"champion\":103}],\"enemy\":[]}").statusCode());
        assertEquals(400, send("PUT", "/api/fearless/" + ARCHIVE_ID + "/game/1", otherChampion.replaceFirst("\\{","{\"replace\":true,")).statusCode(),
                "Unbekannter Champion");
        assertEquals(1, json.readTree(get("/api/fearless", true).body()).path("active").path("games").size(), "Nichts geändert, kein neues Spiel");
    }

    @Test
    void savingRolesOfASeriesGameRebuildsItsDraftWhenItIsGoneFromDrafts() throws Exception {
        java.nio.file.Files.writeString(home.resolve("fearless-series.json"), """
                {"version":2,"active":null,"finished":[{"id":"%s","label":null,"format":"BO3","startedAt":"2026-11-16T18:00:00Z",
                 "endedAt":"2026-11-16T19:00:00Z","games":[{"number":1,"playedAt":"2026-11-16T18:40:00Z","patch":"16.22.1","result":null,
                 "ally":[{"champion":1},{"champion":2},{"champion":3},{"champion":4},{"champion":5}],
                 "enemy":[{"champion":6},{"champion":7},{"champion":8},{"champion":9},{"champion":10}],"bans":[11]}]}]}"""
                .formatted(ARCHIVE_ID));
        String roles = """
                {"ally":[{"champion":1,"role":"TOP"},{"champion":2},{"champion":3},{"champion":4},{"champion":5}],
                 "enemy":[{"champion":6},{"champion":7},{"champion":8},{"champion":9},{"champion":10}]}""";

        JsonNode saved = json.readTree(send("PUT", "/api/fearless/" + ARCHIVE_ID + "/game/1", roles).body());
        assertTrue(saved.path("draftsRecreated").asBoolean(), "Unter „Drafts“ fehlte er ganz");
        JsonNode entry = json.readTree(get("/api/draft-archive", true).body()).path("entries").get(0);
        assertEquals("TOP", entry.path("ally").get(0).path("role").asText());
        assertEquals("2026-11-16T18:40:00Z", entry.path("savedAt").asText(), "Zeitpunkt des Spiels");
        assertEquals(11, entry.path("bans").get(0).asInt());

        assertFalse(json.readTree(send("PUT", "/api/fearless/" + ARCHIVE_ID + "/game/1", roles).body())
                .path("draftsRecreated").asBoolean(), "Jetzt vorhanden: kein zweiter Eintrag");
        send("DELETE", "/api/draft-archive/" + entry.path("id").asText(), null);
        assertFalse(json.readTree(send("PUT", "/api/fearless/" + ARCHIVE_ID + "/game/1", roles).body())
                .path("draftsRecreated").asBoolean(), "Im Papierkorb: nicht neu anlegen");
        assertEquals(0, json.readTree(get("/api/draft-archive", true).body()).path("entries").size());
    }

    @Test
    void correctedFearlessResultReopensTheSeries() throws Exception {
        java.nio.file.Files.writeString(home.resolve("fearless-series.json"), """
                {"version":1,"active":null,"finished":[{"id":"%s","label":null,"format":"BO3","startedAt":"2026-11-16T18:00:00Z",
                 "endedAt":"2026-11-16T19:00:00Z","games":[{"number":1,"playedAt":"2026-11-16T18:40:00Z","patch":"","result":"WIN",
                 "ally":[{"champion":1},{"champion":2},{"champion":3},{"champion":4},{"champion":5}],
                 "enemy":[{"champion":6},{"champion":7},{"champion":8},{"champion":9},{"champion":10}],"bans":[]},
                 {"number":2,"playedAt":"2026-11-16T19:00:00Z","patch":"","result":"WIN",
                 "ally":[{"champion":11},{"champion":12},{"champion":13},{"champion":14},{"champion":15}],
                 "enemy":[{"champion":16},{"champion":17},{"champion":18},{"champion":19},{"champion":20}],"bans":[]}]}]}"""
                .formatted(ARCHIVE_ID));

        assertEquals(400, send("PUT", "/api/fearless/" + ARCHIVE_ID + "/game/2", "{\"result\":\"DRAW\"}").statusCode());
        JsonNode changed = json.readTree(send("PUT", "/api/fearless/" + ARCHIVE_ID + "/game/2", "{\"result\":\"LOSS\"}").body());
        assertTrue(changed.path("reopened").asBoolean(), "2:0 → 1:1 im Best-of-3");
        assertEquals(ARCHIVE_ID, changed.path("active").path("id").asText());
        assertEquals(3, changed.path("active").path("nextGame").asInt(), "Ein drittes Spiel kann folgen");
        assertEquals(0, changed.path("finished").size());
        JsonNode cleared = json.readTree(send("PUT", "/api/fearless/" + ARCHIVE_ID + "/game/1", "{\"result\":null}").body());
        assertTrue(cleared.path("active").path("games").get(0).path("result").isNull());
        assertEquals(400, send("PUT", "/api/fearless/" + ARCHIVE_ID + "/game/9", "{\"result\":\"WIN\"}").statusCode(), "Spiel gibt es nicht");
    }

    @Test
    void archivedDraftsAreShownTopToSupportWithoutChangingTheFile() throws Exception {
        String stored = """
                {"version":1,"entries":[{"id":"aaaaaaaa-6f3c-4d39-9a52-3d2f4f0e8a11","savedAt":"2026-11-16T17:00:00Z","patch":"",
                 "ally":[{"champion":5,"role":"UTILITY"},{"champion":3,"role":"MIDDLE"},{"champion":1,"role":"TOP"},
                         {"champion":4,"role":"BOTTOM"},{"champion":2,"role":"JUNGLE"}],
                 "enemy":[{"champion":9,"role":"BOTTOM"},{"champion":6},{"champion":7},{"champion":8},{"champion":10}],"bans":[]}]}""";
        java.nio.file.Files.writeString(home.resolve("draft-archive.json"), stored);

        JsonNode entry = json.readTree(get("/api/draft-archive", true).body()).path("entries").get(0);
        assertEquals(List.of(1, 2, 3, 4, 5), json.convertValue(entry.path("ally").findValues("champion"), new com.fasterxml.jackson.core.type.TypeReference<List<Integer>>() { }),
                "Alle fünf fest: Top bis Support");
        assertEquals(List.of(9, 6, 7, 8, 10), json.convertValue(entry.path("enemy").findValues("champion"), new com.fasterxml.jackson.core.type.TypeReference<List<Integer>>() { }),
                "Nicht alle fest: gespeicherte Reihenfolge");
        assertEquals(stored, java.nio.file.Files.readString(home.resolve("draft-archive.json")), "Nur Anzeige");
    }

    @Test
    void archivedDraftsUseShownAutoRolesForOrderingWithoutAnyLookup() throws Exception {
        java.nio.file.Files.writeString(home.resolve("draft-archive.json"), """
                {"version":1,"entries":[{"id":"aaaaaaaa-6f3c-4d39-9a52-3d2f4f0e8a11","savedAt":"2026-11-16T17:00:00Z","patch":"",
                 "ally":[{"champion":5,"role":"UTILITY"},{"champion":3,"role":"MIDDLE"},{"champion":1,"role":"TOP"},
                         {"champion":4,"role":"BOTTOM"},{"champion":2,"role":"JUNGLE"}],
                 "enemy":[{"champion":9},{"champion":6},{"champion":7},{"champion":8},{"champion":10}],"bans":[],
                 "shownRoles":{"9":"BOTTOM","6":"TOP","7":"JUNGLE","8":"MIDDLE","10":"UTILITY"}}]}""");

        JsonNode entry = json.readTree(get("/api/draft-archive", true).body()).path("entries").get(0);
        assertEquals(List.of(6, 7, 8, 9, 10), json.convertValue(entry.path("enemy").findValues("champion"),
                new com.fasterxml.jackson.core.type.TypeReference<List<Integer>>() { }), "Gegner nach angezeigten Auto-Rollen");
        assertTrue(entry.path("enemy").get(0).path("role").isNull(), "Nur zum Ordnen, keine feste Rolle");
    }

    @Test
    void removedFearlessGameLeavesSeriesButKeepsItsDraft() throws Exception {
        java.nio.file.Files.writeString(home.resolve("fearless-series.json"), """
                {"version":2,"active":{"id":"%s","label":"Scrim","format":"BO5","startedAt":"2026-11-16T18:00:00Z",
                 "endedAt":null,"games":[{"number":1,"playedAt":"2026-11-16T18:40:00Z","patch":"","result":"WIN",
                 "ally":[{"champion":1},{"champion":2},{"champion":3},{"champion":4},{"champion":5}],
                 "enemy":[{"champion":6},{"champion":7},{"champion":8},{"champion":9},{"champion":10}],"bans":[]},
                 {"number":2,"playedAt":"2026-11-16T19:20:00Z","patch":"","result":null,
                 "ally":[{"champion":11},{"champion":12},{"champion":13},{"champion":14},{"champion":15}],
                 "enemy":[{"champion":16},{"champion":17},{"champion":18},{"champion":19},{"champion":20}],"bans":[]}]},
                 "finished":[]}""".formatted(ARCHIVE_ID));
        java.nio.file.Files.writeString(home.resolve("draft-archive.json"), """
                {"version":1,"entries":[{"id":"aaaaaaaa-6f3c-4d39-9a52-3d2f4f0e8a11","savedAt":"2026-11-16T17:00:00Z","patch":"",
                 "ally":[{"champion":1},{"champion":2},{"champion":3},{"champion":4},{"champion":5}],
                 "enemy":[{"champion":6},{"champion":7},{"champion":8},{"champion":9},{"champion":10}],"bans":[]}]}""");

        assertEquals(404, send("DELETE", "/api/fearless/" + ARCHIVE_ID + "/game/3", null).statusCode());
        assertEquals(404, send("DELETE", "/api/fearless/" + ARCHIVE_ID + "/game/x", null).statusCode());
        JsonNode removed = json.readTree(send("DELETE", "/api/fearless/" + ARCHIVE_ID + "/game/1", null).body());
        assertEquals(1, removed.path("active").path("games").size());
        assertEquals(11, removed.path("active").path("games").get(0).path("ally").get(0).path("champion").asInt(),
                "Spiel 2 rückt nach vorn");
        assertEquals(10, removed.path("locked").size(), "Nur noch die Sperren des nachgerückten Spiels");
        removed.path("locked").forEach(lock -> assertTrue(lock.path("champion").asInt() > 10, "Sperren des entfernten Spiels fallen weg"));
        assertFalse(removed.path("draftRecreated").asBoolean(), "Der Draft steht schon unter „Drafts“");
        assertEquals(0, removed.path("trash").size(), "Kein Papierkorb");
        JsonNode archive = json.readTree(get("/api/draft-archive", true).body());
        assertEquals(1, archive.path("entries").size(), "Der Draft bleibt");
        assertEquals(0, archive.path("trash").size());

        String draftId = "{\"draftId\":\"aaaaaaaa-6f3c-4d39-9a52-3d2f4f0e8a11\"}";
        JsonNode readded = json.readTree(send("POST", "/api/fearless/" + ARCHIVE_ID + "/game", draftId).body());
        assertEquals(2, readded.path("game").asInt(), "Als nächstes Spiel wieder angehängt");
        JsonNode game = readded.path("active").path("games").get(1);
        assertEquals(1, game.path("ally").get(0).path("champion").asInt());
        assertEquals("2026-11-16T17:00:00Z", game.path("playedAt").asText(), "Mit dem Speicherdatum des Drafts");
        assertTrue(game.path("result").isNull(), "Ohne Ergebnis");
        assertEquals(20, readded.path("locked").size());
        assertEquals(400, send("POST", "/api/fearless/" + ARCHIVE_ID + "/game", draftId).statusCode(), "Champions schon in der Serie");
        assertEquals(404, send("POST", "/api/fearless/" + ARCHIVE_ID + "/game", "{\"draftId\":\"unbekannt\"}").statusCode());
        assertEquals(404, send("POST", "/api/fearless/aaaaaaaa-6f3c-4d39-9a52-3d2f4f0e8a11/game", draftId).statusCode(), "Unbekannte Serie");
        assertEquals(1, json.readTree(get("/api/draft-archive", true).body()).path("entries").size(), "Kein neuer Draft");

        JsonNode recreated = json.readTree(send("DELETE", "/api/fearless/" + ARCHIVE_ID + "/game/1", null).body());
        assertTrue(recreated.path("draftRecreated").asBoolean(), "Fehlt er unter „Drafts“, wird er neu angelegt");
        assertEquals(2, json.readTree(get("/api/draft-archive", true).body()).path("entries").size());
    }

    @Test
    void deletedFearlessSeriesCanBeRestoredOrPurgedFromTrash() throws Exception {
        java.nio.file.Files.writeString(home.resolve("fearless-series.json"), """
                {"version":1,"active":null,"finished":[{"id":"%s","label":"Scrim","format":"BO3","startedAt":"2026-11-16T18:00:00Z",
                 "endedAt":"2026-11-16T19:00:00Z","games":[{"number":1,"playedAt":"2026-11-16T18:40:00Z","patch":"","result":null,
                 "ally":[{"champion":1},{"champion":2},{"champion":3},{"champion":4},{"champion":5}],
                 "enemy":[{"champion":6},{"champion":7},{"champion":8},{"champion":9},{"champion":10}],"bans":[]}]}]}"""
                .formatted(ARCHIVE_ID));

        java.nio.file.Files.writeString(home.resolve("draft-archive.json"), """
                {"version":1,"entries":[{"id":"aaaaaaaa-6f3c-4d39-9a52-3d2f4f0e8a11","savedAt":"2026-11-16T17:00:00Z","patch":"",
                 "ally":[{"champion":6},{"champion":7},{"champion":8},{"champion":9},{"champion":10}],
                 "enemy":[{"champion":1},{"champion":2},{"champion":3},{"champion":4},{"champion":5}],"bans":[]},
                 {"id":"bbbbbbbb-6f3c-4d39-9a52-3d2f4f0e8a11","savedAt":"2026-11-15T17:00:00Z","patch":"",
                 "ally":[{"champion":11},{"champion":12},{"champion":13},{"champion":14},{"champion":15}],
                 "enemy":[{"champion":16},{"champion":17},{"champion":18},{"champion":19},{"champion":20}],"bans":[]}]}""");

        JsonNode deleted = json.readTree(send("DELETE", "/api/fearless/" + ARCHIVE_ID, null).body());
        assertEquals(0, deleted.path("finished").size());
        assertEquals(1, deleted.path("draftsMoved").asInt(), "Das gleiche Spiel unter Drafts geht mit");
        JsonNode archive = json.readTree(get("/api/draft-archive", true).body());
        assertEquals(1, archive.path("entries").size());
        assertEquals("aaaaaaaa-6f3c-4d39-9a52-3d2f4f0e8a11", archive.path("trash").get(0).path("id").asText());
        assertEquals(7, deleted.path("trashDays").asInt());
        assertEquals("Scrim", deleted.path("trash").get(0).path("label").asText());
        assertTrue(deleted.path("trash").get(0).path("expiresAt").isTextual());

        assertEquals(404, send("POST", "/api/fearless/" + ARCHIVE_ID + "/foo", null).statusCode());
        JsonNode restored = json.readTree(send("POST", "/api/fearless/" + ARCHIVE_ID + "/restore", null).body());
        assertEquals(1, restored.path("finished").size());
        assertEquals(1, restored.path("draftsRestored").asInt(), "Und kommt mit zurück");
        assertEquals(2, json.readTree(get("/api/draft-archive", true).body()).path("entries").size());
        assertEquals(0, restored.path("trash").size());
        assertEquals(404, send("POST", "/api/fearless/" + ARCHIVE_ID + "/restore", null).statusCode());

        assertEquals(409, send("DELETE", "/api/fearless/" + ARCHIVE_ID + "/game/1", null).statusCode(),
                "Einziges Spiel einer beendeten Serie: die Serie löschen");
        send("DELETE", "/api/fearless/" + ARCHIVE_ID, null);
        assertEquals(200, send("DELETE", "/api/fearless/trash/" + ARCHIVE_ID, null).statusCode());
        assertEquals(404, send("DELETE", "/api/fearless/trash/" + ARCHIVE_ID, null).statusCode());
        assertEquals(0, json.readTree(send("DELETE", "/api/fearless/trash", null).body()).path("trash").size());
    }

    @Test
    void fearlessGamesAreShownTopToSupportWithoutChangingStoredRoles() throws Exception {
        java.nio.file.Files.writeString(home.resolve("fearless-series.json"), """
                {"version":1,"active":{"id":"%s","label":null,"format":"BO3","startedAt":"2026-11-16T18:00:00Z","endedAt":null,
                 "games":[{"number":1,"playedAt":"2026-11-16T18:40:00Z","patch":"","result":null,
                 "ally":[{"champion":1,"role":"UTILITY"},{"champion":2,"role":"TOP"},{"champion":3,"role":"MIDDLE"},
                         {"champion":4,"role":"BOTTOM"},{"champion":5,"role":"JUNGLE"}],
                 "enemy":[{"champion":6},{"champion":7},{"champion":8},{"champion":9},{"champion":10}],"bans":[]}]},
                 "finished":[]}""".formatted(ARCHIVE_ID));

        JsonNode state = json.readTree(get("/api/fearless", true).body());
        assertEquals("[2, 5, 3, 4, 1, 6, 7, 8, 9, 10]", state.path("locked").findValuesAsText("champion").toString(),
                "Ohne Lolalytics nach festen Rollen, Gegner ohne Rolle in bisheriger Reihenfolge");
        JsonNode ally = state.path("active").path("games").get(0).path("ally");
        assertEquals("TOP", ally.get(0).path("role").asText());
        assertTrue(java.nio.file.Files.readString(home.resolve("fearless-series.json")).indexOf("\"UTILITY\"")
                < java.nio.file.Files.readString(home.resolve("fearless-series.json")).indexOf("\"TOP\""), "Datei unverändert");
    }

    @Test
    void draftRejectsUnknownFearlessLocksBeforeAnyRequest() throws Exception {
        settings.setLolalyticsEnabled(true);
        int before = fake.requests().size();
        assertEquals(400, postDraft("{\"locked\":[999999]}").statusCode());
        assertEquals(before, fake.requests().size());
    }

    @Test
    void clientSyncIsOffByDefault() throws Exception {
        HttpResponse<String> state = get("/api/lcu", true);
        assertEquals(200, state.statusCode());
        assertTrue(state.body().contains("\"status\":\"OFF\""), state.body());

        HttpRequest invalid = HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/lcu"))
                .header("Cookie", server.sessionCookie()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"enabled\":\"ja\"}")).build();
        assertEquals(400, http.send(invalid, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(401, get("/api/lcu", false).statusCode(), "nur mit Sitzungs-Cookie");
        assertTrue(get("/api/settings", true).body().contains("\"lcuFolder\":\"\""), "Standard: automatisch suchen");
    }

    @Test
    void clientSyncSwitchIsSaved() throws Exception {
        HttpRequest.Builder post = HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/lcu"))
                .header("Cookie", server.sessionCookie()).header("Content-Type", "application/json");
        HttpResponse<String> on = http.send(post.POST(HttpRequest.BodyPublishers.ofString("{\"enabled\":true}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, on.statusCode());
        assertFalse(on.body().contains("\"status\":\"OFF\""), on.body());
        assertTrue(AppSettings.load(settings.home()).lcuSyncEnabled(), "Der nächste Start übernimmt den Schalter");

        http.send(post.POST(HttpRequest.BodyPublishers.ofString("{\"enabled\":false}")).build(), HttpResponse.BodyHandlers.ofString());
        assertFalse(AppSettings.load(settings.home()).lcuSyncEnabled());
        assertTrue(get("/api/lcu", true).body().contains("\"status\":\"OFF\""));
    }

    @Test
    void leagueFolderMustContainTheClient() throws Exception {
        HttpRequest save = HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/settings"))
                .header("Cookie", server.sessionCookie()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"lcuFolder\":\"C:/gibt/es/nicht\"}")).build();
        HttpResponse<String> response = http.send(save, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("LeagueClient.exe"), response.body());
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
