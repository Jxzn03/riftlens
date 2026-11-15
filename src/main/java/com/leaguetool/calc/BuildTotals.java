package com.leaguetool.calc;

import java.util.List;
import java.util.Objects;

/**
 * Summe eines Builds (siehe {@link BuildCalculator#totals}).
 *
 * @param stats     summierte Item-Werte
 * @param totalGold Summe der Gesamtkosten ({@code gold.total})
 * @param unparsed  nicht erkannte Angaben, jeweils mit Item-Namen davor ({@code "Item: Angabe"})
 * @param itemCount Anzahl der Items
 */
public record BuildTotals(StatBlock stats, int totalGold, List<String> unparsed, int itemCount) {

    public BuildTotals {
        Objects.requireNonNull(stats, "stats");
        unparsed = List.copyOf(unparsed);
    }
}
