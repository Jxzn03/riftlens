package com.leaguetool.data.lolalytics;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Counters aus Sicht des Gegners: Die Eingabe sind Matchups des Champions, die Zeilen die Picks dagegen. */
class LolalyticsCountersTest {

    private static final int GAMES = 1000;
    private static final double OWN_WIN_RATE = 0.5;

    /** Delta 1 = Delta 2 ergibt mit 50 % eigener Winrate einen Tier-Durchschnitt von 50 %. */
    private static LolalyticsPairs.Stat stat(double winRate, double delta1, double delta2) {
        return new LolalyticsPairs.Stat(winRate, GAMES, delta1, delta2);
    }

    private static LolalyticsPairs top(Map<Integer, LolalyticsPairs.Stat> rows) {
        return new LolalyticsPairs(Map.of("top", rows));
    }

    private static List<Integer> champions(List<LolalyticsCounters.Line> lines) {
        return lines.stream().map(LolalyticsCounters.Line::champion).toList();
    }

    private static LolalyticsCounters.Line line(LolalyticsCounters counters, int champion) {
        return counters.delta().stream().filter(l -> l.champion() == champion).findFirst().orElseThrow();
    }

    @Test
    void bestAndWorstPicksFollowThePickWinRateWhileDeltaFollowsTheRating() {
        // Je 1000 Spiele, Dämpfung 1400: Delta 2 zählt zu 1000 / 2400. Durchschnitt 50 %, Pick-WR = 1 − WR + Delta 2 × 1400 / 2400.
        // 1: roh bester Pick (53 %), aber nur durch einen gedämpften Matchup-Effekt bei schwacher Erwartung → 49,5 %.
        // 2: allgemein starker Pick (Erwartung 53 %), roh 51 % → 52,2 %. 3: neutral 50 %. 4: neutral 45 %.
        LolalyticsCounters counters = LolalyticsCounters.of(top(Map.of(
                1, stat(0.47, -0.06, -0.06),
                2, stat(0.49, 0.02, 0.02),
                3, stat(0.50, 0.00, 0.00),
                4, stat(0.55, 0.00, 0.00))), "top", 100, OWN_WIN_RATE);

        assertEquals(List.of(2, 3), champions(counters.best()), "nach Pick-WR, nicht nach roher Winrate (die 1 vorn hätte)");
        assertEquals(0.53 - 0.06 * 1400 / 2400, line(counters, 1).pick(), 1e-12);
        assertEquals(List.of(4, 1), champions(counters.worst()));
        assertEquals(0.51, counters.best().get(0).winRate(), 1e-12, "angezeigte Winrate bleibt die gespiegelte Matchup-Winrate");
        assertEquals(List.of(1, 3, 4, 2), champions(counters.delta()), "stärkster Counter laut Bewertung zuerst");
    }

    @Test
    void valuesAreMirroredToThePicksView() {
        // Durchschnitt = 1 + 0,04 + 0,02 − 0,51 = 0,55; Winrate des Picks = 1,10 − 0,48 = 0,62.
        LolalyticsCounters.Line l = LolalyticsCounters.of(top(Map.of(
                1, new LolalyticsPairs.Stat(0.48, 1400, 0.04, -0.02))), "top", 100, 0.51).delta().get(0);
        assertEquals(0.62, l.winRate(), 1e-12);
        assertEquals(0.62 - 0.49, l.delta1(), 1e-12, "Winrate des Picks minus (1 − Winrate des Champions)");
        assertEquals(0.02, l.delta2(), 1e-12, "Matchup-Effekt mit umgekehrtem Vorzeichen");
        assertEquals(0.01, l.rating(), 1e-12, "bei 1400 Spielen zählt die Hälfte von Delta 2");
        // Gedämpfte Matchup-WR = 0,48 + 0,02 × 0,5 = 0,49; Pick = 1,10 − 0,49.
        assertEquals(0.61, l.pick(), 1e-12);
    }

    @Test
    void theTierAverageIsSharedByAllPicks() {
        // Ohne Deltas fehlt der Durchschnitt dieser Zeile; er gilt aber je Champion und kommt aus den anderen Zeilen.
        LolalyticsCounters counters = LolalyticsCounters.of(top(Map.of(
                1, new LolalyticsPairs.Stat(0.48, GAMES, 0.04, -0.02),
                2, new LolalyticsPairs.Stat(0.50, GAMES, null, null))), "top", 100, 0.51);
        assertEquals(0.60, line(counters, 2).winRate(), 1e-12);
        assertNull(line(counters, 2).delta1());
        assertEquals(0.5, LolalyticsCounters.tierAverage(List.of(), 0.51), 1e-12, "ohne Deltas: 50 %");
    }

    @Test
    void ratingNeedsOnlyDeltaTwo() {
        LolalyticsCounters.Line onlyDeltaTwo = LolalyticsCounters.of(top(Map.of(
                1, new LolalyticsPairs.Stat(0.5, 1400, null, 0.04))), "top", 100, OWN_WIN_RATE).delta().get(0);
        assertEquals(-0.02, onlyDeltaTwo.rating(), 1e-12);

        LolalyticsCounters.Line noDeltaTwo = LolalyticsCounters.of(top(Map.of(
                1, new LolalyticsPairs.Stat(0.5, 700, 0.04, null))), "top", 100, OWN_WIN_RATE).delta().get(0);
        assertNull(noDeltaTwo.rating());
    }

    @Test
    void pickKeepsTheGeneralStrengthThatTheRatingRemoves() {
        // Beide Zeilen mit vielen Spielen: 1 ist allgemein stark und neutral, 2 allgemein schwach, aber ein echter Counter.
        LolalyticsCounters counters = LolalyticsCounters.of(top(Map.of(
                1, new LolalyticsPairs.Stat(0.47, 1_000_000, 0.0, 0.0),
                2, new LolalyticsPairs.Stat(0.48, 1_000_000, -0.03, -0.03))), "top", 100, OWN_WIN_RATE);

        assertTrue(line(counters, 1).pick() > line(counters, 2).pick(), "mit dem allgemein starken Champion gewinnt man häufiger");
        assertTrue(line(counters, 2).rating() > line(counters, 1).rating(), "die Bewertung sieht den echten Counter");
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

        assertEquals(LolalyticsCounters.MAX_LISTED, counters.best().size());
        assertEquals(LolalyticsCounters.MAX_LISTED, counters.worst().size());
        assertEquals(30, counters.delta().size());
        assertEquals(1, counters.best().get(0).champion(), "gegen 1 hat der Champion die niedrigste Winrate");
        assertEquals(30, counters.worst().get(0).champion());
    }

    @Test
    void fewPicksAreNeverBestAndWorstAtOnce() {
        LolalyticsCounters counters = LolalyticsCounters.of(top(Map.of(
                1, stat(0.55, 0, 0), 2, stat(0.5, 0, 0), 3, stat(0.45, 0, 0))), "top", 100, OWN_WIN_RATE);

        assertEquals(List.of(3, 2), champions(counters.best()));
        assertEquals(List.of(1), champions(counters.worst()));
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
                1, new LolalyticsPairs.Stat(0.4, GAMES, null, null),
                2, new LolalyticsPairs.Stat(0.5, GAMES, 0.01, null),
                3, stat(0.6, 0.05, 0.05))), "top", 100, OWN_WIN_RATE);

        assertEquals(1, counters.best().get(0).champion(), "die Listen kommen ohne Deltas aus");
        assertEquals(3, counters.delta().get(0).champion());
        assertNull(counters.delta().get(1).rating());
        assertNull(counters.delta().get(2).rating());
    }
}
