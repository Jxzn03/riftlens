package com.leaguetool.data.lolalytics;

import java.util.HashMap;
import java.util.Map;

/**
 * Stärke-Indikator eines Champions auf einer Rolle:
 * {@code (Winrate − Durchschnitt der Rolle) × P / (P + 1)} mit {@code P = Pickrate / (1 − Banrate)} in Prozent.
 *
 * <p>Der Abstand zum Rollen-Durchschnitt ist das Stärkesignal. Der Faktor dämpft selten gespielte Picks, deren
 * Winrate meist von Spezialisten stammt, und die Banrate gleicht aus, dass oft gebannte Champions seltener
 * spielbar sind. Eigene Gewichtung dieses Tools, kein Wert von Lolalytics.
 */
public final class LolalyticsRoleStrength {

    /** Unter diesem Anteil der Spiele eines Champions auf der Rolle gibt es keinen Wert (Off-Role-Spezialisten). */
    static final double MIN_LANE_SHARE = 0.15;

    private LolalyticsRoleStrength() {
    }

    /**
     * @param rows Zeilen der Tierliste einer einzelnen Rolle
     * @return Champion-Key → Stärke in Prozentpunkten; Champions unter dem Mindestanteil fehlen
     */
    public static Map<Integer, Double> compute(Map<Integer, LolalyticsFullTierList.Row> rows) {
        double games = 0;
        double wins = 0;
        for (LolalyticsFullTierList.Row row : rows.values()) {
            games += row.games();
            wins += row.games() * row.winRate();
        }
        Map<Integer, Double> strength = new HashMap<>();
        if (games == 0) {
            return strength;
        }
        double roleAverage = wins / games;
        rows.forEach((key, row) -> {
            if (row.laneShare() >= MIN_LANE_SHARE && row.banRate() < 1) {
                double p = row.pickRate() * 100.0 / (1 - row.banRate());
                strength.put(key, (row.winRate() - roleAverage) * 100.0 * p / (p + 1));
            }
        });
        return strength;
    }
}
