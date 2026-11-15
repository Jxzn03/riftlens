package com.leaguetool.data.lolalytics;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LolalyticsCountersTest {

    private static final int GAMES = 1000;

    private static LolalyticsPairs.Stat stat(double winRate, double delta1, double delta2) {
        return new LolalyticsPairs.Stat(winRate, GAMES, delta1, delta2);
    }

    private static LolalyticsPairs top(Map<Integer, LolalyticsPairs.Stat> rows) {
        return new LolalyticsPairs(Map.of("top", rows));
    }

    private static List<Integer> champions(List<LolalyticsCounters.Line> lines) {
        return lines.stream().map(LolalyticsCounters.Line::champion).toList();
    }

    @Test
    void strongAndWeakFollowOnlyTheWinRateWhileDeltaFollowsTheRating() {
        // Champion 1 hat die beste Winrate, aber die schlechtesten Deltas; 3 ist umgekehrt.
        LolalyticsCounters counters = LolalyticsCounters.of(top(Map.of(
                1, stat(0.56, -0.03, -0.03),
                2, stat(0.50, 0.00, 0.00),
                3, stat(0.44, 0.03, 0.03),
                4, stat(0.47, 0.01, 0.01))), "top", 100);

        assertEquals(List.of(1, 2), champions(counters.strong()));
        assertEquals(List.of(3, 4), champions(counters.weak()));
        assertEquals(List.of(3, 4, 2, 1), champions(counters.delta()));
    }

    @Test
    void ratingCountsDeltaTwoTwiceAndDampsSmallSamples() {
        LolalyticsCounters counters = LolalyticsCounters.of(top(Map.of(
                1, new LolalyticsPairs.Stat(0.5, 500, 0.03, 0.06))), "top", 100);
        // (0,03 + 2 × 0,06) / 3 = 0,05; bei 500 Spielen zählt davon die Hälfte
        assertEquals(0.025, counters.delta().get(0).rating(), 1e-12);
    }

    @Test
    void listsAreLimitedAndDeltaStaysComplete() {
        Map<Integer, LolalyticsPairs.Stat> rows = new HashMap<>();
        IntStream.range(1, 31).forEach(i -> rows.put(i, stat(0.4 + i / 200.0, 0, 0)));
        LolalyticsCounters counters = LolalyticsCounters.of(top(rows), "top", 100);

        assertEquals(LolalyticsCounters.MAX_LISTED, counters.strong().size());
        assertEquals(LolalyticsCounters.MAX_LISTED, counters.weak().size());
        assertEquals(30, counters.delta().size());
        assertEquals(30, counters.strong().get(0).champion());
        assertEquals(1, counters.weak().get(0).champion());
    }

    @Test
    void fewOpponentsAreNeverStrongAndWeakAtOnce() {
        LolalyticsCounters counters = LolalyticsCounters.of(top(Map.of(
                1, stat(0.55, 0, 0), 2, stat(0.5, 0, 0), 3, stat(0.45, 0, 0))), "top", 100);

        assertEquals(List.of(1, 2), champions(counters.strong()));
        assertEquals(List.of(3), champions(counters.weak()));
    }

    @Test
    void matchupsBelowTheMinimumAreDropped() {
        LolalyticsCounters counters = LolalyticsCounters.of(top(Map.of(
                1, new LolalyticsPairs.Stat(0.9, 99, 0.1, 0.1),
                2, new LolalyticsPairs.Stat(0.5, 100, 0.0, 0.0))), "top", 100);

        assertEquals(List.of(2), champions(counters.delta()));
        assertEquals(100, counters.minGames());
    }

    @Test
    void onlyTheRequestedLaneCounts() {
        LolalyticsPairs pairs = new LolalyticsPairs(Map.of("top", Map.of(1, stat(0.5, 0, 0)), "middle", Map.of(2, stat(0.5, 0, 0))));

        assertEquals(List.of(2), champions(LolalyticsCounters.of(pairs, "middle", 100).delta()));
        assertTrue(LolalyticsCounters.of(pairs, "", 100).delta().isEmpty(), "unbekannte Lane bleibt leer");
    }

    @Test
    void missingDeltasLeaveTheRatingEmptyAndSortLast() {
        LolalyticsCounters counters = LolalyticsCounters.of(top(Map.of(
                1, new LolalyticsPairs.Stat(0.6, GAMES, null, null),
                2, new LolalyticsPairs.Stat(0.5, GAMES, 0.01, null),
                3, stat(0.4, -0.05, -0.05))), "top", 100);

        assertEquals(1, counters.strong().get(0).champion(), "Strong kommt ohne Deltas aus");
        assertEquals(3, counters.delta().get(0).champion());
        assertNull(counters.delta().get(1).rating());
        assertNull(counters.delta().get(2).rating());
    }
}
