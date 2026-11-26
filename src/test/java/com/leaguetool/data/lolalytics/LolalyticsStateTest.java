package com.leaguetool.data.lolalytics;

import com.leaguetool.cache.FileCache;
import com.leaguetool.testutil.FakeHttpFetcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LolalyticsStateTest {

    private static final String BASE = "https://lolalytics.com/lol/tierlist/?tier=emerald_plus&lane=all";

    @TempDir
    Path tmp;

    @Test
    void tierListContainsEveryChampionWithTierLabels() throws IOException {
        LolalyticsFullTierList list = LolalyticsState.parseTierList(LolalyticsState.extractTierList(LolalyticsPages.tierList()));
        assertEquals("16.22", list.patch());
        assertEquals(57_802_290L, list.analysed());
        assertEquals(0.5173, list.avgWinRate(), 1e-9);
        assertEquals(3, list.rows().size());
        assertEquals("D-", list.rows().get(1).tier());
        assertEquals(-50, list.rows().get(1).pbi(), "PBI kann negativ sein");
        assertEquals(14, list.rows().get(103).pbi());
        assertEquals(0.9515, list.rows().get(103).laneShare(), 1e-9);
        LolalyticsFullTierList.Row ahri = list.rows().get(103);
        assertEquals("S", ahri.tier());
        assertEquals(2, ahri.tierRank());
        assertEquals("middle", ahri.lane());
        assertEquals(0.5311, ahri.winRate(), 1e-9);
        assertEquals(0.1003, ahri.pickRate(), 1e-9);
        assertEquals(0.0324, ahri.banRate(), 1e-9);
        assertEquals(579_664, ahri.games());
        assertEquals("ahri", list.slugs().get(103));
    }

    @Test
    void championWithoutTierHasEmptyLabel() throws IOException {
        LolalyticsFullTierList list = LolalyticsState.parseTierList(LolalyticsState.extractTierList(LolalyticsPages.tierList()));
        assertEquals("", list.rows().get(222).tier());
        assertEquals(0, list.rows().get(222).tierRank());
    }

    @Test
    void buildSectionsAreParsed() throws IOException {
        LolalyticsBuild build = LolalyticsState.parseBuild(LolalyticsState.extractBuild(LolalyticsPages.build()));
        assertEquals(579_664, build.games());
        assertEquals(0.5311, build.winRate(), 1e-9);
        assertEquals("1056_2003_2003", build.starters().get(0).key());
        assertEquals(1, build.starters().size(), "leerer Schlüssel fällt weg");
        assertEquals("3118_3020_4645", build.coreBuilds().get(0).key());
        assertEquals(102_489, build.coreBuilds().get(0).games());
        assertEquals(0.1768, build.coreBuilds().get(0).pickRate(), 1e-9);
        assertEquals("QWE", build.skillOrders().get(0).key());
        assertEquals("213114", build.skillStarts().get(0).key());
        assertEquals("213114131343322", build.skillPaths().get(0).key(), "komplette Skillung bis Level 15");
        assertEquals(27, build.itemSlots().stream().filter(s -> s.slot() == 3).findFirst().orElseThrow().entries().get(0)
                .minute(), "5. Spalte der Itemplätze ist die Kaufminute");
        assertEquals("4_14", build.spells().get(0).key());
        assertEquals(0.53, build.runes().get(8112).winRate(), 1e-9);
        assertEquals(499_515, build.runes().get(8112).games());
        assertEquals(2, build.runes().size(), "Shard-Varianten wie 5008f sind keine Runen-IDs");
        assertEquals(0.54, build.subRunes().get(8226).winRate(), 1e-9, "zweiter Eintrag gilt im Sekundärbaum");
        assertEquals(238_800, build.subRunes().get(8226).games());
        assertFalse(build.subRunes().containsKey(8112), "Keystones haben keinen Eintrag im Sekundärbaum");
        assertEquals(34_500, build.shardRow(1).get(5008).games(), "5008f ist der Flex-Shard 5008");
        assertEquals(41_000, build.shardRow(0).get(5005).games(), "5005 ohne Endung: Offensive");
        assertEquals(22_000, build.shardRow(2).get(5013).games(), "5013 ohne Endung: Defensive");
        assertFalse(build.runes().containsKey(5005), "Shards sind keine Runen");
        assertTrue(build.runePages().get(0).mostPlayed());
        assertEquals(List.of(8112, 8139), build.runePages().get(0).primary());
        assertEquals(List.of(5005, 5008, 5001), build.runePages().get(0).shards());
    }

    @Test
    void buildContainsMatchupsDamageAndGameLength() throws IOException {
        LolalyticsBuild build = LolalyticsState.parseBuild(LolalyticsState.extractBuild(LolalyticsPages.build()));
        LolalyticsPairs.Stat matchup = build.matchups().byLane().get("top").get(266);
        assertEquals(0.5736, matchup.winRate(), 1e-9);
        assertEquals(1630, matchup.games());
        assertEquals(0.0888, matchup.delta1(), 1e-9);
        assertEquals(0.0316, matchup.delta2(), 1e-9);
        assertEquals(17893.03, build.damage().magic(), 1e-9);
        assertEquals(5325.13, build.damage().trueDamage(), 1e-9);
        assertEquals(List.of(new LolalyticsBuild.LengthBucket(200, 400), new LolalyticsBuild.LengthBucket(360, 600)),
                build.gameLength());
    }

    @Test
    void itemSlotsOneToFiveAreParsed() throws IOException {
        List<LolalyticsBuild.ItemSlot> slots = LolalyticsState.parseBuild(LolalyticsState.extractBuild(LolalyticsPages.build())).itemSlots();
        assertEquals(List.of(1, 2, 3, 4, 5), slots.stream().map(LolalyticsBuild.ItemSlot::slot).toList());
        assertEquals("3118", slots.get(0).entries().get(0).key());
        assertEquals(347_798, slots.get(0).entries().get(0).games());
        assertTrue(slots.get(1).entries().isEmpty(), "fehlender Itemplatz bleibt leer");
        LolalyticsBuild.Entry third = slots.get(2).entries().get(0);
        assertEquals("3157", third.key());
        assertEquals(0.5667, third.winRate(), 1e-9);
        assertEquals(0.2048, third.pickRate(), 1e-9);
        assertEquals(119_752, third.games());
    }

    @Test
    void missingItemSlotListsKeepTheBuildUsable() throws IOException {
        String page = LolalyticsPages.build().replace("\"item1\"", "\"x1\"").replace("\"item3\"", "\"x3\"").replace("\"item4\"", "\"x4\"").replace("\"item5\"", "\"x5\"");
        LolalyticsBuild build = LolalyticsState.parseBuild(LolalyticsState.extractBuild(page));
        assertEquals(579_664, build.games());
        assertTrue(build.itemSlots().stream().allMatch(slot -> slot.entries().isEmpty()));
    }

    @Test
    void missingDeltaColumnsKeepTheBuildUsable() throws IOException {
        String page = LolalyticsPages.build().replace("\"d1\"", "\"x1\"").replace("\"d2\"", "\"x2\"");
        LolalyticsPairs.Stat matchup = LolalyticsState.parseBuild(LolalyticsState.extractBuild(page))
                .matchups().byLane().get("top").get(266);
        assertEquals(0.5736, matchup.winRate(), 1e-9);
        assertEquals(1630, matchup.games());
        assertNull(matchup.delta1());
        assertNull(matchup.delta2());
    }

    @Test
    void teamValuesStayWithoutDeltas() throws IOException {
        LolalyticsPairs.Stat teammate = LolalyticsState.parseTeam(LolalyticsState.extractTeam(LolalyticsPages.team(777, 52.69, 10_694)))
                .byLane().get("top").get(777);
        assertEquals(0.5269, teammate.winRate(), 1e-9);
        assertNull(teammate.delta1());
        assertNull(teammate.delta2());
    }

    @Test
    void teamResponseIsParsedAndCachedThroughTheThrottledClient() throws IOException {
        String url = "https://a1.lolalytics.com/mega/?ep=build-team&v=1&patch=30&c=ahri&lane=middle&tier=diamond_plus"
                + "&queue=ranked&region=all";
        FakeHttpFetcher http = new FakeHttpFetcher().respond(url, LolalyticsPages.team(777, 52.69, 10_694));
        LolalyticsClient client = new LolalyticsClient(http, new FileCache(tmp));

        LolalyticsPairs team = client.teammates("ahri", "diamond_plus", "middle");
        assertEquals(0.5269, team.byLane().get("top").get(777).winRate(), 1e-9);
        assertEquals(10_694, team.byLane().get("top").get(777).games());

        client.teammates("ahri", "diamond_plus", "middle");
        assertEquals(1, http.requests().size(), "zweiter Aufruf kommt aus dem Cache");
    }

    @Test
    void changedTeamResponseFailsClearlyAndIsNotCached() {
        assertTrue(assertThrows(IOException.class, () -> LolalyticsState.extractTeam("<html>blockiert</html>"))
                .getMessage().contains("kein JSON"));
        assertTrue(assertThrows(IOException.class, () -> LolalyticsState.extractTeam("{\"error\":1}"))
                .getMessage().contains("Team-Daten"));
        assertTrue(assertThrows(IOException.class, () -> LolalyticsState.extractTeam(
                "{\"team_h\":[\"id\",\"wins\"],\"team\":{\"top\":[[1,2]]}}")).getMessage().contains("Spalte"));
    }

    @Test
    void pageWithoutStateFailsClearly() {
        IOException e = assertThrows(IOException.class, () -> LolalyticsState.extractTierList("<html></html>"));
        assertTrue(e.getMessage().contains("Seitenzustand"));
    }

    @Test
    void periodSelectsUrlAndSeparateCacheEntries() throws IOException {
        FakeHttpFetcher http = new FakeHttpFetcher()
                .respond(BASE + "&patch=30", LolalyticsPages.tierList())
                .respond(BASE, LolalyticsPages.tierList());
        LolalyticsClient client = new LolalyticsClient(http, new FileCache(tmp));

        client.fullTierList("emerald_plus", "all", LolalyticsPeriod.LAST_30_DAYS);
        assertEquals(List.of(BASE + "&patch=30"), http.requests());

        client.fullTierList("emerald_plus", "all", LolalyticsPeriod.LAST_30_DAYS);
        assertEquals(1, http.requests().size(), "zweiter Aufruf kommt aus dem Cache");

        client.fullTierList("emerald_plus", "all", LolalyticsPeriod.CURRENT_PATCH);
        assertEquals(List.of(BASE + "&patch=30", BASE), http.requests());
    }

    @Test
    void unusableResponseIsNotCached() throws IOException {
        FileCache cache = new FileCache(tmp);
        FakeHttpFetcher http = new FakeHttpFetcher().respond(BASE + "&patch=30", "<html>blockiert</html>");
        LolalyticsClient client = new LolalyticsClient(http, cache);
        assertThrows(IOException.class, () -> client.fullTierList("emerald_plus", "all", LolalyticsPeriod.LAST_30_DAYS));
        assertTrue(!cache.contains(FileCache.GLOBAL, "lolalytics/full_v4_emerald_plus_all_30d.json"));
    }

    @Test
    void tierNamesMayContainDigits() throws IOException {
        String url = "https://lolalytics.com/lol/tierlist/?tier=d2_plus&lane=top&patch=30";
        FakeHttpFetcher http = new FakeHttpFetcher().respond(url, LolalyticsPages.tierList());
        new LolalyticsClient(http, new FileCache(tmp)).fullTierList("d2_plus", "top", LolalyticsPeriod.LAST_30_DAYS);
        assertEquals(List.of(url), http.requests());
    }

    @Test
    void tierNamesWithOtherCharactersAreRejected() {
        LolalyticsClient client = new LolalyticsClient(new FakeHttpFetcher(), new FileCache(tmp));
        assertThrows(IllegalArgumentException.class, () -> client.fullTierList("d2&x=1", "top", LolalyticsPeriod.LAST_30_DAYS));
    }

    @Test
    void invalidSlugIsRejected() {
        LolalyticsClient client = new LolalyticsClient(new FakeHttpFetcher(), new FileCache(tmp));
        assertThrows(IllegalArgumentException.class,
                () -> client.build("../evil", "emerald_plus", null, LolalyticsPeriod.LAST_30_DAYS));
    }
}
