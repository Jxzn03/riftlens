package com.leaguetool.calc;

import com.leaguetool.util.Text;

/**
 * Bonus-Werte, wie sie Items (und später Runen) gewähren. Die Enum-Reihenfolge ist zugleich die
 * Anzeigereihenfolge (siehe {@link StatBlock#entries()}).
 *
 * <p>Bei {@link #percent()} {@code == true} ist der Wert eine Prozentzahl (25 = 25 %), sonst ein
 * flacher Wert. Flache und prozentuale Variante desselben Werts (z. B. Lauftempo) tragen dasselbe
 * Label; unterschieden werden sie über {@link #percent()} bzw. {@link #format(double)}.
 */
public enum StatType {
    HEALTH("Leben", false),
    MANA("Mana", false),
    ATTACK_DAMAGE("Angriffsschaden", false),
    ABILITY_POWER("Fähigkeitsstärke", false),
    /** Adaptive Kraft (wird in {@link ChampionBuildCalculator} zu AD oder AP). */
    ADAPTIVE_FORCE("Adaptive Kraft", false),
    ARMOR("Rüstung", false),
    MAGIC_RESIST("Magieresistenz", false),
    /** Zusätzliches Angriffstempo in Prozent (wird mit der Angriffstempo-Ratio verrechnet). */
    ATTACK_SPEED("Angriffstempo", true),
    CRIT_CHANCE("Kritische Trefferchance", true),
    /** Zusätzlicher kritischer Schaden (z. B. Infinity Edge). */
    CRIT_DAMAGE("Kritischer Schaden", true),
    /** Flaches Lauftempo. */
    MOVE_SPEED("Lauftempo", false),
    /** Prozentuales Lauftempo. */
    MOVE_SPEED_PERCENT("Lauftempo", true),
    ABILITY_HASTE("Fähigkeitstempo", false),
    /** Alte Abklingzeitverringerung; kommt nur noch bei Items anderer Modi vor. */
    COOLDOWN_REDUCTION("Abklingzeitverringerung", true),
    /** Flache Rüstungsdurchdringung (seit V14.1 unabhängig vom Level). */
    LETHALITY("Tödlichkeit", false),
    /** Prozentuale Rüstungsdurchdringung. */
    ARMOR_PENETRATION("Rüstungsdurchdringung", true),
    /** Flache Magiedurchdringung. */
    MAGIC_PENETRATION("Magiedurchdringung", false),
    /** Prozentuale Magiedurchdringung. */
    MAGIC_PENETRATION_PERCENT("Magiedurchdringung", true),
    LIFE_STEAL("Lebensraub", true),
    OMNIVAMP("Omnivampir", true),
    /** Prozent der Basis-Lebensregeneration ("Base Health Regen"). */
    HEALTH_REGEN("Grundlebensregeneration", true),
    /** Flache Lebensregeneration pro 5 Sekunden. */
    HEALTH_REGEN_FLAT("Lebensregeneration pro 5 s", false),
    /** Prozent der Basis-Manaregeneration ("Base Mana Regen"). */
    MANA_REGEN("Grundmanaregeneration", true),
    /** Flache Manaregeneration pro 5 Sekunden. */
    MANA_REGEN_FLAT("Manaregeneration pro 5 s", false),
    HEAL_SHIELD_POWER("Heil- und Schildstärke", true),
    TENACITY("Zähigkeit", true),
    GOLD_PER_10("Gold pro 10 Sekunden", false),
    /** Multiplikator auf die gesamte Fähigkeitsstärke (Rabadon's Deathcap). */
    ABILITY_POWER_AMP("Fähigkeitsstärke (Multiplikator)", true),
    /** Multiplikator auf das Leben aus Items (Warmog's Armor). */
    ITEM_HEALTH_AMP("Leben aus Items (Multiplikator)", true),
    /** Prozent des Bonus-Lebens als Fähigkeitsstärke. */
    AP_PER_BONUS_HEALTH("Fähigkeitsstärke aus Bonus-Leben", true),
    /** Prozent des Bonus-Lebens als Angriffsschaden. */
    AD_PER_BONUS_HEALTH("Angriffsschaden aus Bonus-Leben", true),
    /** Prozent des Bonus-Manas als Fähigkeitsstärke. */
    AP_PER_BONUS_MANA("Fähigkeitsstärke aus Bonus-Mana", true),
    /** Prozent des maximalen Manas als Angriffsschaden. */
    AD_PER_MAX_MANA("Angriffsschaden aus maximalem Mana", true),
    /** Prozent des Bonus-Manas als Leben. */
    HEALTH_PER_BONUS_MANA("Leben aus Bonus-Mana", true),
    /** Prozent des Basis-Angriffsschadens (auf dem aktuellen Level) als Angriffsschaden. */
    AD_PER_BASE_AD("Angriffsschaden aus Basis-AD", true),
    /** Fähigkeitsstärke je 100 % Grundmanaregeneration (anteilig). */
    AP_PER_100_BASE_MANA_REGEN("Fähigkeitsstärke je 100 % Grundmanaregeneration", false),
    /** Heil- und Schildstärke in Prozent je 100 % Grundmanaregeneration (anteilig). */
    HSP_PER_100_BASE_MANA_REGEN("Heil- und Schildstärke je 100 % Grundmanaregeneration", true),
    /** Prozent des Bonus-Manas als Heil- und Schildstärke (1000 Mana bei 0,5 % = 5 %). */
    HSP_PER_BONUS_MANA("Heil- und Schildstärke aus Bonus-Mana", true),
    /** Prozent des Bonus-AD als Fähigkeitstempo bei Nahkämpfern. */
    HASTE_PER_BONUS_AD_MELEE("Fähigkeitstempo aus Bonus-AD (Nahkampf)", true),
    /** Prozent des Bonus-AD als Fähigkeitstempo bei Fernkämpfern. */
    HASTE_PER_BONUS_AD_RANGED("Fähigkeitstempo aus Bonus-AD (Fernkampf)", true),
    /** Prozent des gesamten Lauftempos als adaptive Kraft. */
    ADAPTIVE_PER_MOVE_SPEED("Adaptive Kraft aus Lauftempo", true),
    /** Fähigkeitstempo nur für Grundfähigkeiten (Q, W, E). */
    BASIC_ABILITY_HASTE("Fähigkeitstempo für Grundfähigkeiten", false),
    /** Fähigkeitstempo nur für die Ultimate. */
    ULTIMATE_HASTE("Fähigkeitstempo für die Ultimate", false),
    /** Fähigkeitstempo nur für Fähigkeiten mit Immobilisierung. */
    IMMOBILIZE_ABILITY_HASTE("Fähigkeitstempo für Immobilisierungen", false);

    private final String label;
    private final boolean percent;

    StatType(String label, boolean percent) {
        this.label = label;
        this.percent = percent;
    }

    /** Deutsche Bezeichnung für die Oberfläche. */
    public String label() {
        return label;
    }

    /** {@code true}, wenn der Wert in Prozent angegeben ist (25 = 25 %). */
    public boolean percent() {
        return percent;
    }

    /** Nur der Wert mit Einheit, z. B. {@code "45"} oder {@code "4 %"}. */
    public String formatValue(double value) {
        return percent ? Text.num(value) + " %" : Text.num(value);
    }

    /** Wert mit Label, z. B. {@code "45 Lauftempo"} oder {@code "4 % Lauftempo"}. */
    public String format(double value) {
        return formatValue(value) + " " + label;
    }
}
