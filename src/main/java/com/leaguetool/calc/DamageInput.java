package com.leaguetool.calc;

/**
 * Werte des Angreifers für die Schadensberechnung.
 *
 * @param rank  Rang der Fähigkeit (ab 1)
 * @param level Champion-Level
 * @param ap    Fähigkeitsstärke
 */
public record DamageInput(
        int rank,
        int level,
        double ap,
        double totalAd,
        double bonusAd,
        double maxHealth,
        double bonusHealth,
        double armor,
        double bonusArmor,
        double magicResist,
        double bonusMagicResist) {

    /** Eingabe aus den Gesamtwerten eines Champions für eine Fähigkeit auf Rang {@code rank}. */
    public static DamageInput of(FinalStats stats, int rank) {
        return new DamageInput(rank, stats.level(), stats.abilityPower(), stats.totalAd(), stats.bonusAd(),
                stats.maxHealth(), stats.bonusHealth(), stats.armor(), stats.bonusArmor(),
                stats.magicResist(), stats.bonusMagicResist());
    }
}
