package com.leaguetool.data.lolalytics;

/**
 * Eine Zeile der Lolalytics-Tierliste. Raten sind Anteile (0..1), keine Prozentwerte.
 *
 * @param slug         URL-Name, z. B. {@code ahri}
 * @param name         Anzeigename
 * @param rank         Rang in der Tierliste
 * @param tier         Tier-Buchstabe (S+, S, A, …)
 * @param lane         Lane-Name aus dem Icon (top, jungle, middle, bottom, support) oder {@code null}
 * @param laneShare    Anteil der Spiele in dieser Lane am Champion (0..1)
 * @param winRate      Winrate
 * @param winRateDelta Abweichung der Winrate vom Durchschnitt (Prozentpunkte/100, kann negativ sein)
 * @param pickRate     Pickrate
 * @param banRate      Banrate
 * @param pbi          Pick-Ban-Index-Rang laut Seite
 * @param games        Anzahl Spiele
 */
public record LolalyticsChampion(String slug, String name, int rank, String tier, String lane, double laneShare,
                                 double winRate, double winRateDelta, double pickRate, double banRate, int pbi,
                                 int games) {
}
