package com.leaguetool.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leaguetool.stats.StatLine;
import com.leaguetool.stats.StatRanking;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DtoRankingFillTest {

    /** Boots von Aatrox: nur drei erreichen die Mindest-Stichprobe von 3.685 Spielen. */
    private static final StatRanking<Integer> BOOTS = StatRanking.of(List.of(
            line(3047, 230_698, 0.5171), line(3111, 102_527, 0.5328), line(3008, 8_059, 0.5754),
            line(3009, 3_604, 0.5669), line(3158, 2_278, 0.5575), line(3006, 40, 0.50), line(3020, 0, 0)), 3_685);

    @Test
    void fillsFreeSlotsWithMostFrequentBelowMinimum() {
        List<Map<String, Object>> lines = lines(Dto.ranking(BOOTS, k -> k, 6, true));
        assertEquals(6, lines.size());
        for (int i = 0; i < 3; i++) {
            assertNull(lines.get(i).get("belowMin"));
        }
        assertEquals(List.of(3009, 3158, 3006), lines.subList(3, 6).stream().map(m -> m.get("key")).toList());
        assertTrue(lines.subList(3, 6).stream().allMatch(m -> Boolean.TRUE.equals(m.get("belowMin"))));
    }

    @Test
    void withoutFillOnlyEntriesAboveMinimum() {
        assertEquals(3, lines(Dto.ranking(BOOTS, k -> k, 6, false)).size());
    }

    private static StatLine<Integer> line(int id, int games, double winRate) {
        return new StatLine<>(id, games, (int) Math.round(games * winRate), games / 368_644.0);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lines(Map<String, Object> ranking) {
        return (List<Map<String, Object>>) ranking.get("lines");
    }
}
