package com.leaguetool.data.ddragon;

import com.leaguetool.cache.FileCache;
import com.leaguetool.data.GameData;
import com.leaguetool.data.GameDataService;
import com.leaguetool.testutil.FakeHttpFetcher;
import com.leaguetool.testutil.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DDragonClientTest {

    private static final String BASE = "https://dd.test";
    private static final String PATCH = "16.22.1";

    @TempDir
    Path tmp;

    private FakeHttpFetcher http;
    private FileCache cache;
    private DDragonClient client;

    @BeforeEach
    void setUp() {
        http = new FakeHttpFetcher();
        String data = BASE + "/cdn/" + PATCH + "/data/en_US/";
        http.respond(BASE + "/api/versions.json", Fixtures.read("ddragon/versions.json"))
                .respond(data + "champion.json", Fixtures.read("ddragon/champion.json"))
                .respond(data + "champion/Ahri.json", Fixtures.read("ddragon/champion-Ahri.json"))
                .respond(data + "item.json", Fixtures.read("ddragon/item.json"))
                .respond(data + "runesReforged.json", Fixtures.read("ddragon/runesReforged.json"))
                .respond(data + "summoner.json", Fixtures.read("ddragon/summoner.json"));
        cache = new FileCache(tmp.resolve("cache"));
        client = new DDragonClient(http, cache, "en_US", BASE);
    }

    @Test
    void latestPatchIsFirstVersion() throws IOException {
        assertEquals(PATCH, client.latestPatch());
        assertFalse(client.isOffline());
    }

    @Test
    void patchDataIsCachedAndReused() throws IOException {
        assertEquals(3, client.champions(PATCH).size());
        assertTrue(cache.contains(PATCH, "data/en_US/champion.json"));
        long before = http.requests().size();
        assertEquals(3, client.champions(PATCH).size());
        assertEquals(before, http.requests().size(), "zweiter Aufruf kommt aus dem Cache");
    }

    @Test
    void worksOfflineWithCache() throws IOException {
        GameDataService service = new GameDataService(client);
        GameData online = service.loadLatest();
        assertFalse(online.offline());
        assertEquals(103, online.championById("Ahri").orElseThrow().key());
        service.championDetail("Ahri");

        http.setOffline(true);
        DDragonClient offlineClient = new DDragonClient(http, cache, "en_US", BASE);
        // versions.json ist noch frisch → kein Netz nötig; danach veraltet erzwingen:
        cache.writeText(FileCache.GLOBAL, "ddragon/versions.json", Fixtures.read("ddragon/versions.json"));
        assertTrue(cache.file(FileCache.GLOBAL, "ddragon/versions.json").orElseThrow().toFile()
                .setLastModified(System.currentTimeMillis() - 7L * 24 * 3600 * 1000));

        GameDataService offlineService = new GameDataService(offlineClient);
        GameData data = offlineService.loadLatest();
        assertEquals(PATCH, data.patch());
        assertTrue(data.offline());
        assertEquals(11, data.items().size());
        assertEquals("Orb of Deception", offlineService.championDetail("Ahri").abilities().get(1).name());
    }

    @Test
    void offlineWithoutVersionsUsesNewestCachedPatch() throws IOException {
        client.champions(PATCH);
        client.items(PATCH);
        client.runeTrees(PATCH);
        client.summonerSpells(PATCH);
        http.setOffline(true);
        FileCache emptyVersionsCache = new FileCache(tmp.resolve("cache"));
        // versions.json nie geladen (nur Patch-Daten im Cache)
        DDragonClient offline = new DDragonClient(http, emptyVersionsCache, "en_US", BASE);
        assertEquals(PATCH, offline.latestPatch());
        assertTrue(offline.isOffline());
    }

    @Test
    void invalidResponsesAreNotCachedAndCorruptCacheIsReplaced() throws IOException {
        String url = BASE + "/cdn/" + PATCH + "/data/en_US/champion.json";
        http.respond(url, "<html>Captive Portal</html>");
        assertThrows(IOException.class, () -> client.champions(PATCH));
        assertFalse(cache.contains(PATCH, "data/en_US/champion.json"), "ungültige Antwort wird nicht gecacht");

        cache.writeText(PATCH, "data/en_US/champion.json", "{\"data\": {\"Ah");   // abgeschnitten
        http.respond(url, Fixtures.read("ddragon/champion.json"));
        assertEquals(3, client.champions(PATCH).size(), "kaputter Cache-Eintrag wird neu geladen");
        assertEquals(3, new DDragonParser().parseChampions(
                cache.readText(PATCH, "data/en_US/champion.json").orElseThrow()).size());
    }

    @Test
    void championDetailCacheIsPerPatch() throws IOException {
        GameDataService service = new GameDataService(client);
        service.loadLatest();
        assertEquals("Ahri", service.championDetail(PATCH, "Ahri").champion().id());
        assertThrows(IOException.class, () -> service.championDetail("16.21.1", "Ahri"),
                "anderer Patch wird nicht aus dem Speicher-Cache bedient");
    }

    @Test
    void failsCleanlyWithoutNetworkAndCache() {
        http.setOffline(true);
        assertThrows(IOException.class, () -> new GameDataService(client).loadLatest());
    }

    @Test
    void rejectsUnsafeIds() {
        assertThrows(IllegalArgumentException.class, () -> client.championDetail(PATCH, "../secret"));
        assertThrows(IllegalArgumentException.class, () -> client.champions("../../x"));
        assertThrows(IllegalArgumentException.class, () -> new DDragonClient(http, cache, "../en", BASE));
    }
}
