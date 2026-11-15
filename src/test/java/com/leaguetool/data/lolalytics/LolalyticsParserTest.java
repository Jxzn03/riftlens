package com.leaguetool.data.lolalytics;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LolalyticsParserTest {

    private static String sample() throws IOException {
        try (var in = LolalyticsParserTest.class.getResourceAsStream("/lolalytics/tierlist_sample.html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesPatchAverageAndRows() throws IOException {
        LolalyticsTierList list = LolalyticsParser.parse(sample());
        assertEquals("16.22", list.patch());
        assertEquals(0.5159, list.avgWinRate(), 1e-9);
        assertEquals(3, list.champions().size());

        LolalyticsChampion jinx = list.find("Jinx").orElseThrow();
        assertEquals("jinx", jinx.slug());
        assertEquals(1, jinx.rank());
        assertEquals("S+", jinx.tier());
        assertEquals(0.5315, jinx.winRate(), 1e-9);
        assertEquals(0.0190, jinx.winRateDelta(), 1e-9);
        assertEquals(0.1112, jinx.pickRate(), 1e-9);
        assertEquals(0.0567, jinx.banRate(), 1e-9);
        assertEquals(91_764, jinx.games());
    }

    @Test
    void findNormalizesNames() throws IOException {
        LolalyticsTierList list = LolalyticsParser.parse(sample());
        assertTrue(list.find("Cho'Gath").isPresent() || list.find("Chogath").isPresent());
        assertTrue(list.find("Unbekannt").isEmpty());
    }

    @Test
    void pageWithoutRowsFailsClearly() {
        IOException e = assertThrows(IOException.class,
                () -> LolalyticsParser.parse("<html><body>Just a moment...</body></html>"));
        assertTrue(e.getMessage().contains("Lolalytics"));
    }
}
