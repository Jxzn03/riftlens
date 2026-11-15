package com.leaguetool.data.wiki;

import com.leaguetool.cache.FileCache;
import com.leaguetool.model.AbilitySlot;
import com.leaguetool.testutil.FakeHttpFetcher;
import com.leaguetool.testutil.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikiClientTest {

    private static final String API = "https://wiki.test/api.php";
    private static final String QUERY = API + "?action=query&prop=revisions&rvprop=content&rvslots=main"
            + "&format=json&formatversion=2&redirects=1&titles=";
    private static final String CHAMPION_URL = QUERY + "Module%3AChampionData%2Fdata";
    private static final String AHRI_URL = QUERY
            + "Template%3AData_Ahri%2FEssence%20Theft%7CTemplate%3AData_Ahri%2FOrb%20of%20Deception"
            + "%7CTemplate%3AData_Ahri%2FFox-Fire%7CTemplate%3AData_Ahri%2FCharm%7CTemplate%3AData_Ahri%2FSpirit%20Rush";
    private static final String AATROX_URL = QUERY
            + "Template%3AData_Aatrox%2FDeathbringer%20Stance%7CTemplate%3AData_Aatrox%2FThe%20Darkin%20Blade"
            + "%7CTemplate%3AData_Aatrox%2FThe%20Darkin%20Blade%202%7CTemplate%3AData_Aatrox%2FThe%20Darkin%20Blade%203"
            + "%7CTemplate%3AData_Aatrox%2FInfernal%20Chains%7CTemplate%3AData_Aatrox%2FUmbral%20Dash"
            + "%7CTemplate%3AData_Aatrox%2FWorld%20Ender";
    private static final String WUKONG_URL = QUERY
            + "Template%3AData_Wukong%2FStone%20Skin%7CTemplate%3AData_Wukong%2FCrushing%20Blow"
            + "%7CTemplate%3AData_Wukong%2FWarrior%20Trickster%7CTemplate%3AData_Wukong%2FNimbus%20Strike"
            + "%7CTemplate%3AData_Wukong%2FCyclone";

    @TempDir
    Path tmp;

    private FileCache cache;
    private FakeHttpFetcher http;

    @BeforeEach
    void setUp() {
        cache = new FileCache(tmp);
        http = new FakeHttpFetcher()
                .respond(CHAMPION_URL, Fixtures.read("wiki/championdata.json"))
                .respond(AHRI_URL, Fixtures.read("wiki/abilities-Ahri.json"))
                .respond(AATROX_URL, Fixtures.read("wiki/abilities-Aatrox.json"));
    }

    private WikiClient client() {
        return new WikiClient(http, cache, API);
    }

    @Test
    void buildsEncodedQueryUrls() {
        WikiClient c = client();
        assertEquals(CHAMPION_URL, c.queryUrl(List.of(WikiClient.CHAMPION_DATA_TITLE)));
        assertEquals(QUERY + "Template%3AData_Kai%27Sa%2FIcathian%20Rain%7CTemplate%3AData_Nunu%20%26%20Willump%2FX",
                c.queryUrl(List.of("Template:Data_Kai'Sa/Icathian Rain", "Template:Data_Nunu & Willump/X")));
        assertEquals("Template:Data_Bel'Veth/Death in Lavender",
                WikiClient.abilityPageTitle("Bel'Veth", "Death in Lavender"));
    }

    @Test
    void championDataIsKeyedByDataDragonIdAndCached() throws IOException {
        WikiClient c = client();
        Map<String, WikiChampionData> data = c.championData();
        assertEquals(List.of("Aatrox", "Ahri", "Kaisa", "Kled", "MonkeyKing"), List.copyOf(data.keySet()));
        assertEquals("Wukong", c.champion("MonkeyKing").orElseThrow().wikiName());
        assertTrue(c.champion("Unbekannt").isEmpty());
        assertSame(data, c.championData());
        assertEquals(List.of(CHAMPION_URL), http.requests());
        assertFalse(c.isStale());
        assertTrue(cache.contains(FileCache.GLOBAL, "wiki/championdata.json"));

        // neue Instanz: frischer Datei-Cache, kein Netz nötig
        FakeHttpFetcher offline = new FakeHttpFetcher();
        offline.setOffline(true);
        WikiClient second = new WikiClient(offline, cache, API);
        assertEquals(590, second.champion("Ahri").orElseThrow().hpBase());
        assertTrue(offline.requests().isEmpty());
    }

    @Test
    void staleCacheIsUsedWhenOffline() throws IOException {
        cache.writeText(FileCache.GLOBAL, "wiki/championdata.json", Fixtures.read("wiki/championdata.json"));
        makeOld("wiki/championdata.json");
        http.setOffline(true);

        WikiClient c = client();
        assertEquals(103, c.champion("Ahri").orElseThrow().id());
        assertTrue(c.isStale());
        assertEquals(List.of(CHAMPION_URL), http.requests());
    }

    @Test
    void staleCacheIsRefreshedWhenOnline() throws IOException {
        cache.writeText(FileCache.GLOBAL, "wiki/championdata.json", """
                {"query":{"pages":[{"title":"Module:ChampionData/data","revisions":[{"slots":{"main":{
                 "content":"return { [\\"Alt\\"] = { id = 1, apiname = \\"Alt\\", stats = { hp_base = 1 } } }"}}}]}]}}""");
        makeOld("wiki/championdata.json");
        FakeHttpFetcher offline = new FakeHttpFetcher();
        offline.setOffline(true);
        assertEquals(List.of("Alt"), List.copyOf(new WikiClient(offline, cache, API).championData().keySet()));

        WikiClient c = client();
        assertEquals(5, c.championData().size());
        assertFalse(c.championData().containsKey("Alt"));
        assertFalse(c.isStale());
        assertEquals(Fixtures.read("wiki/championdata.json"),
                cache.readText(FileCache.GLOBAL, "wiki/championdata.json").orElseThrow());
    }

    @Test
    void errorResponseFallsBackToStaleCacheOrFails() throws IOException {
        FakeHttpFetcher failing = new FakeHttpFetcher()
                .respond(CHAMPION_URL, "{\"error\":{\"code\":\"maxlag\",\"info\":\"Waiting for a database server\"}}");
        IOException e = assertThrows(IOException.class, () -> new WikiClient(failing, cache, API).championData());
        assertTrue(e.getMessage().contains("Waiting for a database server"), e.getMessage());
        assertFalse(cache.contains(FileCache.GLOBAL, "wiki/championdata.json"));

        cache.writeText(FileCache.GLOBAL, "wiki/championdata.json", Fixtures.read("wiki/championdata.json"));
        makeOld("wiki/championdata.json");
        WikiClient c = new WikiClient(failing, cache, API);
        assertEquals(5, c.championData().size());
        assertTrue(c.isStale());
    }

    @Test
    void offlineWithoutCacheThrows() {
        http.setOffline(true);
        assertThrows(IOException.class, () -> client().championData());
        assertThrows(IOException.class, () -> client().abilities("Ahri"));
    }

    @Test
    void abilitiesAreFetchedInOneQueryInSlotOrder() throws IOException {
        WikiClient c = client();
        List<WikiAbility> abilities = c.abilities("Ahri");
        assertEquals(List.of(AbilitySlot.P, AbilitySlot.Q, AbilitySlot.W, AbilitySlot.E, AbilitySlot.R),
                abilities.stream().map(WikiAbility::slot).toList());
        assertEquals(List.of("Essence Theft", "Orb of Deception", "Fox-Fire", "Charm", "Spirit Rush"),
                abilities.stream().map(WikiAbility::name).toList());
        assertEquals(List.of(35.0, 60.0, 85.0, 110.0, 135.0), abilities.get(1).levelings().get(0).valuesPerRank());
        assertEquals(List.of(CHAMPION_URL, AHRI_URL), http.requests());
        assertTrue(cache.contains(FileCache.GLOBAL, "wiki/abilities/Ahri.json"));

        assertSame(abilities, c.abilities("Ahri"));
        assertEquals(2, http.requests().size());

        // neue Instanz liest aus dem Datei-Cache
        FakeHttpFetcher offline = new FakeHttpFetcher();
        offline.setOffline(true);
        assertEquals(5, new WikiClient(offline, cache, API).abilities("Ahri").size());
        assertTrue(offline.requests().isEmpty());
    }

    @Test
    void redirectedFormsAreReturnedOnce() throws IOException {
        List<WikiAbility> abilities = client().abilities("Aatrox");
        assertEquals(List.of("Deathbringer Stance", "The Darkin Blade", "Infernal Chains", "Umbral Dash", "World Ender"),
                abilities.stream().map(WikiAbility::name).toList());
        WikiAbility q = abilities.get(1);
        assertEquals(AbilitySlot.Q, q.slot());
        assertEquals(8, q.levelings().size());
    }

    @Test
    void missingAndBrokenPagesAreSkipped() throws IOException {
        http.respond(WUKONG_URL, WUKONG_RESPONSE);
        List<WikiAbility> abilities = client().abilities("MonkeyKing");
        assertEquals(List.of("Stone Skin", "Nimbus Strike"), abilities.stream().map(WikiAbility::name).toList());
        assertEquals(List.of(AbilitySlot.P, AbilitySlot.E), abilities.stream().map(WikiAbility::slot).toList());
        assertEquals(List.of(10.0, 9.0, 8.0, 7.0, 6.0), abilities.get(1).cooldownValues());
    }

    @Test
    void freshButUnreadableCacheEntriesAreReloaded() throws IOException {
        cache.writeText(FileCache.GLOBAL, "wiki/championdata.json", "kaputt");
        cache.writeText(FileCache.GLOBAL, "wiki/abilities/Ahri.json", "{\"query\":{}}");
        WikiClient c = client();
        assertEquals(5, c.championData().size());
        assertEquals(5, c.abilities("Ahri").size());
        assertFalse(c.isStale());
        assertEquals(List.of(CHAMPION_URL, AHRI_URL), http.requests());
        assertEquals(Fixtures.read("wiki/championdata.json"),
                cache.readText(FileCache.GLOBAL, "wiki/championdata.json").orElseThrow());
        assertTrue(cache.readText(FileCache.GLOBAL, "wiki/abilities/Ahri.json").orElseThrow()
                .contains("Orb of Deception"));

        // ohne Netz bleibt ein unlesbarer Eintrag ein Fehler
        cache.writeText(FileCache.GLOBAL, "wiki/championdata.json", "kaputt");
        FakeHttpFetcher offline = new FakeHttpFetcher();
        offline.setOffline(true);
        assertThrows(IOException.class, () -> new WikiClient(offline, cache, API).championData());
    }

    @Test
    void cachedAbilitiesWithoutRequestedTitlesAreReloaded() throws IOException {
        client().championData(); // Championdaten in den Datei-Cache
        // frischer Eintrag von vor einer Umbenennung: enthält nur eine der fünf angefragten Seiten
        cache.writeText(FileCache.GLOBAL, "wiki/abilities/MonkeyKing.json", """
                {"query":{"pages":[{"pageid":3,"ns":10,"title":"Template:Data Wukong/Stone Skin","revisions":[{"slots":{
                 "main":{"content":"{{X|Stone Skin\\n|champion = Wukong\\n|skill = I\\n}}"}}}]}]}}""");

        FakeHttpFetcher offline = new FakeHttpFetcher();
        offline.setOffline(true);
        WikiClient offlineClient = new WikiClient(offline, cache, API);
        assertEquals(List.of("Stone Skin"), offlineClient.abilities("MonkeyKing").stream().map(WikiAbility::name).toList());
        assertTrue(offlineClient.isStale());
        assertEquals(List.of(WUKONG_URL), offline.requests());

        http.respond(WUKONG_URL, WUKONG_RESPONSE);
        WikiClient online = client();
        assertEquals(List.of("Stone Skin", "Nimbus Strike"),
                online.abilities("MonkeyKing").stream().map(WikiAbility::name).toList());
        assertFalse(online.isStale());
        assertTrue(cache.readText(FileCache.GLOBAL, "wiki/abilities/MonkeyKing.json").orElseThrow()
                .contains("Nimbus Strike"));
    }

    /** Antwort für Wukong: zwei fehlende Seiten, eine ohne Template, zwei gültige. */
    private static final String WUKONG_RESPONSE = """
                {"batchcomplete":true,"query":{
                 "normalized":[
                  {"fromencoded":false,"from":"Template:Data_Wukong/Stone Skin","to":"Template:Data Wukong/Stone Skin"},
                  {"fromencoded":false,"from":"Template:Data_Wukong/Crushing Blow","to":"Template:Data Wukong/Crushing Blow"},
                  {"fromencoded":false,"from":"Template:Data_Wukong/Warrior Trickster","to":"Template:Data Wukong/Warrior Trickster"},
                  {"fromencoded":false,"from":"Template:Data_Wukong/Nimbus Strike","to":"Template:Data Wukong/Nimbus Strike"},
                  {"fromencoded":false,"from":"Template:Data_Wukong/Cyclone","to":"Template:Data Wukong/Cyclone"}],
                 "pages":[
                  {"ns":10,"title":"Template:Data Wukong/Crushing Blow","missing":true},
                  {"ns":10,"title":"Template:Data Wukong/Cyclone","missing":true},
                  {"pageid":1,"ns":10,"title":"Template:Data Wukong/Nimbus Strike","revisions":[{"slots":{"main":{
                   "content":"{{{{{1<noinclude>|Ability data</noinclude>}}}|Nimbus Strike|{{{2|}}}\\n|champion = Wukong\\n|skill = E\\n|cooldown = {{ap|10 to 6}}\\n}}"}}}]},
                  {"pageid":2,"ns":10,"title":"Template:Data Wukong/Warrior Trickster","revisions":[{"slots":{"main":{
                   "content":"kein Template"}}}]},
                  {"pageid":3,"ns":10,"title":"Template:Data Wukong/Stone Skin","revisions":[{"slots":{"main":{
                   "content":"{{{{{1<noinclude>|Ability data</noinclude>}}}|Stone Skin|{{{2|}}}\\n|champion = Wukong\\n|skill = I\\n}}"}}}]}
                 ]}}
                """;

    @Test
    void errorForOneChampionDoesNotAffectOthers() throws IOException {
        FakeHttpFetcher partial = new FakeHttpFetcher()
                .respond(CHAMPION_URL, Fixtures.read("wiki/championdata.json"))
                .respond(AHRI_URL, Fixtures.read("wiki/abilities-Ahri.json"));
        WikiClient c = new WikiClient(partial, cache, API);
        assertThrows(IOException.class, () -> c.abilities("Aatrox")); // 404 ohne Cache
        assertEquals(5, c.abilities("Ahri").size());
        assertFalse(cache.contains(FileCache.GLOBAL, "wiki/abilities/Aatrox.json"));
    }

    @Test
    void unknownOrInvalidChampion() throws IOException {
        WikiClient c = client();
        assertTrue(c.abilities("Unbekannt").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> c.abilities("../x"));
        assertThrows(IllegalArgumentException.class, () -> c.abilities(null));
    }

    private void makeOld(String key) throws IOException {
        Path file = cache.pathFor(FileCache.GLOBAL, key);
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofDays(5))));
    }
}
