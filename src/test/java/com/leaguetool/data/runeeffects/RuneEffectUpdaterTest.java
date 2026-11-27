package com.leaguetool.data.runeeffects;

import com.leaguetool.data.UpdateReport;
import com.leaguetool.cache.FileCache;
import com.leaguetool.calc.RuneEffects;
import com.leaguetool.calc.StatType;
import com.leaguetool.data.GameData;
import com.leaguetool.data.wiki.WikiRuneShards;
import com.leaguetool.model.Rune;
import com.leaguetool.model.RuneTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuneEffectUpdaterTest {

    /** Data-Dragon-Texte von Patch 16.22 (longDesc, gekürzt auf die ausgewerteten Sätze). */
    static final Map<Integer, String> TEXTS_16_22 = Map.ofEntries(
            Map.entry(9104, "Gain 3% attack speed plus an additional 1.5% for every Legend stack (max 10 stacks).<br><br>Earn progress toward Legend stacks for every champion takedown."),
            Map.entry(9105, "Gain 1.5 basic ability haste for every Legend stack (max 10 stacks)."),
            Map.entry(9103, "Gain 0.45% Life Steal for every Legend stack (max 15 stacks). At maximum Legend stacks, gain 85 max health."),
            Map.entry(8106, "Your ultimate gains 6 Ability Haste, plus an additional 5 Ability Haste per Bounty Hunter stack."),
            Map.entry(8226, "Hitting an enemy champion with an ability permanently increases your maximum mana by 25, up to 250 mana."),
            Map.entry(8210, "Gain bonuses upon reaching the following levels:<br><b>Level 5</b>: +5 Ability Haste<br><b>Level 8</b>: +5 Ability Haste<br>Level 11: On Champion takedown, reduce the remaining cooldown of basic abilities by 20%."),
            Map.entry(8234, "All movement bonuses are 7% more effective on you and gain 1% Move Speed."),
            Map.entry(8233, "While above 70% health, gain an adaptive bonus of up to 18 Attack Damage or 30 Ability Power (based on level).<br><br>Grants 1.8 Attack Damage or 3 Ability Power at level 1."),
            Map.entry(8232, "Gain 10 Move Speed and 13 - 30 Adaptive Force (based on level) when in the river."),
            Map.entry(8236, "Every 10 min gain AP or AD, adaptive.<br><br>10 min: + 8 AP or 5 AD<br>20 min: + 24 AP or 14 AD"),
            Map.entry(8429, "After 12 min gain +8 Armor and +8 Magic Resist and increase your Armor and Magic Resist by 3%."),
            Map.entry(8451, "Absorb life essence from monsters or enemy minions that die near you, permanently gaining 3 maximum health for every 8.<br><br>When you've absorbed 120 monsters or enemy minions, gain an additional 3.5% maximum health."),
            Map.entry(8242, "Gain 10 Armor and Magic Resist when crowd controlled and for 2 seconds after."),
            Map.entry(8347, "+18 Summoner Spell Haste<br>+10 Item Haste"),
            Map.entry(8316, "For each different stat gained from items, gain one Jack stack. Each stack grants you 1 Ability Haste.<br><br>Gain 8 or 20 bonus Adaptive Force at 5 and 10 stacks, respectively."));

    @TempDir
    Path dir;

    @BeforeEach
    @AfterEach
    void resetRunes() {
        RuneEffects.install(RuneEffects.defaults(), RuneEffects.DEFAULT_SHARD_ROWS);
    }

    private static Map<Integer, String> plain(Map<Integer, String> html) {
        Map<Integer, String> out = new HashMap<>();
        html.forEach((id, text) -> out.put(id, com.leaguetool.util.Text.htmlToPlain(text).replaceAll("\\s+", " ").strip()));
        return out;
    }

    private static GameData data(String patch, Map<Integer, String> texts) {
        List<Rune> runes = new ArrayList<>();
        texts.forEach((id, text) -> runes.add(new Rune(id, "R" + id, "Rune " + id, "", text, "", 8000, 1)));
        RuneTree tree = new RuneTree(8000, "Precision", "Precision", "", List.of(List.of(), runes));
        return new GameData(patch, false, List.of(), List.of(), List.of(tree), List.of());
    }

    @Test
    void textsOfPatch1622GiveTheShippedValues() throws Exception {
        RuneEffectUpdater.Result result = RuneEffectUpdater.recognize(plain(TEXTS_16_22),
                WikiRuneShards.parse(com.leaguetool.data.wiki.WikiRuneShardsTest.TABLE),
                RuneEffects.defaults(), RuneEffects.DEFAULT_SHARD_ROWS);
        assertTrue(result.review().isEmpty(), result.review().toString());
        RuneEffects.defaults().forEach((id, p) -> assertArrayEquals(p, result.params().get(id), 1e-9, "Rune " + id));
        assertEquals(RuneEffects.DEFAULT_SHARD_ROWS, result.rows());
    }

    @Test
    void newNumbersAreTakenOverUnreadableTextsKeepTheirValue() {
        Map<Integer, String> texts = new HashMap<>(plain(TEXTS_16_22));
        texts.put(9105, "Gain 2 basic ability haste for every Legend stack (max 12 stacks).");
        texts.put(8347, "Ganz neuer Text ohne Zahlen");
        RuneEffectUpdater.Result result = RuneEffectUpdater.recognize(texts, null, RuneEffects.defaults(),
                RuneEffects.DEFAULT_SHARD_ROWS);
        assertArrayEquals(new double[] {2, 12}, result.params().get(9105), 1e-9);
        assertArrayEquals(new double[] {18}, result.params().get(8347), 1e-9, "letzter Wert bleibt");
        assertTrue(result.review().stream().anyMatch(r -> r.startsWith("Cosmic Insight")));
    }

    @Test
    void aBrokenFormatKeepsTheWholePreviousState() {
        Map<Integer, String> texts = new HashMap<>();
        TEXTS_16_22.keySet().forEach(id -> texts.put(id, "?"));
        texts.put(9105, "Gain 2 basic ability haste for every Legend stack (max 12 stacks).");
        RuneEffectUpdater.Result result = RuneEffectUpdater.recognize(texts, null, RuneEffects.defaults(),
                RuneEffects.DEFAULT_SHARD_ROWS);
        assertArrayEquals(new double[] {1.5, 10}, result.params().get(9105), 1e-9);
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("Format")));
    }

    @Test
    void runsOnlyOnTheFirstStartOfAPatchAndThenUsesTheSavedState() {
        FileCache cache = new FileCache(dir);
        Map<Integer, String> changed = new HashMap<>(TEXTS_16_22);
        changed.put(9105, "Gain 2 basic ability haste for every Legend stack (max 10 stacks).");

        UpdateReport first = new RuneEffectUpdater(cache).update(data("16.23.1", changed), null, true);
        assertEquals(List.of("Legend: Haste: +1.5 Fähigkeitstempo für Grundfähigkeiten je Stack → +2 Fähigkeitstempo für Grundfähigkeiten je Stack"),
                first.changes());
        assertEquals(20, RuneEffects.stats(List.of(9105), List.of(), Map.of(9105, 10), 11).get(StatType.BASIC_ABILITY_HASTE), 1e-9);

        // Zweiter Start, gleicher Patch: Texte werden nicht erneut ausgewertet, der gespeicherte Stand gilt.
        resetRunes();
        UpdateReport again = new RuneEffectUpdater(cache).update(data("16.23.1", TEXTS_16_22), null, false);
        assertEquals(first.changes(), again.changes());
        assertEquals(20, RuneEffects.stats(List.of(9105), List.of(), Map.of(9105, 10), 11).get(StatType.BASIC_ABILITY_HASTE), 1e-9);
    }

    @Test
    void aBrokenHistoryFileDoesNotFailTheUpdate() throws Exception {
        FileCache cache = new FileCache(dir);
        cache.writeText(FileCache.GLOBAL, "rune-effects/history.json", "{ kaputt");
        Map<Integer, String> changed = new HashMap<>(TEXTS_16_22);
        changed.put(9105, "Gain 2 basic ability haste for every Legend stack (max 10 stacks).");

        UpdateReport report = new RuneEffectUpdater(cache).update(data("16.23.1", changed), null, true);

        assertEquals(1, report.changes().size());
        assertEquals(20, RuneEffects.stats(List.of(9105), List.of(), Map.of(9105, 10), 11).get(StatType.BASIC_ABILITY_HASTE), 1e-9);
        assertTrue(cache.readText(FileCache.GLOBAL, "rune-effects/history.json").orElseThrow().contains("16.23.1"));
    }
}
