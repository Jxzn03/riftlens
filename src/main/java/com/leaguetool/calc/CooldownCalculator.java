package com.leaguetool.calc;

/** Abklingzeiten mit Ability Haste: {@code cd * 100 / (100 + haste)}. */
public final class CooldownCalculator {

    private CooldownCalculator() {
    }

    /** Effektive Abklingzeit bei {@code haste} Ability Haste (negative Werte werden als 0 behandelt). */
    public static double cooldown(double baseCooldown, double haste) {
        return baseCooldown * 100.0 / (100.0 + Math.max(0, haste));
    }

    /** Entsprechende prozentuale Abklingzeitverringerung (0..1), z. B. 100 Haste → 0.5. */
    public static double cooldownReduction(double haste) {
        double h = Math.max(0, haste);
        return h / (100.0 + h);
    }
}
