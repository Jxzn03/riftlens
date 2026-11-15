package com.leaguetool.calc;

import com.leaguetool.model.BaseStats;

/**
 * Level-Skalierung von Champion-Werten nach der offiziellen Formel
 * {@code base + growth * (n - 1) * (0.7025 + 0.0175 * (n - 1))}.
 */
public final class StatCalculator {

    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 18;

    private StatCalculator() {
    }

    /** Wachstumsfaktor für Level {@code n} (0 auf Level 1, 17 auf Level 18). */
    public static double growthMultiplier(int level) {
        int n = clampLevel(level);
        return (n - 1) * (0.7025 + 0.0175 * (n - 1));
    }

    /** Wert eines linear wachsenden Stats (HP, Rüstung, AD …) auf Level {@code level}. */
    public static double statAtLevel(double base, double growth, int level) {
        return base + growth * growthMultiplier(level);
    }

    /**
     * Angriffstempo auf Level {@code level} ohne Items: {@code base * (1 + growthPercent/100 * m(n))}.
     *
     * @param growthPercent Data-Dragon-{@code attackspeedperlevel} (Prozent)
     */
    public static double attackSpeedAtLevel(double baseAttackSpeed, double growthPercent, int level) {
        return attackSpeed(baseAttackSpeed, baseAttackSpeed, bonusAttackSpeedPercent(growthPercent, level));
    }

    /** Bonus-Angriffstempo in Prozent allein aus dem Level-Wachstum. */
    public static double bonusAttackSpeedPercent(double growthPercent, int level) {
        return growthPercent * growthMultiplier(level);
    }

    /**
     * Angriffstempo {@code base + ratio * bonusPercent / 100} (einzige Stelle der Formel). Die Ratio ist
     * meist gleich der Basis-AS, bei manchen Champions abweichend (Wiki {@code as_ratio}).
     */
    public static double attackSpeed(double baseAttackSpeed, double ratio, double bonusPercent) {
        return baseAttackSpeed + ratio * bonusPercent / 100.0;
    }

    public static int clampLevel(int level) {
        return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
    }

    /**
     * Alle Werte eines Champions auf Level {@code level} (wird auf 1..18 begrenzt), ohne Items.
     * Lauftempo und Reichweite wachsen nicht mit dem Level; das Angriffstempo nutzt die Basis-AS als
     * Ratio (siehe {@link ChampionBuildCalculator} für abweichende Ratios).
     */
    public static ChampionLevelStats atLevel(BaseStats stats, int level) {
        int n = clampLevel(level);
        return new ChampionLevelStats(
                n,
                statAtLevel(stats.hp(), stats.hpPerLevel(), n),
                statAtLevel(stats.hpRegen(), stats.hpRegenPerLevel(), n),
                statAtLevel(stats.mp(), stats.mpPerLevel(), n),
                statAtLevel(stats.mpRegen(), stats.mpRegenPerLevel(), n),
                statAtLevel(stats.armor(), stats.armorPerLevel(), n),
                statAtLevel(stats.spellBlock(), stats.spellBlockPerLevel(), n),
                statAtLevel(stats.attackDamage(), stats.attackDamagePerLevel(), n),
                attackSpeedAtLevel(stats.attackSpeed(), stats.attackSpeedPerLevel(), n),
                stats.attackRange(),
                stats.moveSpeed(),
                statAtLevel(stats.crit(), stats.critPerLevel(), n));
    }
}
