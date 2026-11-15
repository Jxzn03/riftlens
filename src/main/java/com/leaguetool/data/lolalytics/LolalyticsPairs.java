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
     */
    public record Stat(double winRate, int games) {
    }
}
