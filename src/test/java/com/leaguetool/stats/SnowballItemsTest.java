package com.leaguetool.stats;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnowballItemsTest {

    private static StatLine<Integer> line(int item) {
        return new StatLine<>(item, 1000, 500, 0.1);
    }

    @Test
    void mejaisMovesBehindTheOtherEntriesInStableOrder() {
        List<StatLine<Integer>> lines = List.of(line(3041), line(6655), line(3118));
        assertEquals(List.of(6655, 3118, 3041),
                SnowballItems.last(lines, SnowballItems::is).stream().map(StatLine::key).toList());
    }

    @Test
    void mejaisStaysOnTheLastShownPlace() {
        List<StatLine<Integer>> lines = List.of(line(3041), line(1), line(2), line(3), line(4));
        assertEquals(List.of(1, 2, 3041, 3, 4),
                SnowballItems.lastVisible(lines, SnowballItems::is, 3).stream().map(StatLine::key).toList());
        assertEquals(List.of(1, 2, 3, 4, 3041),
                SnowballItems.lastVisible(lines, SnowballItems::is, 6).stream().map(StatLine::key).toList(), "weniger Einträge als Plätze");
    }

    @Test
    void pathsWithASnowballItemAreRecognised() {
        assertTrue(SnowballItems.anyIn(List.of(3020, 3041, 6655)));
        assertFalse(SnowballItems.anyIn(List.of(3020, 6655)));
    }
}
