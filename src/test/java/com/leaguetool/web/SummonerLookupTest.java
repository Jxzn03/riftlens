package com.leaguetool.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SummonerLookupTest {

    @Test
    void durationIsNormalizedToSeconds() {
        ObjectNode info = new ObjectMapper().createObjectNode();
        assertEquals(-1, SummonerLookup.durationSeconds(info));
        info.put("gameDuration", 1_500_000);
        assertEquals(1500, SummonerLookup.durationSeconds(info));
        info.put("gameEndTimestamp", 123L);
        info.put("gameDuration", 1500);
        assertEquals(1500, SummonerLookup.durationSeconds(info));
    }

    @Test
    void arenaIsRecognisedByGameModeForAnyQueueNumber() {
        assertEquals("Arena", SummonerLookup.modeLabel(1750, "CHERRY"));
        assertEquals("Arena", SummonerLookup.modeLabel(1700, "CHERRY"));
    }

    @Test
    void undocumentedQueuesKeepModeAndNumber() {
        assertEquals("Summoner's Rift (Queue 9999)", SummonerLookup.modeLabel(9999, "CLASSIC"));
        assertEquals("Queue 9999", SummonerLookup.modeLabel(9999, ""));
    }
}
