package com.leaguetool.data.lolalytics;

import java.util.HashMap;
import java.util.Map;

/**
 * Werte eines Champions zusammen mit oder gegen andere Champions, getrennt nach deren Lane.
 *
 * @param byLane Lane des anderen Champions → Riot-Champion-Key → Werte
 */
public record LolalyticsPairs(Map<String, Map<Integer, Stat>> byLane) {

    public LolalyticsPairs {
        Map<String, Map<Integer, Stat>> copy = new HashMap<>();
        byLane.forEach((lane, rows) -> copy.put(lane, Map.copyOf(rows)));
        byLane = Map.copyOf(copy);
    }

    /**
     * @param winRate Winrate des Champions in dieser Paarung (0..1)
     * @param games   Spiele dieser Paarung
     * @param delta1  Delta 1 laut Lolalytics (Prozentpunkte/100); {@code null}, wenn die Quelle keines liefert
     * @param delta2  Delta 2 laut Lolalytics (Prozentpunkte/100); {@code null}, wenn die Quelle keines liefert
     */
    public record Stat(double winRate, int games, Double delta1, Double delta2) {
    }
}
