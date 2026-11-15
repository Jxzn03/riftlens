package com.leaguetool.calc;

import com.leaguetool.model.BaseStats;

import java.util.Objects;

/**
 * Gesamtwerte eines Champions aus Basiswerten, Level und Bonus-Werten (z. B. {@link BuildTotals#stats()}).
 *
 * <p>Formeln nach dem League of Legends Wiki (Stand V26.08):
 * <ul>
 *   <li>Angriffstempo: {@code basisAS + ratio * (wachstum% * m(n) + bonusAS%) / 100}; bei
 *       {@code ratio == basisAS} gleich {@code basisAS * (1 + (…)/100)}. Obergrenze 1/0.333 ≈ 3.003
 *       (seit V25.S1.3, vorher 2.5), Untergrenze 0.2.</li>
 *   <li>Lauftempo: {@code (basis + flach) * (1 + prozent/100)}, danach Soft-Caps
 *       (siehe {@link #applyMoveSpeedCaps(double)}).</li>
 *   <li>Effektives Leben: {@code hp * (1 + resist/100)} (für negative Resistenzen siehe
 *       {@link DamageCalculator#effectiveHealth}).</li>
 *   <li>Fähigkeitsstärke: Umrechnungen aus Leben und Mana zählen mit, danach der Multiplikator
 *       ({@link StatType#ABILITY_POWER_AMP}).</li>
 *   <li>Adaptive Kraft: 1 Punkt ergibt 0.6 Bonus-AD oder 1 AP, je nachdem, was höher ist; bei Gleichstand
 *       entscheidet der adaptive Typ des Champions.</li>
 * </ul>
 * Nicht berücksichtigt: bedingte Passiven und Stacks, Champion-Passiven, Runen,
 * Verlangsamungen und die alte Abklingzeitverringerung ({@link StatType#COOLDOWN_REDUCTION}).
 */
public final class ChampionBuildCalculator {

    /** Standard-Obergrenze für das Angriffstempo: 1 Angriff pro 0.333 s (seit V25.S1.3). */
    public static final double ATTACK_SPEED_CAP = 1.0 / 0.333;
    /** Untergrenze für das Angriffstempo: 1 Angriff pro 5 s. */
    public static final double ATTACK_SPEED_FLOOR = 0.2;
    /** Obergrenze der kritischen Trefferchance in Prozent. */
    public static final double CRIT_CHANCE_CAP = 100.0;
    /** Größte Angriffsreichweite eines Nahkämpfers. */
    private static final double MELEE_MAX_RANGE = 325;
    /** Bonus-AD je Punkt adaptiver Kraft; als AP zählt ein Punkt voll. */
    private static final double ADAPTIVE_TO_AD = 0.6;

    private ChampionBuildCalculator() {
    }

    /** Wie {@link #compute(BaseStats, int, StatBlock, double)} mit der Basis-AS als Angriffstempo-Ratio. */
    public static FinalStats compute(BaseStats base, int level, StatBlock bonus) {
        Objects.requireNonNull(base, "base");
        return compute(base, level, bonus, base.attackSpeed());
    }

    /** Wie {@link #compute(BaseStats, int, StatBlock, double, AdaptiveType)} mit physischem adaptivem Typ. */
    public static FinalStats compute(BaseStats base, int level, StatBlock bonus, double attackSpeedRatio) {
        return compute(base, level, bonus, attackSpeedRatio, AdaptiveType.PHYSICAL);
    }

    /**
     * Gesamtwerte auf Level {@code level} (wird auf 1..18 begrenzt).
     *
     * @param bonus            Bonus-Werte; {@code null} = keine
     * @param attackSpeedRatio Angriffstempo-Ratio des Champions (meist gleich der Basis-AS); Werte
     *                         {@code <= 0} oder NaN werden durch die Basis-AS ersetzt
     * @param adaptiveType     entscheidet über adaptive Kraft, solange Bonus-AD und AP gleich sind
     */
    public static FinalStats compute(BaseStats base, int level, StatBlock bonus, double attackSpeedRatio,
                                     AdaptiveType adaptiveType) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(adaptiveType, "adaptiveType");
        StatBlock b = bonus == null ? StatBlock.EMPTY : bonus;
        ChampionLevelStats lvl = StatCalculator.atLevel(base, level);
        double ratio = attackSpeedRatio > 0 && Double.isFinite(attackSpeedRatio)
                ? attackSpeedRatio : base.attackSpeed();

        double bonusMana = b.get(StatType.MANA);
        double maxMana = lvl.mp() + bonusMana;
        double manaRegen = lvl.mpRegen() * (1 + b.get(StatType.MANA_REGEN) / 100.0)
                + b.get(StatType.MANA_REGEN_FLAT);
        double bonusHealth = b.get(StatType.HEALTH) * (1 + b.get(StatType.ITEM_HEALTH_AMP) / 100.0)
                + bonusMana * b.get(StatType.HEALTH_PER_BONUS_MANA) / 100.0;
        double maxHealth = lvl.hp() + bonusHealth;
        double healthRegen = lvl.hpRegen() * (1 + b.get(StatType.HEALTH_REGEN) / 100.0)
                + b.get(StatType.HEALTH_REGEN_FLAT);

        double moveSpeedRaw = rawMoveSpeed(base.moveSpeed(), b.get(StatType.MOVE_SPEED),
                b.get(StatType.MOVE_SPEED_PERCENT));
        double moveSpeed = applyMoveSpeedCaps(moveSpeedRaw);
        double adaptiveForce = b.get(StatType.ADAPTIVE_FORCE)
                + moveSpeed * b.get(StatType.ADAPTIVE_PER_MOVE_SPEED) / 100.0;

        double baseManaRegenFactor = b.get(StatType.MANA_REGEN) / 100.0;
        double apFromRegen = baseManaRegenFactor * b.get(StatType.AP_PER_100_BASE_MANA_REGEN);
        double apBeforeAmp = b.get(StatType.ABILITY_POWER) + apFromRegen
                + bonusHealth * b.get(StatType.AP_PER_BONUS_HEALTH) / 100.0
                + bonusMana * b.get(StatType.AP_PER_BONUS_MANA) / 100.0;
        double ampFactor = b.get(StatType.ABILITY_POWER_AMP) / 100.0;

        // Laut Wiki zählen die Awe-Passiven und Void Infusion nicht zur Wahl der adaptiven Kraft, Rabadon's Bonus schon.
        double adForChoice = b.get(StatType.ATTACK_DAMAGE)
                + lvl.attackDamage() * b.get(StatType.AD_PER_BASE_AD) / 100.0
                + bonusHealth * b.get(StatType.AD_PER_BONUS_HEALTH) / 100.0;
        double apForChoice = b.get(StatType.ABILITY_POWER) + apFromRegen + apBeforeAmp * ampFactor;
        boolean adaptiveToAp = apForChoice > adForChoice
                || (apForChoice == adForChoice && adaptiveType == AdaptiveType.MAGIC);

        double bonusAd = adForChoice + maxMana * b.get(StatType.AD_PER_MAX_MANA) / 100.0
                + (adaptiveToAp ? 0 : adaptiveForce * ADAPTIVE_TO_AD);
        double abilityPower = (apBeforeAmp + (adaptiveToAp ? adaptiveForce : 0)) * (1 + ampFactor);
        double healShieldPower = b.get(StatType.HEAL_SHIELD_POWER)
                + bonusMana * b.get(StatType.HSP_PER_BONUS_MANA) / 100.0
                + baseManaRegenFactor * b.get(StatType.HSP_PER_100_BASE_MANA_REGEN);
        double bonusArmor = b.get(StatType.ARMOR);
        double armor = lvl.armor() + bonusArmor;
        double bonusMr = b.get(StatType.MAGIC_RESIST);
        double magicResist = lvl.magicResist() + bonusMr;

        double bonusAs = StatCalculator.bonusAttackSpeedPercent(base.attackSpeedPerLevel(), lvl.level())
                + b.get(StatType.ATTACK_SPEED);
        double attackSpeedRaw = StatCalculator.attackSpeed(base.attackSpeed(), ratio, bonusAs);

        StatType hastePerBonusAd = lvl.attackRange() <= MELEE_MAX_RANGE
                ? StatType.HASTE_PER_BONUS_AD_MELEE : StatType.HASTE_PER_BONUS_AD_RANGED;
        double haste = b.get(StatType.ABILITY_HASTE) + bonusAd * b.get(hastePerBonusAd) / 100.0;
        double crit = clamp(lvl.critChance() + b.get(StatType.CRIT_CHANCE), 0, CRIT_CHANCE_CAP);

        return new FinalStats(
                lvl.level(),
                maxHealth, bonusHealth, healthRegen,
                maxMana, bonusMana, manaRegen,
                lvl.attackDamage() + bonusAd, bonusAd,
                abilityPower,
                armor, bonusArmor, magicResist, bonusMr,
                bonusAs, attackSpeedRaw, capAttackSpeed(attackSpeedRaw),
                lvl.attackRange(),
                moveSpeedRaw, moveSpeed,
                haste, CooldownCalculator.cooldownReduction(haste),
                crit,
                healShieldPower,
                DamageCalculator.effectiveHealth(maxHealth, armor),
                DamageCalculator.effectiveHealth(maxHealth, magicResist),
                b);
    }

    /** Begrenzt das Angriffstempo auf {@link #ATTACK_SPEED_FLOOR}..{@link #ATTACK_SPEED_CAP}. */
    public static double capAttackSpeed(double rawAttackSpeed) {
        return clamp(rawAttackSpeed, ATTACK_SPEED_FLOOR, ATTACK_SPEED_CAP);
    }

    /** Lauftempo vor den Soft-Caps: {@code (basis + flach) * (1 + prozent/100)}. */
    public static double rawMoveSpeed(double baseMoveSpeed, double flatBonus, double percentBonus) {
        return (baseMoveSpeed + flatBonus) * (1 + percentBonus / 100.0);
    }

    /**
     * Soft-Caps des Lauftempos (Wiki "Movement speed"):
     * <ul>
     *   <li>über 490: {@code x * 0.5 + 230}</li>
     *   <li>über 415 bis 490: {@code x * 0.8 + 83}</li>
     *   <li>220 bis 415: unverändert</li>
     *   <li>0 bis unter 220: {@code x * 0.5 + 110}</li>
     *   <li>unter 0: {@code x * 0.01 + 110}</li>
     * </ul>
     * Die Abschnitte gehen stetig ineinander über (415 → 415, 490 → 475, 220 → 220, 0 → 110).
     */
    public static double applyMoveSpeedCaps(double raw) {
        if (raw > 490) {
            return raw * 0.5 + 230;
        }
        if (raw > 415) {
            return raw * 0.8 + 83;
        }
        if (raw >= 220) {
            return raw;
        }
        if (raw >= 0) {
            return raw * 0.5 + 110;
        }
        return raw * 0.01 + 110;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
