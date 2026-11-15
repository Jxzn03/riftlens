package com.leaguetool.calc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.leaguetool.calc.StatCalculatorTest.AHRI;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DamageCalculatorTest {

    private static final double EPS = 1e-9;

    private static final List<Double> BASE = List.of(80.0, 110.0, 140.0, 170.0, 200.0);

    private static DamageInput input(int rank, int level, double ap, double totalAd, double bonusAd) {
        return new DamageInput(rank, level, ap, totalAd, bonusAd, 2000, 800, 100, 40, 60, 20);
    }

    // --- Rohschaden ---------------------------------------------------------------------------------

    @Test
    void picksBaseDamageOfRank() {
        DamageFormula f = new DamageFormula(BASE, List.of(), DamageType.MAGIC);
        assertEquals(80, DamageCalculator.rawDamage(f, input(1, 1, 0, 0, 0)), EPS);
        assertEquals(140, DamageCalculator.rawDamage(f, input(3, 1, 0, 0, 0)), EPS);
        assertEquals(200, DamageCalculator.rawDamage(f, input(5, 1, 0, 0, 0)), EPS);
        assertEquals(5, f.maxRank());
    }

    @Test
    void rankIsClampedToAvailableRanks() {
        DamageFormula f = new DamageFormula(BASE, List.of(), DamageType.MAGIC);
        assertEquals(80, DamageCalculator.rawDamage(f, input(0, 1, 0, 0, 0)), EPS);
        assertEquals(200, DamageCalculator.rawDamage(f, input(9, 1, 0, 0, 0)), EPS);
        DamageFormula noBase = new DamageFormula(List.of(), List.of(new Scaling(0.5, ScalingStat.AP)), DamageType.MAGIC);
        assertEquals(50, DamageCalculator.rawDamage(noBase, input(3, 1, 100, 0, 0)), EPS);
        assertEquals(1, noBase.maxRank());
    }

    @Test
    void addsAllScalings() {
        DamageFormula f = new DamageFormula(BASE,
                List.of(new Scaling(0.5, ScalingStat.AP), new Scaling(0.6, ScalingStat.BONUS_AD)),
                DamageType.PHYSICAL);
        assertEquals(110 + 0.5 * 200 + 0.6 * 50, DamageCalculator.rawDamage(f, input(2, 6, 200, 150, 50)), EPS);
    }

    @Test
    void everyScalingStatReadsItsInputValue() {
        DamageInput in = new DamageInput(1, 9, 1, 2, 3, 4, 5, 6, 7, 8, 10);
        assertEquals(1, ScalingStat.AP.valueFrom(in));
        assertEquals(2, ScalingStat.AD.valueFrom(in));
        assertEquals(3, ScalingStat.BONUS_AD.valueFrom(in));
        assertEquals(4, ScalingStat.MAX_HEALTH.valueFrom(in));
        assertEquals(5, ScalingStat.BONUS_HEALTH.valueFrom(in));
        assertEquals(6, ScalingStat.ARMOR.valueFrom(in));
        assertEquals(7, ScalingStat.BONUS_ARMOR.valueFrom(in));
        assertEquals(8, ScalingStat.MAGIC_RESIST.valueFrom(in));
        assertEquals(10, ScalingStat.BONUS_MAGIC_RESIST.valueFrom(in));
        assertEquals(9, ScalingStat.LEVEL.valueFrom(in));
        assertEquals(10 * 9, new Scaling(10, ScalingStat.LEVEL).contribution(in), EPS);
        for (ScalingStat s : ScalingStat.values()) {
            assertFalse(s.label().isBlank(), s.name());
        }
    }

    @Test
    void inputFromFinalStats() {
        StatBlock bonus = StatBlock.of(StatType.ABILITY_POWER, 130).with(StatType.ATTACK_DAMAGE, 10)
                .with(StatType.HEALTH, 200).with(StatType.ARMOR, 50).with(StatType.MAGIC_RESIST, 20);
        FinalStats stats = ChampionBuildCalculator.compute(AHRI, 1, bonus);
        DamageInput in = DamageInput.of(stats, 3);
        assertEquals(new DamageInput(3, 1, 130, 63, 10, 790, 200, 71, 50, 50, 20), in);
    }

    // --- Resistenzen --------------------------------------------------------------------------------

    @Test
    void mitigationMultiplier() {
        assertEquals(1, DamageCalculator.mitigationMultiplier(0), EPS);
        assertEquals(0.5, DamageCalculator.mitigationMultiplier(100), EPS);
        assertEquals(100.0 / 150, DamageCalculator.mitigationMultiplier(50), EPS);
        assertEquals(4.0 / 3, DamageCalculator.mitigationMultiplier(-50), EPS);
        assertEquals(1.5, DamageCalculator.mitigationMultiplier(-100), EPS);
    }

    @Test
    void percentPenetrationAppliesBeforeFlatPenetration() {
        // 100 * 0.7 - 10 = 60 (umgekehrt wären es (100 - 10) * 0.7 = 63)
        assertEquals(60, DamageCalculator.effectiveResist(100, 30, 10), EPS);
        assertEquals(100, DamageCalculator.effectiveResist(100, 0, 0), EPS);
    }

    @Test
    void penetrationDoesNotGoBelowZeroOrAffectNegativeResist() {
        assertEquals(0, DamageCalculator.effectiveResist(20, 0, 30), EPS);
        assertEquals(0, DamageCalculator.effectiveResist(0, 40, 10), EPS);
        assertEquals(-10, DamageCalculator.effectiveResist(-10, 40, 10), EPS);
        assertEquals(0, DamageCalculator.effectiveResist(50, 150, 0), EPS);
    }

    @Test
    void physicalDamageUsesArmorPenetrationAndLethality() {
        StatBlock pen = StatBlock.of(StatType.ARMOR_PENETRATION, 30).with(StatType.LETHALITY, 10)
                .with(StatType.MAGIC_PENETRATION, 99);
        assertEquals(1000 * 100.0 / 160, DamageCalculator.damageAfterResist(1000, DamageType.PHYSICAL, 100, 0, pen),
                EPS);
    }

    @Test
    void magicDamageUsesMagicPenetration() {
        StatBlock pen = StatBlock.of(StatType.MAGIC_PENETRATION_PERCENT, 40).with(StatType.MAGIC_PENETRATION, 18)
                .with(StatType.LETHALITY, 99);
        // 100 * 0.6 - 18 = 42
        assertEquals(1000 * 100.0 / 142, DamageCalculator.damageAfterResist(1000, DamageType.MAGIC, 0, 100, pen), EPS);
        assertEquals(500, DamageCalculator.damageAfterResist(1000, DamageType.MAGIC, 0, 100, null), EPS);
    }

    @Test
    void trueDamageIsNotReduced() {
        assertEquals(1000, DamageCalculator.damageAfterResist(1000, DamageType.TRUE, 300, 300, StatBlock.EMPTY), EPS);
    }

    @Test
    void mixedDamageIsReturnedUnchanged() {
        assertEquals(1000, DamageCalculator.damageAfterResist(1000, DamageType.MIXED, 100, 100, StatBlock.EMPTY), EPS);
    }

    @Test
    void damageCombinesRawDamageAndResist() {
        DamageFormula f = new DamageFormula(BASE, List.of(new Scaling(0.75, ScalingStat.AP)), DamageType.MAGIC);
        // Rang 5: 200 + 0.75 * 400 = 500, gegen 100 MR ohne Durchdringung → 250
        assertEquals(250, DamageCalculator.damage(f, input(5, 11, 400, 0, 0), 0, 100, StatBlock.EMPTY), EPS);
    }

    @Test
    void effectiveHealth() {
        assertEquals(2000, DamageCalculator.effectiveHealth(1000, 100), EPS);
        assertEquals(1000, DamageCalculator.effectiveHealth(1000, 0), EPS);
        assertEquals(750, DamageCalculator.effectiveHealth(1000, -50), EPS);
    }

    @Test
    void formulaCopiesListsAndRequiresType() {
        assertThrows(NullPointerException.class, () -> new DamageFormula(BASE, List.of(), null));
        assertThrows(NullPointerException.class, () -> new Scaling(0.5, null));
    }

    // --- Texte aus dem Wiki -------------------------------------------------------------------------

    @Test
    void parsesScalingStatNames() {
        assertEquals(Optional.of(ScalingStat.AP), ScalingStat.parse("AP"));
        assertEquals(Optional.of(ScalingStat.AP), ScalingStat.parse("ability power"));
        assertEquals(Optional.of(ScalingStat.BONUS_AD), ScalingStat.parse("bonus AD"));
        assertEquals(Optional.of(ScalingStat.BONUS_AD), ScalingStat.parse("  Bonus   Attack Damage "));
        assertEquals(Optional.of(ScalingStat.AD), ScalingStat.parse("AD"));
        assertEquals(Optional.of(ScalingStat.MAX_HEALTH), ScalingStat.parse("maximum health"));
        assertEquals(Optional.of(ScalingStat.BONUS_HEALTH), ScalingStat.parse("bonus health"));
        assertEquals(Optional.of(ScalingStat.ARMOR), ScalingStat.parse("armor"));
        assertEquals(Optional.of(ScalingStat.BONUS_ARMOR), ScalingStat.parse("bonus armor"));
        assertEquals(Optional.of(ScalingStat.MAGIC_RESIST), ScalingStat.parse("magic resistance"));
        assertEquals(Optional.of(ScalingStat.BONUS_MAGIC_RESIST), ScalingStat.parse("bonus magic resistance"));
        assertEquals(Optional.of(ScalingStat.LEVEL), ScalingStat.parse("level"));
    }

    @Test
    void scalingStatParseIgnoresLeadingRatio() {
        assertEquals(Optional.of(ScalingStat.AP), ScalingStat.parse("50% AP"));
        assertEquals(Optional.of(ScalingStat.BONUS_AD), ScalingStat.parse("(+ 60% bonus AD)"));
    }

    @Test
    void scalingStatParseRejectsUnknownAndTargetStats() {
        assertEquals(Optional.empty(), ScalingStat.parse("target's maximum health"));
        assertEquals(Optional.empty(), ScalingStat.parse("missing health"));
        assertEquals(Optional.empty(), ScalingStat.parse(""));
        assertEquals(Optional.empty(), ScalingStat.parse(null));
    }

    @Test
    void parsesScalingTexts() {
        assertEquals(Optional.of(new Scaling(0.5, ScalingStat.AP)), Scaling.parse("50% AP"));
        assertEquals(Optional.of(new Scaling(0.6, ScalingStat.BONUS_AD)), Scaling.parse("60% bonus AD"));
        assertEquals(Optional.of(new Scaling(0.08, ScalingStat.MAX_HEALTH)), Scaling.parse("(+ 8% maximum health)"));
        assertEquals(Optional.of(new Scaling(0.125, ScalingStat.BONUS_MAGIC_RESIST)),
                Scaling.parse("12.5% bonus magic resistance"));
        assertEquals(Optional.empty(), Scaling.parse("AP"));
        assertEquals(Optional.empty(), Scaling.parse("10% target's maximum health"));
        assertEquals(Optional.empty(), Scaling.parse(null));
    }
}
