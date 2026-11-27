package com.leaguetool.calc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuneEffectsTest {

    private static final double EPS = 1e-9;

    @org.junit.jupiter.api.BeforeEach
    void shippedValues() {
        RuneEffects.install(RuneEffects.defaults(), RuneEffects.DEFAULT_SHARD_ROWS);
    }

    private static StatBlock runes(int level, Map<Integer, Integer> stacks, Integer... ids) {
        return RuneEffects.stats(List.of(ids), List.of(), stacks, level);
    }

    @Test
    void shardsCountPerRowAndHealthScalesWithLevel() {
        // Adaptive Force in Offensive und Flex zählt doppelt, Health Scaling 10 je Level.
        StatBlock b = RuneEffects.stats(List.of(), List.of(5008, 5008, 5001), Map.of(), 11);
        assertEquals(18, b.get(StatType.ADAPTIVE_FORCE), EPS);
        assertEquals(110, b.get(StatType.RUNE_HEALTH), EPS);
        assertEquals(0, b.get(StatType.HEALTH), EPS, "Runen-Leben getrennt vom Item-Leben (Warmog's)");

        StatBlock other = RuneEffects.stats(List.of(), List.of(5007, 5010, 5013), Map.of(), 1);
        assertEquals(8, other.get(StatType.ABILITY_HASTE), EPS);
        assertEquals(2.5, other.get(StatType.MOVE_SPEED_PERCENT), EPS);
        assertEquals(15, other.get(StatType.TENACITY), EPS);
        assertEquals(15, other.get(StatType.SLOW_RESIST), EPS);
    }

    @Test
    void shardsOutsideTheirRowAreIgnored() {
        assertTrue(RuneEffects.stats(List.of(), List.of(5011, 5005, 5007), Map.of(), 5).isEmpty(),
                "Health gibt es nur defensiv, Attack Speed nur offensiv, Ability Haste nur offensiv");
    }

    @Test
    void transcendenceDependsOnLevel() {
        assertEquals(0, runes(4, Map.of(), 8210).get(StatType.ABILITY_HASTE), EPS);
        assertEquals(5, runes(5, Map.of(), 8210).get(StatType.ABILITY_HASTE), EPS);
        assertEquals(10, runes(8, Map.of(), 8210).get(StatType.ABILITY_HASTE), EPS);
        assertEquals(10, runes(18, Map.of(8210, 99), 8210).get(StatType.ABILITY_HASTE), EPS, "immer aktiv: Stapel egal");
    }

    @Test
    void stacksAreClampedAndHaveBaseValues() {
        assertEquals(6, runes(11, Map.of(), 8106).get(StatType.ULTIMATE_HASTE), EPS);
        assertEquals(31, runes(11, Map.of(8106, 5), 8106).get(StatType.ULTIMATE_HASTE), EPS);
        assertEquals(31, runes(11, Map.of(8106, 50), 8106).get(StatType.ULTIMATE_HASTE), EPS, "höchstens 5 Stapel");
        assertEquals(15, runes(11, Map.of(9105, 10), 9105).get(StatType.BASIC_ABILITY_HASTE), EPS);
        assertEquals(18, runes(11, Map.of(9104, 10), 9104).get(StatType.ATTACK_SPEED), EPS);

        StatBlock bloodline = runes(11, Map.of(9103, 15), 9103);
        assertEquals(6.75, bloodline.get(StatType.LIFE_STEAL), EPS);
        assertEquals(85, bloodline.get(StatType.RUNE_HEALTH), EPS);

        StatBlock overgrowth = runes(11, Map.of(8451, 15), 8451);
        assertEquals(45, overgrowth.get(StatType.RUNE_HEALTH), EPS);
        assertEquals(3.5, overgrowth.get(StatType.MAX_HEALTH_PERCENT), EPS);

        assertEquals(List.of(8.0, 24.0, 48.0, 80.0, 120.0, 168.0), java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(n -> runes(11, Map.of(8236, n), 8236).get(StatType.ADAPTIVE_FORCE)).toList(), "Gathering Storm");

        StatBlock jack = runes(11, Map.of(8316, 10), 8316);
        assertEquals(10, jack.get(StatType.ABILITY_HASTE), EPS);
        assertEquals(20, jack.get(StatType.ADAPTIVE_FORCE), EPS);
    }

    @Test
    void togglesOnlyCountWhenSwitchedOn() {
        assertTrue(runes(11, Map.of(), 8429).isEmpty());
        StatBlock conditioning = runes(11, Map.of(8429, 1), 8429);
        assertEquals(8, conditioning.get(StatType.ARMOR), EPS);
        assertEquals(3, conditioning.get(StatType.RESIST_PERCENT), EPS);
        assertEquals(3, runes(1, Map.of(8233, 1), 8233).get(StatType.ADAPTIVE_FORCE), EPS, "Absolute Focus Level 1");
        assertEquals(30, runes(18, Map.of(8233, 1), 8233).get(StatType.ADAPTIVE_FORCE), EPS, "Absolute Focus Level 18");
    }

    @Test
    void runesWithoutValuesAndDuplicatesAreHarmless() {
        assertTrue(runes(11, Map.of(), 8010, 8112).isEmpty(), "Conqueror und Electrocute geben keine Werte");
        assertEquals(18, runes(11, Map.of(), 8347, 8347).get(StatType.SUMMONER_SPELL_HASTE), EPS, "doppelt zählt einmal");
    }

    @Test
    void jackBeschreibungNenntAdUndAp() {
        String summary = RuneEffects.all().stream().filter(e -> e.id() == JackStacks.RUNE_ID).findFirst().orElseThrow().summary();
        assertTrue(summary.contains("Ab 5 Stacks +4.8 AD oder +8 AP"), summary);
        assertTrue(summary.contains("zusätzlich +7.2 AD oder +12 AP, zusammen +12 AD oder +20 AP"), summary);
    }
}
