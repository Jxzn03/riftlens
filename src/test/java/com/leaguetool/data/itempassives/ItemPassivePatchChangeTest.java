package com.leaguetool.data.itempassives;

import com.leaguetool.data.UpdateReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leaguetool.cache.FileCache;
import com.leaguetool.calc.BuildCalculator;
import com.leaguetool.calc.ItemPassive;
import com.leaguetool.calc.ItemPassives;
import com.leaguetool.calc.StatType;
import com.leaguetool.data.GameData;
import com.leaguetool.data.wiki.WikiClient;
import com.leaguetool.model.Item;
import com.leaguetool.model.ItemGold;
import com.leaguetool.testutil.FakeHttpFetcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Simuliert Patchwechsel von der Wiki-Antwort bis zum Einsatz im Build-Rechner. */
class ItemPassivePatchChangeTest {

    private static final Item STERAK = new Item(3053, "Sterak's Gage", "",
            "<mainText><stats>400 Health</stats><br><br><passive>The Claws that Catch</passive><br>Gain bonus Attack Damage.</mainText>",
            new ItemGold(3200, 3200, 2240, true), List.of(), Map.of(), List.of(), List.of(), Set.of(Item.SUMMONERS_RIFT),
            true, 3, "", false, "", 0);

    @TempDir
    Path dir;

    private final FakeHttpFetcher http = new FakeHttpFetcher();
    private WikiClient wiki;
    private String url;

    @BeforeEach
    void setUp() {
        wiki = new WikiClient(http, new FileCache(dir));
        url = wiki.queryUrl(List.of("Module:ItemData/data"));
    }

    @AfterEach
    void restoreBaseline() {
        ItemPassives.install(ItemPassives.baseline());
    }

    private void wikiSays(int percent) throws IOException {
        String lua = "return { [\"Sterak's Gage\"] = { [\"id\"] = 3053, [\"effects\"] = { [\"pass\"] = { "
                + "[\"name\"] = \"The Claws that Catch\", "
                + "[\"description\"] = \"Gain bonus attack damage equal to " + percent + "% base AD.\" } } } }";
        http.respond(url, new ObjectMapper().writeValueAsString(Map.of("query", Map.of("pages",
                List.of(Map.of("title", "Module:ItemData/data", "revisions", List.of(Map.of("slots",
                        Map.of("main", Map.of("content", lua))))))))));
    }

    private static GameData patch(String version) {
        return new GameData(version, false, List.of(), List.of(STERAK), List.of(), List.of());
    }

    private static double steraksRatio() {
        return BuildCalculator.totals(List.of(STERAK)).stats().get(StatType.AD_PER_BASE_AD);
    }

    @Test
    void newPatchFetchesTheWikiAgainAndAppliesTheChangeAutomatically() throws IOException {
        ItemPassiveUpdater updater = new ItemPassiveUpdater(new FileCache(dir));
        wikiSays(50);
        updater.update(patch("16.22.1"), wiki);
        assertEquals(50, steraksRatio());

        wikiSays(40);
        UpdateReport report = updater.update(patch("16.23.1"), wiki);

        assertEquals(40, steraksRatio());
        assertEquals(List.of("Sterak's Gage: Angriffsschaden aus Basis-AD 50 % → 40 %"), report.changes());
        assertEquals(2, http.requests().size(), "ein Abruf je Patch, obwohl der Cache noch frisch ist");
    }

    @Test
    void samePatchUsesTheFreshCacheAndAppliedChangesSurviveARestart() throws IOException {
        wikiSays(50);
        new ItemPassiveUpdater(new FileCache(dir)).update(patch("16.22.1"), wiki);
        wikiSays(40);
        new ItemPassiveUpdater(new FileCache(dir)).update(patch("16.23.1"), wiki);
        assertEquals(2, http.requests().size());

        ItemPassives.install(List.of());
        UpdateReport report = new ItemPassiveUpdater(new FileCache(dir)).update(patch("16.23.1"), wiki);

        assertEquals(2, http.requests().size(), "kein weiterer Abruf im selben Patch");
        assertEquals(40, steraksRatio());
        assertTrue(report.changes().get(0).contains("50 % → 40 %"), "Änderung bleibt für diesen Patch sichtbar");
    }

    @Test
    void offlineKeepsTheLastKnownState() throws IOException {
        ItemPassiveUpdater updater = new ItemPassiveUpdater(new FileCache(dir));
        wikiSays(40);
        updater.update(patch("16.22.1"), wiki);
        http.setOffline(true);

        UpdateReport report = updater.update(patch("16.23.1"), wiki);

        assertEquals(40, steraksRatio());
        assertTrue(report.changes().isEmpty() || report.changes().stream().noneMatch(c -> c.contains("Sterak")));
    }

    @Test
    void passivesLeavingTheGameAreRemovedOnlyByRealPatches() {
        ItemPassive gone = new ItemPassive(9999, "Old Item", "Old", StatType.ULTIMATE_HASTE, 10);
        ItemPassiveUpdater.Merge merge = ItemPassiveUpdater.merge(List.of(gone),
                ItemPassiveUpdater.dataDragonItems(List.of(STERAK)), null);
        assertTrue(merge.passives().isEmpty());
        assertEquals(1, merge.changes().size());
    }
}
