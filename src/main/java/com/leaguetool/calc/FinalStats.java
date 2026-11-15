package com.leaguetool.calc;

import com.leaguetool.model.AbilitySlot;

import java.util.Objects;

/**
 * Gesamtwerte eines Champions auf einem Level mit Bonus-Werten (Items), berechnet von
 * {@link ChampionBuildCalculator#compute}. "Gesamt" bedeutet Basis auf dem Level plus Bonus.
 *
 * @param level                   Level 1..18 (bereits begrenzt)
 * @param maxHealth               Gesamt-Leben
 * @param bonusHealth             Leben aus Bonus-Werten
 * @param healthRegen             Lebensregeneration pro 5 Sekunden
 * @param maxMana                 Gesamt-Mana
 * @param bonusMana               Mana aus Bonus-Werten
 * @param manaRegen               Manaregeneration pro 5 Sekunden
 * @param totalAd                 Gesamt-Angriffsschaden
 * @param bonusAd                 Angriffsschaden aus Bonus-Werten
 * @param abilityPower            Fähigkeitsstärke inklusive Item-Umrechnungen und Multiplikator
 *                                (ohne Umrechnung von adaptiver Kraft)
 * @param armor                   Gesamt-Rüstung
 * @param bonusArmor              Rüstung aus Bonus-Werten
 * @param magicResist             Gesamt-Magieresistenz
 * @param bonusMagicResist        Magieresistenz aus Bonus-Werten
 * @param bonusAttackSpeed        zusätzliches Angriffstempo in Prozent (Level-Wachstum + Bonus)
 * @param attackSpeedRaw          Angriffstempo ohne Obergrenze
 * @param attackSpeed             Angriffstempo, begrenzt auf
 *                                {@link ChampionBuildCalculator#ATTACK_SPEED_FLOOR}..{@link ChampionBuildCalculator#ATTACK_SPEED_CAP}
 * @param attackRange             Reichweite
 * @param moveSpeedRaw            Lauftempo vor den Soft-Caps
 * @param moveSpeed               Lauftempo nach den Soft-Caps
 * @param abilityHaste            Fähigkeitstempo für alle Fähigkeiten (ungekappt, ohne Basic/Ultimate Haste)
 * @param cooldownReduction       daraus folgende Abklingzeitverringerung (0..1, 100 Haste = 0.5, Cap 500 Haste)
 * @param critChance              kritische Trefferchance in Prozent (0..100)
 * @param healShieldPower         Heil- und Schildstärke in Prozent
 * @param effectiveHealthPhysical effektives Leben gegen normalen Schaden
 * @param effectiveHealthMagic    effektives Leben gegen magischen Schaden
 * @param bonus                   die zugrunde liegenden Bonus-Werte (z. B. für Durchdringung)
 */
public record FinalStats(
        int level,
        double maxHealth,
        double bonusHealth,
        double healthRegen,
        double maxMana,
        double bonusMana,
        double manaRegen,
        double totalAd,
        double bonusAd,
        double abilityPower,
        double armor,
        double bonusArmor,
        double magicResist,
        double bonusMagicResist,
        double bonusAttackSpeed,
        double attackSpeedRaw,
        double attackSpeed,
        double attackRange,
        double moveSpeedRaw,
        double moveSpeed,
        double abilityHaste,
        double cooldownReduction,
        double critChance,
        double healShieldPower,
        double effectiveHealthPhysical,
        double effectiveHealthMagic,
        StatBlock bonus) {

    public FinalStats {
        Objects.requireNonNull(bonus, "bonus");
    }

    /**
     * Fähigkeitstempo für eine bestimmte Fähigkeit: Ability Haste plus Basic Ability Haste (Q, W, E) bzw. Ultimate
     * Haste (R) und Haste für Immobilisierungen. Die Arten addieren sich, die Summe ist laut Wiki auf
     * {@link CooldownCalculator#HASTE_CAP} begrenzt.
     *
     * @param immobilizes {@code true}, wenn die Fähigkeit Gegner immobilisiert
     */
    public double abilityHaste(AbilitySlot slot, boolean immobilizes) {
        double specific = switch (slot) {
            case Q, W, E -> bonus.get(StatType.BASIC_ABILITY_HASTE);
            case R -> bonus.get(StatType.ULTIMATE_HASTE);
            case P -> 0;
        };
        return CooldownCalculator.capped(
                abilityHaste + specific + (immobilizes ? bonus.get(StatType.IMMOBILIZE_ABILITY_HASTE) : 0));
    }

    /** Summoner Spell Haste (Beschwörerzauber); wirkt nicht auf Fähigkeiten. */
    public double summonerSpellHaste() {
        return bonus.get(StatType.SUMMONER_SPELL_HASTE);
    }

    /** Abklingzeit einer Fähigkeit mit dem Fähigkeitstempo dieses Champions. */
    public double cooldown(double baseCooldown) {
        return CooldownCalculator.cooldown(baseCooldown, abilityHaste);
    }

    /** Abklingzeit eines Beschwörerzaubers mit der Summoner Spell Haste dieses Builds. */
    public double summonerSpellCooldown(double baseCooldown) {
        return CooldownCalculator.summonerSpellCooldown(baseCooldown, summonerSpellHaste());
    }
}
