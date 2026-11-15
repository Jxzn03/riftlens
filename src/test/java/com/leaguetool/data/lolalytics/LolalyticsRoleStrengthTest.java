package com.leaguetool.data.lolalytics;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LolalyticsRoleStrengthTest {

    private static LolalyticsFullTierList.Row row(double share, double winRate, double pickRate, double banRate, int games) {
        return new LolalyticsFullTierList.Row(1, "S", "top", share, winRate, pickRate, banRate, 0, games);
    }

    @Test
    void usesGamesWeightedRoleAverageAndDampsLowPickRates() {
        Map<Integer, Double> strength = LolalyticsRoleStrength.compute(Map.of(
                1, row(1.0, 0.54, 0.09, 0.10, 500),
                2, row(1.0, 0.50, 0.01, 0.00, 500)));
        // Rollen-Durchschnitt 52 %; P = 9 / 0.9 = 10 → +2 × 10/11; P = 1 → −2 × 1/2
        assertEquals(2.0 * 10.0 / 11.0, strength.get(1), 1e-9);
        assertEquals(-1.0, strength.get(2), 1e-9);
    }

    @Test
    void banRateRaisesTheEffectivePickRate() {
        Map<Integer, Double> strength = LolalyticsRoleStrength.compute(Map.of(
                1, row(1.0, 0.54, 0.02, 0.50, 500),
                2, row(1.0, 0.54, 0.02, 0.00, 500),
                3, row(1.0, 0.50, 0.02, 0.00, 1000)));
        assertTrue(strength.get(1) > strength.get(2));
    }

    @Test
    void championsBelowMinimumLaneShareGetNoValueButStillCountForTheAverage() {
        Map<Integer, Double> strength = LolalyticsRoleStrength.compute(Map.of(
                1, row(0.14, 0.60, 0.01, 0.00, 100),
                2, row(0.15, 0.50, 0.01, 0.00, 100)));
        assertFalse(strength.containsKey(1));
        assertEquals(-2.5, strength.get(2), 1e-9);   // Durchschnitt 55 %: −5 × 1/2
    }

    @Test
    void emptyListHasNoValues() {
        assertTrue(LolalyticsRoleStrength.compute(Map.of()).isEmpty());
    }
}
