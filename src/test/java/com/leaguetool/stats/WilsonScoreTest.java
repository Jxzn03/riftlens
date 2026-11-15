package com.leaguetool.stats;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WilsonScoreTest {

    @Test
    void fiveOfTen() {
        assertEquals(0.2366, WilsonScore.lowerBound(5, 10), 1e-4);
        assertEquals(0.7634, WilsonScore.upperBound(5, 10), 1e-4);
        assertEquals(0.2366, WilsonScore.lowerBound(5, 10, WilsonScore.DEFAULT_Z), 1e-4);
    }

    @Test
    void noGamesGivesZero() {
        assertEquals(0, WilsonScore.lowerBound(0, 0));
        assertEquals(0, WilsonScore.upperBound(0, 0));
    }

    @Test
    void perfectRecordStaysBelowOne() {
        double lower = WilsonScore.lowerBound(100, 100);
        assertTrue(lower < 1, "lower = " + lower);
        assertTrue(lower > 0.95, "lower = " + lower);
        assertEquals(1.0, WilsonScore.upperBound(100, 100), 1e-12);
        assertEquals(0.0, WilsonScore.lowerBound(0, 100), 1e-12);
    }

    @Test
    void largerSampleNarrowsInterval() {
        double small = WilsonScore.upperBound(5, 10) - WilsonScore.lowerBound(5, 10);
        double large = WilsonScore.upperBound(500, 1000) - WilsonScore.lowerBound(500, 1000);
        assertTrue(large < small);
        assertTrue(WilsonScore.lowerBound(550, 1000) > WilsonScore.lowerBound(2, 2));
    }

    @Test
    void zeroZGivesRawWinRate() {
        assertEquals(0.3, WilsonScore.lowerBound(3, 10, 0), 1e-12);
        assertEquals(0.3, WilsonScore.upperBound(3, 10, 0), 1e-12);
    }

    @Test
    void invalidInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> WilsonScore.lowerBound(11, 10));
        assertThrows(IllegalArgumentException.class, () -> WilsonScore.lowerBound(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> WilsonScore.upperBound(1, 10, -1));
    }

    @Test
    void statLineDerivedValuesAndSorting() {
        StatLine<String> a = new StatLine<>("a", 10, 5, 0.5);
        StatLine<String> b = new StatLine<>("b", 2, 2, 0.1);
        StatLine<String> c = new StatLine<>("c", 40, 26, 0.4);
        assertEquals(0.5, a.winRate());
        assertEquals(0.2366, a.wilsonLower(), 1e-4);
        assertEquals(0.7634, a.wilsonUpper(), 1e-4);
        assertEquals(0, new StatLine<>("x", 0, 0, 0).winRate());

        assertEquals(List.of(c, a, b), StatLine.mostFrequent(List.of(a, b, c)));
        assertEquals(List.of(c, a), StatLine.bestByWilson(List.of(a, b, c), 5), "b unter Mindest-Stichprobe");
        assertEquals(List.of(c, b, a), StatLine.bestByWilson(List.of(a, b, c), 0));

        StatRanking<String> ranking = StatRanking.of(List.of(a, b, c), 5);
        assertEquals(c, ranking.top().orElseThrow());
        assertEquals(c, ranking.topBest().orElseThrow());
        assertEquals(5, ranking.minGames());
        assertTrue(StatRanking.of(List.<StatLine<String>>of(), 3).isEmpty());
        assertTrue(StatRanking.of(List.<StatLine<String>>of(), 3).top().isEmpty());

        assertThrows(IllegalArgumentException.class, () -> new StatLine<>("x", 1, 2, 0));
    }
}
