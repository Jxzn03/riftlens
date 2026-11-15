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
    private static final double OWN_WIN_RATE = 0.5;

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
                4, stat(0.47, 0.01, 0.01))), "top", 100, OWN_WIN_RATE);

        assertEquals(List.of(1, 2), champions(counters.strong()));
        assertEquals(List.of(3, 4), champions(counters.weak()));
        assertEquals(List.of(3, 4, 2, 1), champions(counters.delta()));
    }

    @Test
    void ratingUsesOnlyDeltaTwoAndDampsSmallSamples() {
        LolalyticsCounters counters = LolalyticsCounters.of(top(Map.of(
                1, new LolalyticsPairs.Stat(0.5, 700, 0.09, 0.04))), "top", 100, OWN_WIN_RATE);
        assertEquals(0.02, counters.delta().get(0).rating(), 1e-12, "bei 700 Spielen zählt die Hälfte von Delta 2");
    }

    @Test
    void ratingNeedsOnlyDeltaTwo() {
        LolalyticsCounters.Line onlyDeltaTwo = LolalyticsCounters.of(top(Map.of(
                1, new LolalyticsPairs.Stat(0.5, 700, null, 0.04))), "top", 100, OWN_WIN_RATE).delta().get(0);
        assertEquals(0.02, onlyDeltaTwo.rating(), 1e-12);

        LolalyticsCounters.Line noDeltaTwo = LolalyticsCounters.of(top(Map.of(
                1, new LolalyticsPairs.Stat(0.5, 700, 0.04, null))), "top", 100, OWN_WIN_RATE).delta().get(0);
        assertNull(noDeltaTwo.rating());
    }

    @Test
    void pickMirrorsTheDampedWinRateAroundTheTierAverage() {
        LolalyticsCounters.Line line = LolalyticsCounters.of(top(Map.of(
                1, new LolalyticsPairs.Stat(0.48, 700, 0.04, -0.02))), "top", 100, 0.51).delta().get(0);
        // Durchschnitt = 1 + 0,04 + 0,02 − 0,51 = 0,55; gedämpfte WR = 0,48 + 0,02 × 0,5 = 0,49; Pick = 1,10 − 0,49
        assertEquals(0.61, line.pick(), 1e-12);
    }

    @Test
    void pickKeepsTheGeneralStrengthThatTheRatingRemoves() {
        // Beide Zeilen mit vielen Spielen: 1 ist allgemein stark und neutral, 2 allgemein schwach, aber ein echter Counter.
        LolalyticsCounters counters = LolalyticsCounters.of(top(Map.of(
                1, new LolalyticsPairs.Stat(0.47, 1_000_000, 0.0, 0.0),
                2, new LolalyticsPairs.Stat(0.48, 1_000_000, -0.03, -0.03))), "top", 100, OWN_WIN_RATE);

        LolalyticsCounters.Line strong = counters.delta().stream().filter(l -> l.champion() == 1).findFirst().orElseThrow();
        LolalyticsCounters.Line counter = counters.delta().stream().filter(l -> l.champion() == 2).findFirst().orElseThrow();
        assertTrue(strong.pick() > counter.pick(), "mit dem allgemein starken Champion gewinnt man häufiger");
        assertTrue(counter.rating() < strong.rating(), "die Bewertung sieht den echten Counter");
    }

    @Test
    void missingDeltaLeavesThePickEmpty() {
        assertNull(LolalyticsCounters.of(top(Map.of(1, new LolalyticsPairs.Stat(0.5, 700, null, 0.04))), "top", 100, OWN_WIN_RATE)
                .delta().get(0).pick());
        assertNull(LolalyticsCounters.of(top(Map.of(1, new LolalyticsPairs.Stat(0.5, 700, 0.04, null))), "top", 100, OWN_WIN_RATE)
                .delta().get(0).pick());
    }

    @Test
    void listsAreLimitedAndDeltaStaysComplete() {
        Map<Integer, LolalyticsPairs.Stat> rows = new HashMap<>();
        IntStream.range(1, 31).forEach(i -> rows.put(i, stat(0.4 + i / 200.0, 0, 0)));
        LolalyticsCounters counters = LolalyticsCounters.of(top(rows), "top", 100, OWN_WIN_RATE);

        assertEquals(LolalyticsCounters.MAX_LISTED, counters.strong().size());
        assertEquals(LolalyticsCounters.MAX_LISTED, counters.weak().size());
        assertEquals(30, counters.delta().size());
        assertEquals(30, counters.strong().get(0).champion());
        assertEquals(1, counters.weak().get(0).champion());
    }

    @Test
    void fewOpponentsAreNeverStrongAndWeakAtOnce() {
        LolalyticsCounters counters = LolalyticsCounters.of(top(Map.of(
                1, stat(0.55, 0, 0), 2, stat(0.5, 0, 0), 3, stat(0.45, 0, 0))), "top", 100, OWN_WIN_RATE);

        assertEquals(List.of(1, 2), champions(counters.strong()));
        assertEquals(List.of(3), champions(counters.weak()));
    }

    @Test
    void matchupsBelowTheMinimumAreDropped() {
        LolalyticsCounters counters = LolalyticsCounters.of(top(Map.of(
                1, new LolalyticsPairs.Stat(0.9, 99, 0.1, 0.1),
                2, new LolalyticsPairs.Stat(0.5, 100, 0.0, 0.0))), "top", 100, OWN_WIN_RATE);

        assertEquals(List.of(2), champions(counters.delta()));
        assertEquals(100, counters.minGames());
    }

    @Test
    void onlyTheRequestedLaneCounts() {
        LolalyticsPairs pairs = new LolalyticsPairs(Map.of("top", Map.of(1, stat(0.5, 0, 0)), "middle", Map.of(2, stat(0.5, 0, 0))));

        assertEquals(List.of(2), champions(LolalyticsCounters.of(pairs, "middle", 100, OWN_WIN_RATE).delta()));
        assertTrue(LolalyticsCounters.of(pairs, "", 100, OWN_WIN_RATE).delta().isEmpty(), "unbekannte Lane bleibt leer");
    }

    @Test
    void missingDeltasLeaveTheRatingEmptyAndSortLast() {
        LolalyticsCounters counters = LolalyticsCounters.of(top(Map.of(
                1, new LolalyticsPairs.Stat(0.6, GAMES, null, null),
                2, new LolalyticsPairs.Stat(0.5, GAMES, 0.01, null),
                3, stat(0.4, -0.05, -0.05))), "top", 100, OWN_WIN_RATE);

        assertEquals(1, counters.strong().get(0).champion(), "Strong kommt ohne Deltas aus");
        assertEquals(3, counters.delta().get(0).champion());
        assertNull(counters.delta().get(1).rating());
        assertNull(counters.delta().get(2).rating());
    }
}
