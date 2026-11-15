package com.leaguetool.calc;

/**
 * Werte eines Champions auf einem Level, ohne Items und Runen (siehe {@link StatCalculator#atLevel}).
 *
 * @param level       Level 1..18 (bereits begrenzt)
 * @param hpRegen     Lebensregeneration pro 5 Sekunden
 * @param mpRegen     Manaregeneration pro 5 Sekunden
 * @param attackSpeed Angriffe pro Sekunde
 * @param critChance  kritische Trefferchance in Prozent
 */
public record ChampionLevelStats(
        int level,
        double hp,
        double hpRegen,
        double mp,
        double mpRegen,
        double armor,
        double magicResist,
        double attackDamage,
        double attackSpeed,
        double attackRange,
        double moveSpeed,
        double critChance) {
}
