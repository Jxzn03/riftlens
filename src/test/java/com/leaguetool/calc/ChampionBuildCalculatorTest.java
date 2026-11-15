package com.leaguetool.calc;

import com.leaguetool.model.AbilitySlot;
import com.leaguetool.model.BaseStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.leaguetool.calc.StatCalculatorTest.AHRI;
import static com.leaguetool.calc.TestItems.byId;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ChampionBuildCalculatorTest {

    private static final double EPS = 1e-9;

    @BeforeEach
    void useBaselinePassives() {
        ItemPassives.install(ItemPassives.baseline());
    }

    /** Champion ohne Wachstum, damit Grenzwerte leicht nachzurechnen sind. */
    private static BaseStats flat(double moveSpeed, double attackSpeed) {
        return new BaseStats(1000, 0, 500, 0, moveSpeed, 50, 0, 40, 0, 125,
                5, 0, 7, 0, 0, 0, 60, 0, attackSpeed, 0);
    }

    @Test
    void withoutBonusMatchesLevelStats() {
        FinalStats f = ChampionBuildCalculator.compute(AHRI, 18, StatBlock.EMPTY);
        ChampionLevelStats lvl = StatCalculator.atLevel(AHRI, 18);
        assertEquals(18, f.level());
        assertEquals(lvl.hp(), f.maxHealth(), EPS);
        assertEquals(lvl.mp(), f.maxMana(), EPS);
        assertEquals(lvl.attackDamage(), f.totalAd(), EPS);
        assertEquals(0, f.bonusAd(), EPS);
        assertEquals(lvl.armor(), f.armor(), EPS);
        assertEquals(lvl.magicResist(), f.magicResist(), EPS);
        assertEquals(lvl.attackSpeed(), f.attackSpeed(), EPS);
        assertEquals(lvl.hpRegen(), f.healthRegen(), EPS);
        assertEquals(330, f.moveSpeed(), EPS);
        assertEquals(550, f.attackRange(), EPS);
    }

    @Test
    void addsItemBonusesToLevelStats() {
        StatBlock bonus = BuildCalculator.totals(List.of(
                byId(TestItems.RABADONS_DEATHCAP), byId(TestItems.ZHONYAS_HOURGLASS), byId(TestItems.BOOTS))).stats()
                .with(StatType.HEALTH, 300)
                .with(StatType.MANA, 200)
                .with(StatType.ATTACK_DAMAGE, 20)
                .with(StatType.MAGIC_RESIST, 40);
        FinalStats f = ChampionBuildCalculator.compute(AHRI, 1, bonus);
        assertEquals(890, f.maxHealth(), EPS);
        assertEquals(300, f.bonusHealth(), EPS);
        assertEquals(618, f.maxMana(), EPS);
        assertEquals(200, f.bonusMana(), EPS);
        assertEquals(73, f.totalAd(), EPS);
        assertEquals(20, f.bonusAd(), EPS);
        assertEquals(235 * 1.3, f.abilityPower(), EPS);   // Rabadon's Deathcap: +30 %
        assertEquals(71, f.armor(), EPS);
        assertEquals(50, f.bonusArmor(), EPS);
        assertEquals(70, f.magicResist(), EPS);
        assertEquals(40, f.bonusMagicResist(), EPS);
        assertEquals(355, f.moveSpeed(), EPS);
        assertSame(bonus, f.bonus());
    }

    // --- Prozentuale Item-Passiven ------------------------------------------------------------------

    @Test
    void abilityPowerAmpMultipliesFlatAndConvertedPower() {
        StatBlock bonus = StatBlock.of(StatType.ABILITY_POWER, 100)
                .with(StatType.HEALTH, 500).with(StatType.AP_PER_BONUS_HEALTH, 2)
                .with(StatType.MANA, 1000).with(StatType.AP_PER_BONUS_MANA, 1)
                .with(StatType.ABILITY_POWER_AMP, 30);
        // (100 + 500 × 2 % + 1000 × 1 %) × 1,3
        assertEquals(156, ChampionBuildCalculator.compute(flat(330, 0.625), 1, bonus).abilityPower(), EPS);
    }

    @Test
    void healthAmpAppliesToItemHealthAndManaConversionAddsToBonusHealth() {
        StatBlock bonus = StatBlock.of(StatType.HEALTH, 1000).with(StatType.ITEM_HEALTH_AMP, 12)
                .with(StatType.MANA, 1000).with(StatType.HEALTH_PER_BONUS_MANA, 15);
        FinalStats f = ChampionBuildCalculator.compute(flat(330, 0.625), 1, bonus);
        assertEquals(1270, f.bonusHealth(), EPS);
        assertEquals(2270, f.maxHealth(), EPS);
    }

    @Test
    void attackDamageConvertsFromBonusHealthAndMaxManaIncludingBaseMana() {
        StatBlock bonus = StatBlock.of(StatType.ATTACK_DAMAGE, 35)
                .with(StatType.HEALTH, 400).with(StatType.AD_PER_BONUS_HEALTH, 2.5)
                .with(StatType.MANA, 1000).with(StatType.AD_PER_MAX_MANA, 2);
        FinalStats f = ChampionBuildCalculator.compute(flat(330, 0.625), 1, bonus);
        assertEquals(35 + 10 + 30, f.bonusAd(), EPS);   // 400 × 2,5 % + (500 + 1000) × 2 %
        assertEquals(60 + 75, f.totalAd(), EPS);
    }

    @Test
    void attackDamageConvertsFromBaseAdOfTheCurrentLevel() {
        BaseStats growing = new BaseStats(1000, 0, 500, 0, 330, 50, 0, 40, 0, 125,
                5, 0, 7, 0, 0, 0, 60, 4, 0.625, 0);
        StatBlock bonus = StatBlock.of(StatType.AD_PER_BASE_AD, 50);
        double level18Ad = StatCalculator.statAtLevel(60, 4, 18);
        FinalStats f = ChampionBuildCalculator.compute(growing, 18, bonus);
        assertEquals(level18Ad * 0.5, f.bonusAd(), EPS);
        assertEquals(level18Ad * 1.5, f.totalAd(), EPS);
    }

    @Test
    void baseManaRegenConvertsProportionallyToPowerAndHealShieldPower() {
        StatBlock bonus = StatBlock.of(StatType.ABILITY_POWER, 45).with(StatType.HEAL_SHIELD_POWER, 16)
                .with(StatType.MANA_REGEN, 125)
                .with(StatType.AP_PER_100_BASE_MANA_REGEN, 15).with(StatType.HSP_PER_100_BASE_MANA_REGEN, 2);
        FinalStats f = ChampionBuildCalculator.compute(flat(330, 0.625), 1, bonus);
        assertEquals(45 + 18.75, f.abilityPower(), EPS);
        assertEquals(16 + 2.5, f.healShieldPower(), EPS);
    }

    @Test
    void bonusManaConvertsToHealShieldPower() {
        StatBlock bonus = StatBlock.of(StatType.HEAL_SHIELD_POWER, 8).with(StatType.MANA, 1000)
                .with(StatType.HSP_PER_BONUS_MANA, 0.5);
        assertEquals(13, ChampionBuildCalculator.compute(flat(330, 0.625), 1, bonus).healShieldPower(), EPS);
    }

    @Test
    void hasteFromBonusAdDependsOnMeleeOrRanged() {
        StatBlock bonus = StatBlock.of(StatType.ATTACK_DAMAGE, 100).with(StatType.ABILITY_HASTE, 5)
                .with(StatType.HASTE_PER_BONUS_AD_MELEE, 13).with(StatType.HASTE_PER_BONUS_AD_RANGED, 10);
        BaseStats ranged = new BaseStats(1000, 0, 500, 0, 330, 50, 0, 40, 0, 550,
                5, 0, 7, 0, 0, 0, 60, 0, 0.625, 0);
        assertEquals(18, ChampionBuildCalculator.compute(flat(330, 0.625), 1, bonus).abilityHaste(), EPS);
        assertEquals(15, ChampionBuildCalculator.compute(ranged, 1, bonus).abilityHaste(), EPS);
    }

    // --- Adaptive Kraft und Haste je Fähigkeit -------------------------------------------------------

    private static StatBlock swiftmarch() {
        return StatBlock.of(StatType.ADAPTIVE_PER_MOVE_SPEED, 5);
    }

    @Test
    void adaptiveForceFollowsChampionTypeWhileBonusStatsAreEqual() {
        FinalStats physical = ChampionBuildCalculator.compute(flat(400, 0.625), 1, swiftmarch(), 0.625, AdaptiveType.PHYSICAL);
        assertEquals(20 * 0.6, physical.bonusAd(), EPS);
        assertEquals(0, physical.abilityPower(), EPS);
        FinalStats magic = ChampionBuildCalculator.compute(flat(400, 0.625), 1, swiftmarch(), 0.625, AdaptiveType.MAGIC);
        assertEquals(0, magic.bonusAd(), EPS);
        assertEquals(20, magic.abilityPower(), EPS);
    }

    @Test
    void adaptiveForceFollowsTheHigherBonusStat() {
        StatBlock ap = swiftmarch().with(StatType.ABILITY_POWER, 50).with(StatType.ATTACK_DAMAGE, 10);
        FinalStats f = ChampionBuildCalculator.compute(flat(400, 0.625), 1, ap, 0.625, AdaptiveType.PHYSICAL);
        assertEquals(70, f.abilityPower(), EPS);
        assertEquals(10, f.bonusAd(), EPS);
    }

    @Test
    void aweAndVoidInfusionDoNotDecideAdaptiveForceButRabadonsBonusDoes() {
        StatBlock awe = swiftmarch().with(StatType.ATTACK_DAMAGE, 10).with(StatType.ABILITY_POWER, 10)
                .with(StatType.MANA, 1000).with(StatType.AP_PER_BONUS_MANA, 2).with(StatType.AD_PER_MAX_MANA, 0.1);
        FinalStats tie = ChampionBuildCalculator.compute(flat(400, 0.625), 1, awe, 0.625, AdaptiveType.PHYSICAL);
        assertEquals(10 + 1.5 + 12, tie.bonusAd(), EPS);   // Awe entscheidet nicht: Gleichstand, also Typ

        StatBlock rabadon = swiftmarch().with(StatType.ATTACK_DAMAGE, 11).with(StatType.ABILITY_POWER, 10)
                .with(StatType.ABILITY_POWER_AMP, 30);
        FinalStats f = ChampionBuildCalculator.compute(flat(400, 0.625), 1, rabadon, 0.625, AdaptiveType.PHYSICAL);
        assertEquals((10 + 20) * 1.3, f.abilityPower(), EPS);   // 13 AP gegen 11 AD: AP, und Rabadon's verstärkt sie
    }

    @Test
    void specificHasteAppliesOnlyToItsAbilities() {
        StatBlock bonus = StatBlock.of(StatType.ABILITY_HASTE, 10).with(StatType.BASIC_ABILITY_HASTE, 25)
                .with(StatType.ULTIMATE_HASTE, 30).with(StatType.IMMOBILIZE_ABILITY_HASTE, 20);
        FinalStats f = ChampionBuildCalculator.compute(flat(330, 0.625), 1, bonus);
        assertEquals(10, f.abilityHaste(), EPS);
        assertEquals(35, f.abilityHaste(AbilitySlot.Q, false), EPS);
        assertEquals(55, f.abilityHaste(AbilitySlot.E, true), EPS);
        assertEquals(40, f.abilityHaste(AbilitySlot.R, false), EPS);
        assertEquals(10, f.abilityHaste(AbilitySlot.P, false), EPS);
        assertEquals(0, f.summonerSpellHaste(), EPS, "Ability Haste wirkt nicht auf Beschwörerzauber");
    }

    @Test
    void runeValuesFollowTheirOwnRules() {
        // 1000 Grundleben, 50 Rüstung, 40 MR, 330 Lauftempo (siehe flat()).
        StatBlock bonus = StatBlock.of(StatType.HEALTH, 100).with(StatType.ITEM_HEALTH_AMP, 10)
                .with(StatType.RUNE_HEALTH, 50).with(StatType.MAX_HEALTH_PERCENT, 3.5)
                .with(StatType.ARMOR, 8).with(StatType.MAGIC_RESIST, 8).with(StatType.RESIST_PERCENT, 3)
                .with(StatType.MOVE_SPEED_PERCENT, 10).with(StatType.MOVE_BONUS_AMP, 7);
        FinalStats f = ChampionBuildCalculator.compute(flat(330, 0.625), 1, bonus);
        assertEquals((1000 + 110 + 50) * 1.035, f.maxHealth(), EPS, "Warmog's nur aufs Item-Leben, Overgrowth aufs ganze");
        assertEquals(f.maxHealth() - 1000, f.bonusHealth(), EPS);
        assertEquals(58 * 1.03, f.armor(), EPS, "Conditioning: +8, dann +3 % auf die gesamte Rüstung");
        assertEquals(48 * 1.03, f.magicResist(), EPS);
        assertEquals(330 * 1.107, f.moveSpeedRaw(), EPS, "Celerity: 10 % Lauftempo wirken als 10,7 %");
    }

    @Test
    void totalHasteIsCappedAt500ButSummonerSpellHasteIsSeparate() {
        StatBlock bonus = StatBlock.of(StatType.ABILITY_HASTE, 480).with(StatType.ULTIMATE_HASTE, 50)
                .with(StatType.SUMMONER_SPELL_HASTE, 20);
        FinalStats f = ChampionBuildCalculator.compute(flat(330, 0.625), 1, bonus);
        assertEquals(480, f.abilityHaste(AbilitySlot.Q, false), EPS);
        assertEquals(500, f.abilityHaste(AbilitySlot.R, false), EPS, "Ability + Ultimate Haste zusammen gekappt");
        assertEquals(20, f.summonerSpellHaste(), EPS);
        assertEquals(300 * 100.0 / 120, f.summonerSpellCooldown(300), EPS, "Flash: 300 s mit 20 Summoner Spell Haste");
    }

    @Test
    void nullBonusMeansNoBonus() {
        assertEquals(ChampionBuildCalculator.compute(AHRI, 5, StatBlock.EMPTY),
                ChampionBuildCalculator.compute(AHRI, 5, null));
    }

    // --- Angriffstempo ------------------------------------------------------------------------------

    @Test
    void attackSpeedWithDefaultRatioEqualsMultiplicativeFormula() {
        FinalStats f = ChampionBuildCalculator.compute(AHRI, 18, StatBlock.of(StatType.ATTACK_SPEED, 40));
        assertEquals(2.2 * 17 + 40, f.bonusAttackSpeed(), EPS);
        assertEquals(0.668 * (1 + (2.2 * 17 + 40) / 100), f.attackSpeed(), EPS);
    }

    @Test
    void attackSpeedUsesExplicitRatio() {
        FinalStats f = ChampionBuildCalculator.compute(flat(330, 0.658), 1, StatBlock.of(StatType.ATTACK_SPEED, 40),
                0.625);
        assertEquals(0.658 + 0.625 * 0.4, f.attackSpeed(), EPS);
    }

    @Test
    void invalidRatioFallsBackToBaseAttackSpeed() {
        StatBlock bonus = StatBlock.of(StatType.ATTACK_SPEED, 40);
        FinalStats expected = ChampionBuildCalculator.compute(flat(330, 0.658), 1, bonus);
        assertEquals(expected, ChampionBuildCalculator.compute(flat(330, 0.658), 1, bonus, 0));
        assertEquals(expected, ChampionBuildCalculator.compute(flat(330, 0.658), 1, bonus, Double.NaN));
    }

    @Test
    void attackSpeedIsCappedButRawValueIsKept() {
        FinalStats f = ChampionBuildCalculator.compute(flat(330, 0.625), 1, StatBlock.of(StatType.ATTACK_SPEED, 500));
        assertEquals(0.625 * 6, f.attackSpeedRaw(), EPS);
        assertEquals(ChampionBuildCalculator.ATTACK_SPEED_CAP, f.attackSpeed(), EPS);
        assertEquals(3.003, ChampionBuildCalculator.ATTACK_SPEED_CAP, 1e-3);
        assertEquals(2.0, ChampionBuildCalculator.capAttackSpeed(2.0), EPS);
        assertEquals(ChampionBuildCalculator.ATTACK_SPEED_FLOOR, ChampionBuildCalculator.capAttackSpeed(0.1), EPS);
    }

    // --- Lauftempo ----------------------------------------------------------------------------------

    @Test
    void moveSpeedSoftCapsAtBoundaries() {
        assertEquals(415, ChampionBuildCalculator.applyMoveSpeedCaps(415), EPS);
        assertEquals(416 * 0.8 + 83, ChampionBuildCalculator.applyMoveSpeedCaps(416), EPS);
        assertEquals(475, ChampionBuildCalculator.applyMoveSpeedCaps(490), EPS);
        assertEquals(491 * 0.5 + 230, ChampionBuildCalculator.applyMoveSpeedCaps(491), EPS);
        assertEquals(530, ChampionBuildCalculator.applyMoveSpeedCaps(600), EPS);
        assertEquals(220, ChampionBuildCalculator.applyMoveSpeedCaps(220), EPS);
        assertEquals(219 * 0.5 + 110, ChampionBuildCalculator.applyMoveSpeedCaps(219), EPS);
        assertEquals(200, ChampionBuildCalculator.applyMoveSpeedCaps(180), EPS);
        assertEquals(110, ChampionBuildCalculator.applyMoveSpeedCaps(0), EPS);
        assertEquals(10, ChampionBuildCalculator.applyMoveSpeedCaps(-10000), EPS);
        assertEquals(330, ChampionBuildCalculator.applyMoveSpeedCaps(330), EPS);
    }

    @Test
    void moveSpeedAddsFlatBeforePercentThenCaps() {
        StatBlock bonus = StatBlock.of(StatType.MOVE_SPEED, 45).with(StatType.MOVE_SPEED_PERCENT, 10);
        FinalStats f = ChampionBuildCalculator.compute(flat(345, 0.625), 1, bonus);
        assertEquals((345 + 45) * 1.1, f.moveSpeedRaw(), EPS);
        assertEquals((345 + 45) * 1.1 * 0.8 + 83, f.moveSpeed(), EPS);
        assertEquals(390 * 1.1, ChampionBuildCalculator.rawMoveSpeed(345, 45, 10), EPS);
    }

    // --- Fähigkeitstempo, Krit, Regeneration, effektives Leben --------------------------------------

    @Test
    void hundredAbilityHasteIsFiftyPercentCooldownReduction() {
        FinalStats f = ChampionBuildCalculator.compute(AHRI, 1, StatBlock.of(StatType.ABILITY_HASTE, 100));
        assertEquals(100, f.abilityHaste(), EPS);
        assertEquals(0.5, f.cooldownReduction(), EPS);
        assertEquals(5, f.cooldown(10), EPS);
    }

    @Test
    void critChanceIsCappedAtHundredPercent() {
        assertEquals(100, ChampionBuildCalculator.compute(AHRI, 1, StatBlock.of(StatType.CRIT_CHANCE, 125))
                .critChance(), EPS);
        assertEquals(25, ChampionBuildCalculator.compute(AHRI, 1, StatBlock.of(StatType.CRIT_CHANCE, 25))
                .critChance(), EPS);
    }

    @Test
    void regenerationUsesBasePercentAndFlatBonus() {
        StatBlock bonus = StatBlock.of(StatType.HEALTH_REGEN, 100).with(StatType.HEALTH_REGEN_FLAT, 5)
                .with(StatType.MANA_REGEN, 50);
        FinalStats f = ChampionBuildCalculator.compute(flat(330, 0.625), 1, bonus);
        assertEquals(5 * 2 + 5, f.healthRegen(), EPS);
        assertEquals(7 * 1.5, f.manaRegen(), EPS);
    }

    @Test
    void effectiveHealthAgainstPhysicalAndMagicDamage() {
        StatBlock bonus = StatBlock.of(StatType.HEALTH, 1000).with(StatType.ARMOR, 50).with(StatType.MAGIC_RESIST, 10);
        FinalStats f = ChampionBuildCalculator.compute(flat(330, 0.625), 1, bonus);
        assertEquals(2000, f.maxHealth(), EPS);
        assertEquals(100, f.armor(), EPS);
        assertEquals(50, f.magicResist(), EPS);
        assertEquals(4000, f.effectiveHealthPhysical(), EPS);
        assertEquals(3000, f.effectiveHealthMagic(), EPS);
    }
}
