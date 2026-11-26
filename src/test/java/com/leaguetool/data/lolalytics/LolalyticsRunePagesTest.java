package com.leaguetool.data.lolalytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leaguetool.model.Rune;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class LolalyticsRunePagesTest {

    private static final int MIN_GAMES = 11_364;   // 1 % der Spiele

    /** Precision (8000) und Resolve (8400): Baum und Reihe je Rune. */
    private static final Map<Integer, Rune> RUNES = Map.ofEntries(
            rune(8010, 8000, 0), rune(9111, 8000, 1), rune(8009, 8000, 1),
            rune(9104, 8000, 2), rune(9105, 8000, 2), rune(8299, 8000, 3), rune(8017, 8000, 3),
            rune(8473, 8400, 2), rune(8444, 8400, 2), rune(8451, 8400, 3), rune(8453, 8400, 3));

    private static Map.Entry<Integer, Rune> rune(int id, int tree, int slot) {
        return Map.entry(id, new Rune(id, "", "Rune " + id, "", "", "", tree, slot));
    }

    private static Optional<Rune> info(int id) {
        return Optional.ofNullable(RUNES.get(id));
    }

    private static LolalyticsBuild.Entry entry(int id, double winRate, int games) {
        return new LolalyticsBuild.Entry(String.valueOf(id), winRate, 0, games);
    }

    private static final List<Integer> COMMON_PRIMARY = List.of(8010, 9111, 9105, 8299);
    private static final List<Integer> COMMON_SUB = List.of(8453, 8473);
    private static final List<Integer> SHARDS = List.of(5008, 5008, 5001);

    /** Aatrox Top (Lolalytics, 30 Tage): Conqueror mit zwei verschiedenen Seiten, beide mit Keystone-Werten. */
    private static LolalyticsBuild build(List<Map<Integer, LolalyticsBuild.Entry>> shardRows,
                                         LolalyticsBuild.RunePage... pages) {
        Map<Integer, LolalyticsBuild.Entry> runes = Map.of(
                8010, entry(8010, 0.494, 1_038_533), 9111, entry(9111, 0.494, 1_034_340),
                8009, entry(8009, 0.513, 9_000), 9105, entry(9105, 0.492, 941_973),
                9104, entry(9104, 0.52, 89_355), 8299, entry(8299, 0.494, 1_009_865),
                8017, entry(8017, 0.56, 900));
        Map<Integer, LolalyticsBuild.Entry> sub = Map.of(
                8453, entry(8453, 0.495, 783_475), 8473, entry(8473, 0.495, 758_594),
                8451, entry(8451, 0.523, 92_097), 8444, entry(8444, 0.518, 162_385));
        return new LolalyticsBuild(1_136_394, 0.494, 0.1, 0.05, List.of(), List.of(), List.of(), runes, sub,
                shardRows, List.of(pages), List.of(), List.of(), List.of(), new LolalyticsPairs(Map.of()),
                new LolalyticsBuild.Damage(0, 0, 0), List.of(), List.of(), List.of());
    }

    /** Shards ohne bessere Wahl über der Mindest-Stichprobe; nur die Runen entscheiden. */
    private static final List<Map<Integer, LolalyticsBuild.Entry>> PLAIN_SHARDS = List.of(
            Map.of(5008, entry(5008, 0.495, 1_045_234), 5007, entry(5007, 0.488, 11_938)),
            Map.of(5008, entry(5008, 0.495, 1_120_397), 5010, entry(5010, 0.494, 8_030)),
            Map.of(5001, entry(5001, 0.493, 966_870)));

    /** Alle drei Shard-Reihen von Aatrox Top laut Lolalytics. */
    private static final List<Map<Integer, LolalyticsBuild.Entry>> AATROX_SHARDS = List.of(
            Map.of(5008, entry(5008, 0.495, 1_045_234), 5005, entry(5005, 0.505, 79_222),
                    5007, entry(5007, 0.488, 11_938)),
            Map.of(5008, entry(5008, 0.495, 1_120_397), 5010, entry(5010, 0.494, 8_030),
                    5001, entry(5001, 0.504, 7_967)),
            Map.of(5001, entry(5001, 0.493, 966_870), 5011, entry(5011, 0.51, 128_768),
                    5013, entry(5013, 0.512, 40_756)));

    private static LolalyticsBuild build(LolalyticsBuild.RunePage... pages) {
        return build(PLAIN_SHARDS, pages);
    }

    private static LolalyticsBuild.RunePage page(List<Integer> primary, List<Integer> sub, List<Integer> shards,
                                                 boolean mostPlayed) {
        return new LolalyticsBuild.RunePage(primary, sub, shards, 0.4944, 1_038_533, mostPlayed);
    }

    private static LolalyticsBuild.RunePage common() {
        return page(COMMON_PRIMARY, COMMON_SUB, SHARDS, true);
    }

    @Test
    void pagesWithTheSameKeystoneGetDifferentEstimates() {
        LolalyticsRunePages.Estimate common = LolalyticsRunePages.of(build(), common());
        LolalyticsRunePages.Estimate rare = LolalyticsRunePages.of(build(),
                page(List.of(8010, 9111, 9104, 8299), List.of(8451, 8444), SHARDS, false));
        assertTrue(common.estimated());
        assertEquals(0.494, common.winRate(), 0.002, "Runen wie der Champion → Winrate wie der Champion");
        assertEquals(758_594, common.games(), "kleinste Spielzahl der Runen und Shards");
        assertTrue(rare.winRate() > 0.501 && rare.winRate() < 0.506,
                "Mittelwert der Abweichungen, nicht ihre Summe: " + rare.winRate());
        assertEquals(89_355, rare.games());
        assertEquals(List.of(8010, 9111, 9104, 8299, 8451, 8444, 5008, 5008, 5001),
                rare.runes().stream().map(LolalyticsRunePages.RuneStat::id).toList(), "alle drei Shards zählen mit");
    }

    @Test
    void missingRuneFallsBackToKeystoneValues() {
        LolalyticsRunePages.Estimate estimate = LolalyticsRunePages.of(build(),
                page(COMMON_PRIMARY, List.of(8453, 9999), SHARDS, true));
        assertFalse(estimate.estimated());
        assertEquals(0.4944, estimate.winRate(), 1e-9);
        assertEquals(1_038_533, estimate.games());
    }

    @Test
    void recommendationSwapsOnlyClearlyBetterRunesOfTheSameRow() {
        LolalyticsRunePages.Recommendation rec = LolalyticsRunePages.recommend(build(common()),
                LolalyticsRunePagesTest::info, MIN_GAMES).orElseThrow();
        assertEquals(List.of(8010, 9111, 9104, 8299), rec.page().primary(),
                "Keystone bleibt; Presence of Mind und Cut Down liegen unter der Mindest-Stichprobe");
        assertEquals(List.of(8451, 8444), rec.page().sub(), "je Reihe des Sekundärbaums die bessere Rune");
        assertEquals(SHARDS, rec.page().shards(), "keine bessere Wahl über der Mindest-Stichprobe");
        assertEquals(List.of(9104, 8451, 8444), rec.swaps().stream().map(LolalyticsRunePages.Swap::id).toList());
        assertEquals(9105, rec.swaps().get(0).replaces());
        assertEquals(0.492, rec.swaps().get(0).replacedWinRate(), 1e-9);
        assertTrue(rec.delta() > 0.005, "geschätzter Vorteil gegenüber Meistgespielt: " + rec.delta());
    }

    @Test
    void recommendationMaySwapTheFlexShard() {
        LolalyticsBuild build = build(List.of(PLAIN_SHARDS.get(0),
                Map.of(5008, entry(5008, 0.494, 1_000_000), 5001, entry(5001, 0.51, 120_000)), PLAIN_SHARDS.get(2)), common());
        LolalyticsRunePages.Recommendation rec = LolalyticsRunePages.recommend(build, LolalyticsRunePagesTest::info,
                MIN_GAMES).orElseThrow();
        assertEquals(List.of(5008, 5001, 5001), rec.page().shards(), "nur der Flex-Shard wird ersetzt");
        assertEquals(List.of(9104, 8451, 8444, 5001), rec.swaps().stream().map(LolalyticsRunePages.Swap::id).toList());
        assertEquals(5008, rec.swaps().get(3).replaces());
        assertEquals(1, rec.swaps().get(3).shardSlot(), "Stelle nötig: 5001 steht auch in der Defensive");
        assertEquals(LolalyticsRunePages.NO_SHARD, rec.swaps().get(0).shardSlot());
    }

    @Test
    void noRecommendationWhenItMatchesADeliveredPage() {
        LolalyticsBuild.RunePage win = page(List.of(8010, 9111, 9104, 8299), List.of(8444, 8451), SHARDS, false);
        assertTrue(LolalyticsRunePages.recommend(build(common(), win), LolalyticsRunePagesTest::info, MIN_GAMES)
                .isEmpty(), "gleich der erfolgreichsten Seite (Reihenfolge im Sekundärbaum egal)");
    }

    @Test
    void noRecommendationWithoutMostPlayedPage() {
        assertTrue(LolalyticsRunePages.recommend(build(page(COMMON_PRIMARY, COMMON_SUB, SHARDS, false)),
                LolalyticsRunePagesTest::info, MIN_GAMES).isEmpty());
    }

    @Test
    void recommendationSwapsOffensiveAndDefensiveShards() {
        LolalyticsRunePages.Recommendation rec = LolalyticsRunePages.recommend(build(AATROX_SHARDS, common()),
                LolalyticsRunePagesTest::info, MIN_GAMES).orElseThrow();
        assertEquals(5005, rec.page().shards().get(0), "Angriffstempo statt Adaptive");
        assertEquals(5008, rec.page().shards().get(1), "Flex-Alternativen unter der Mindest-Stichprobe");
        assertTrue(List.of(5011, 5013).contains(rec.page().shards().get(2)), "Defensive: HP-Flat oder Tenacity");
        assertEquals(List.of(0, 2), rec.swaps().stream().map(LolalyticsRunePages.Swap::shardSlot)
                .filter(slot -> slot != LolalyticsRunePages.NO_SHARD).toList());
        assertTrue(LolalyticsRunePages.of(build(AATROX_SHARDS, common()), rec.page()).games() >= 40_756);
    }

    @Test
    void shardsWeighLessThanRunes() {
        // Dieselbe Abweichung (+5 Punkte) einmal nur bei einer Rune, einmal nur bei einem Shard.
        LolalyticsBuild.RunePage page = common();
        List<Map<Integer, LolalyticsBuild.Entry>> betterShard = List.of(PLAIN_SHARDS.get(0), PLAIN_SHARDS.get(1),
                Map.of(5001, entry(5001, 0.544, 966_870)));
        double base = LolalyticsRunePages.of(build(page), page).winRate();
        double shardShift = LolalyticsRunePages.of(build(betterShard, page), page).winRate() - base;
        LolalyticsBuild runeBuild = build(page);
        Map<Integer, LolalyticsBuild.Entry> sub = new java.util.HashMap<>(runeBuild.subRunes());
        sub.put(8473, entry(8473, 0.545, 758_594));
        LolalyticsBuild betterRune = new LolalyticsBuild(runeBuild.games(), runeBuild.winRate(), 0.1, 0.05, List.of(),
                List.of(), List.of(), runeBuild.runes(), sub, PLAIN_SHARDS, List.of(page), List.of(), List.of(), List.of(),
                new LolalyticsPairs(Map.of()), new LolalyticsBuild.Damage(0, 0, 0), List.of(), List.of(), List.of());
        double runeShift = LolalyticsRunePages.of(betterRune, page).winRate() - base;
        assertTrue(shardShift > 0 && runeShift > 2.5 * shardShift,
                "Shard je 1/3, Rune 1: " + shardShift + " gegen " + runeShift);
    }
}
