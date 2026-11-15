package com.leaguetool.calc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CooldownCalculatorTest {

    private static final double EPS = 1e-9;

    @Test
    void cooldownShrinksWithAbilityHaste() {
        assertEquals(10, CooldownCalculator.cooldown(10, 0), EPS);
        assertEquals(5, CooldownCalculator.cooldown(10, 100), EPS);
        assertEquals(10.0 / 3, CooldownCalculator.cooldown(10, 200), EPS);
    }

    @Test
    void cooldownReductionFollowsHasteFormula() {
        assertEquals(0, CooldownCalculator.cooldownReduction(0), EPS);
        assertEquals(0.5, CooldownCalculator.cooldownReduction(100), EPS);
        assertEquals(2.0 / 3, CooldownCalculator.cooldownReduction(200), EPS);
    }

    @Test
    void negativeHasteIsTreatedAsZero() {
        assertEquals(10, CooldownCalculator.cooldown(10, -50), EPS);
        assertEquals(0, CooldownCalculator.cooldownReduction(-20), EPS);
    }

    @Test
    void hasteIsCappedAt500() {
        assertEquals(500.0 / 600, CooldownCalculator.cooldownReduction(500), EPS, "Wiki: 83,33 %");
        assertEquals(500.0 / 600, CooldownCalculator.cooldownReduction(800), EPS);
        assertEquals(10 * 100.0 / 600, CooldownCalculator.cooldown(10, 1000), EPS);
        assertEquals(300 * 100.0 / 1100, CooldownCalculator.summonerSpellCooldown(300, 1000), EPS,
                "für Summoner Spell Haste nennt das Wiki keinen Cap");
    }
}
