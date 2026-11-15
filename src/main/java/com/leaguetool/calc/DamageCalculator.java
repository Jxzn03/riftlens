package com.leaguetool.calc;

/**
 * Schadensvorschau für Fähigkeiten: Rohschaden aus {@link DamageFormula} und Reduktion durch
 * Rüstung bzw. Magieresistenz (Formeln nach dem League of Legends Wiki).
 *
 * <p>Reihenfolge der Durchdringung: erst prozentual, dann flach (Tödlichkeit bzw. flache
 * Magiedurchdringung). Durchdringung senkt die Resistenz nicht unter 0 und wirkt nicht auf eine
 * Resistenz, die bereits 0 oder negativ ist. Resistenz-Reduktionen (Debuffs) sind nicht abgebildet.
 */
public final class DamageCalculator {

    private DamageCalculator() {
    }

    /** Rohschaden vor Resistenzen: Basisschaden des Rangs plus alle Skalierungen. */
    public static double rawDamage(DamageFormula formula, DamageInput input) {
        double damage = formula.baseAtRank(input.rank());
        for (Scaling s : formula.scalings()) {
            damage += s.contribution(input);
        }
        return damage;
    }

    /**
     * Schadensfaktor bei Resistenz {@code resist}: {@code 100 / (100 + r)} für {@code r >= 0},
     * sonst {@code 2 - 100 / (100 - r)} (100 → 0.5, 0 → 1, −50 → 1.333…).
     */
    public static double mitigationMultiplier(double resist) {
        if (resist >= 0) {
            return 100.0 / (100.0 + resist);
        }
        return 2.0 - 100.0 / (100.0 - resist);
    }

    /**
     * Resistenz des Ziels nach Durchdringung.
     *
     * @param targetResist Rüstung bzw. Magieresistenz des Ziels
     * @param percentPen   prozentuale Durchdringung in Prozent (40 = 40 %, wird auf 0..100 begrenzt)
     * @param flatPen      flache Durchdringung (negative Werte zählen als 0)
     * @return {@code max(0, r * (1 - percentPen/100) - flatPen)}; bei {@code r <= 0} unverändert {@code r}
     */
    public static double effectiveResist(double targetResist, double percentPen, double flatPen) {
        if (targetResist <= 0) {
            return targetResist;
        }
        double pct = Math.max(0, Math.min(100, percentPen));
        double r = targetResist * (1 - pct / 100.0) - Math.max(0, flatPen);
        return Math.max(0, r);
    }

    /**
     * Schaden nach Resistenzen.
     *
     * <ul>
     *   <li>{@link DamageType#PHYSICAL}: Rüstung mit {@link StatType#ARMOR_PENETRATION} (%) und
     *       {@link StatType#LETHALITY} (flach, seit V14.1 unabhängig vom Level)</li>
     *   <li>{@link DamageType#MAGIC}: Magieresistenz mit {@link StatType#MAGIC_PENETRATION_PERCENT} und
     *       {@link StatType#MAGIC_PENETRATION}</li>
     *   <li>{@link DamageType#TRUE}: keine Reduktion</li>
     *   <li>{@link DamageType#MIXED}: nicht definiert (Aufteilung unbekannt), daher unverändert
     *       {@code raw}</li>
     * </ul>
     *
     * @param attackerPen Bonus-Werte des Angreifers (nur die Durchdringung wird gelesen); {@code null} = keine
     */
    public static double damageAfterResist(double raw, DamageType type, double targetArmor, double targetMr,
                                           StatBlock attackerPen) {
        StatBlock pen = attackerPen == null ? StatBlock.EMPTY : attackerPen;
        return switch (type) {
            case PHYSICAL -> raw * mitigationMultiplier(effectiveResist(targetArmor,
                    pen.get(StatType.ARMOR_PENETRATION), pen.get(StatType.LETHALITY)));
            case MAGIC -> raw * mitigationMultiplier(effectiveResist(targetMr,
                    pen.get(StatType.MAGIC_PENETRATION_PERCENT), pen.get(StatType.MAGIC_PENETRATION)));
            case TRUE, MIXED -> raw;
        };
    }

    /** Rohschaden und Reduktion in einem Schritt (siehe {@link #damageAfterResist}). */
    public static double damage(DamageFormula formula, DamageInput input, double targetArmor, double targetMr,
                                StatBlock attackerPen) {
        return damageAfterResist(rawDamage(formula, input), formula.type(), targetArmor, targetMr, attackerPen);
    }

    /**
     * Effektives Leben gegen eine Schadensart mit Resistenz {@code resist}: {@code hp / Schadensfaktor},
     * für {@code resist >= 0} also {@code hp * (1 + resist/100)}.
     */
    public static double effectiveHealth(double hp, double resist) {
        return hp / mitigationMultiplier(resist);
    }
}
