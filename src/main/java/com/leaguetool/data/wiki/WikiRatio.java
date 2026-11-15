package com.leaguetool.data.wiki;

import java.util.List;

/**
 * Skalierung einer Fähigkeit, z. B. {@code (+ 50% AP)}.
 *
 * @param percentsPerRank Prozentwert je Rang (z. B. [50, 50, 50, 50, 50])
 * @param stat            normalisierte Stat-Bezeichnung, z. B. "AP", "AD", "bonus AD", "maximum health",
 *                        "bonus health", "armor", "bonus magic resistance", "target's maximum health"
 */
public record WikiRatio(List<Double> percentsPerRank, String stat) {

    public WikiRatio {
        percentsPerRank = List.copyOf(percentsPerRank);
        stat = stat == null ? "" : stat;
    }
}
