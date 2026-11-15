package com.leaguetool.calc;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Unveränderliche Sammlung von Bonus-Werten ({@link StatType} → Wert).
 *
 * <p>Werte von 0 gelten als "nicht vorhanden" und werden nicht gespeichert; {@link #get(StatType)}
 * liefert für fehlende Einträge 0. Alle Änderungsmethoden geben einen neuen Block zurück.
 */
public final class StatBlock {

    /** Leerer Block. */
    public static final StatBlock EMPTY = new StatBlock(new EnumMap<>(StatType.class));

    private final EnumMap<StatType, Double> values;

    private StatBlock(EnumMap<StatType, Double> values) {
        this.values = values;
    }

    /** Block mit genau einem Wert. */
    public static StatBlock of(StatType type, double value) {
        return EMPTY.with(type, value);
    }

    /** Block aus einer Map (Kopie; Einträge mit Wert 0 werden verworfen). */
    public static StatBlock of(Map<StatType, Double> values) {
        EnumMap<StatType, Double> copy = new EnumMap<>(StatType.class);
        for (Map.Entry<StatType, Double> e : values.entrySet()) {
            double v = checked(e.getValue());
            if (v != 0.0) {
                copy.put(Objects.requireNonNull(e.getKey(), "type"), v);
            }
        }
        return copy.isEmpty() ? EMPTY : new StatBlock(copy);
    }

    /** Wert für {@code type}, 0 wenn nicht vorhanden. */
    public double get(StatType type) {
        return values.getOrDefault(type, 0.0);
    }

    /** {@code true}, wenn {@code type} einen Wert ungleich 0 hat. */
    public boolean has(StatType type) {
        return values.containsKey(type);
    }

    /** Summen mit kleinerem Betrag gelten als 0 (Gleitkomma-Reste wie 0.1 + 0.2 - 0.3). */
    private static final double ZERO_EPSILON = 1e-9;

    /** Summe beider Blöcke (Werte gleicher Typen werden addiert; Summe 0 entfernt den Eintrag). */
    public StatBlock plus(StatBlock other) {
        if (other.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return other;
        }
        EnumMap<StatType, Double> sum = new EnumMap<>(values);
        for (Map.Entry<StatType, Double> e : other.values.entrySet()) {
            double v = sum.getOrDefault(e.getKey(), 0.0) + e.getValue();
            if (Math.abs(v) < ZERO_EPSILON) {
                sum.remove(e.getKey());
            } else {
                sum.put(e.getKey(), v);
            }
        }
        return sum.isEmpty() ? EMPTY : new StatBlock(sum);
    }

    /** Kopie mit {@code type} auf {@code value} gesetzt (0 entfernt den Eintrag). */
    public StatBlock with(StatType type, double value) {
        Objects.requireNonNull(type, "type");
        double v = checked(value);
        EnumMap<StatType, Double> copy = new EnumMap<>(values);
        if (v == 0.0) {
            copy.remove(type);
        } else {
            copy.put(type, v);
        }
        return copy.isEmpty() ? EMPTY : new StatBlock(copy);
    }

    /** Alle Einträge in Enum-Reihenfolge (unveränderliche Liste). */
    public List<Map.Entry<StatType, Double>> entries() {
        return values.entrySet().stream()
                .map(e -> Map.entry(e.getKey(), e.getValue()))
                .toList();
    }

    /** Unveränderliche Sicht auf die Werte (Enum-Reihenfolge). */
    public Map<StatType, Double> asMap() {
        return Collections.unmodifiableMap(values);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public int size() {
        return values.size();
    }

    private static double checked(Double value) {
        Objects.requireNonNull(value, "value");
        if (value.isNaN() || value.isInfinite()) {
            throw new IllegalArgumentException("Ungültiger Wert: " + value);
        }
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StatBlock other && values.equals(other.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "StatBlock" + values;
    }
}
