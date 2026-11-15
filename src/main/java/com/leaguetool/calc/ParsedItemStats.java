package com.leaguetool.calc;

import java.util.List;
import java.util.Objects;

/**
 * Ergebnis von {@link ItemStatParser#parse}.
 *
 * @param stats    erkannte Werte
 * @param unparsed nicht erkannte Angaben: Zeilen aus dem {@code <stats>}-Block im Klartext
 *                 (z. B. {@code "12 Magiedurchdringung"}) bzw. unbekannte Data-Dragon-Schlüssel
 *                 (z. B. {@code "PercentHPPoolMod = 0.1"})
 */
public record ParsedItemStats(StatBlock stats, List<String> unparsed) {

    public ParsedItemStats {
        Objects.requireNonNull(stats, "stats");
        unparsed = List.copyOf(unparsed);
    }

    /** {@code true}, wenn alle Angaben erkannt wurden. */
    public boolean complete() {
        return unparsed.isEmpty();
    }
}
