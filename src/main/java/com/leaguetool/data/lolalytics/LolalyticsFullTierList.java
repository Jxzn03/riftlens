package com.leaguetool.data.lolalytics;

import java.util.Map;

/**
 * Vollständige Tierliste eines Abrufs: alle Champions, nicht nur die ~20 sichtbar gerenderten.
 *
 * @param patch         aktueller Patch laut Seite, z. B. {@code 16.22}
 * @param analysed      analysierte Champion-Picks
 * @param avgWinRate    durchschnittliche Winrate des Rang-Bereichs (0..1)
 * @param rows          Riot-Champion-Key → Zeile
 * @param slugs         Riot-Champion-Key → Pfadname auf Lolalytics
 */
public record LolalyticsFullTierList(String patch, long analysed, double avgWinRate, Map<Integer, Row> rows,
                                     Map<Integer, String> slugs) {

    public LolalyticsFullTierList {
        rows = Map.copyOf(rows);
        slugs = Map.copyOf(slugs);
    }

    /**
     * @param tierRank 1 = bestes Tier, 0 = ohne Tier (z. B. kaum gespielt auf dieser Lane)
     * @param tier     Anzeige, z. B. {@code S+}; leer ohne Tier
     * @param lane     {@code top}, {@code jungle}, {@code middle}, {@code bottom} oder {@code support}
     * @param laneShare Anteil der Spiele des Champions auf dieser Lane (0..1)
     * @param winRate  0..1
     * @param pickRate 0..1
     * @param banRate  0..1
     * @param pbi      Pick Ban Influence laut Lolalytics; kann 0 oder negativ sein
     */
    public record Row(int tierRank, String tier, String lane, double laneShare, double winRate, double pickRate,
                      double banRate, int pbi, int games) {
    }
}
