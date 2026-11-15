package com.leaguetool.calc;

/**
 * Abklingzeiten mit Haste laut Wiki (Haste): {@code cd * 100 / (100 + haste)}, Verringerung {@code haste / (100 + haste)}.
 *
 * <p>Ability Haste, Basic Ability Haste und Ultimate Haste addieren sich; die Summe ist auf {@link #HASTE_CAP} begrenzt
 * (83,33 % Verringerung). Dieselbe Formel gilt für Summoner Spell Haste und Item Haste, für die das Wiki keinen Cap
 * nennt. Statische Abklingzeiten verringert Haste nicht.
 */
public final class CooldownCalculator {

    /** Obergrenze für Ability Haste samt Basic und Ultimate Haste. */
    public static final double HASTE_CAP = 500;

    private CooldownCalculator() {
    }

    /** Effektive Abklingzeit bei {@code haste} (negative Werte zählen als 0, mehr als {@link #HASTE_CAP} als Cap). */
    public static double cooldown(double baseCooldown, double haste) {
        return baseCooldown * 100.0 / (100.0 + capped(haste));
    }

    /** Entsprechende prozentuale Abklingzeitverringerung (0..1), z. B. 100 Haste → 0.5. */
    public static double cooldownReduction(double haste) {
        double h = capped(haste);
        return h / (100.0 + h);
    }

    /** Haste auf 0..{@link #HASTE_CAP} begrenzt. */
    public static double capped(double haste) {
        return Math.min(HASTE_CAP, Math.max(0, haste));
    }

    /** Abklingzeit mit Summoner Spell Haste (ohne Cap laut Wiki). */
    public static double summonerSpellCooldown(double baseCooldown, double summonerSpellHaste) {
        return baseCooldown * 100.0 / (100.0 + Math.max(0, summonerSpellHaste));
    }
}
