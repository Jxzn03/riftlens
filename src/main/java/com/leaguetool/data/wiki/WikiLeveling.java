package com.leaguetool.data.wiki;

import java.util.List;

/**
 * Ein Wert einer Fähigkeit, der mit dem Rang skaliert (aus {@code {{st|Label|Wert}}} im Parameter
 * {@code leveling}).
 *
 * @param label         Bezeichnung, z. B. "Damage Per Pass"
 * @param valuesPerRank Basiswert je Rang (leer, wenn nicht erkennbar)
 * @param ratios        Skalierungen {@code (+ X% STAT)}
 * @param text          vollständiger Wert als Klartext, z. B. "35 – 135 (+ 50% AP)"
 */
public record WikiLeveling(String label, List<Double> valuesPerRank, List<WikiRatio> ratios, String text) {

    public WikiLeveling {
        label = label == null ? "" : label;
        valuesPerRank = List.copyOf(valuesPerRank);
        ratios = List.copyOf(ratios);
        text = text == null ? "" : text;
    }
}
