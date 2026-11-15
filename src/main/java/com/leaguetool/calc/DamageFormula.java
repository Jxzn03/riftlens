package com.leaguetool.calc;

import java.util.List;
import java.util.Objects;

/**
 * Schadensformel einer Fähigkeit: Basisschaden je Rang plus Skalierungen.
 *
 * @param basePerRank Basisschaden für Rang 1, 2, … (darf leer sein = 0)
 * @param scalings    Skalierungen, deren Beiträge addiert werden
 * @param type        Schadensart
 */
public record DamageFormula(List<Double> basePerRank, List<Scaling> scalings, DamageType type) {

    public DamageFormula {
        basePerRank = List.copyOf(basePerRank);
        scalings = List.copyOf(scalings);
        Objects.requireNonNull(type, "type");
    }

    /** Anzahl der Ränge (mindestens 1, auch bei leerer Basisliste). */
    public int maxRank() {
        return Math.max(1, basePerRank.size());
    }

    /** Basisschaden auf {@code rank}; der Rang wird auf 1..{@link #maxRank()} begrenzt. */
    public double baseAtRank(int rank) {
        if (basePerRank.isEmpty()) {
            return 0;
        }
        int index = Math.max(1, Math.min(basePerRank.size(), rank)) - 1;
        return basePerRank.get(index);
    }
}
