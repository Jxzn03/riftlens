package com.leaguetool.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummonerHistoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ME = "p1";

    /** Match mit {@code ME} in Team 100; {@code teammates} im selben Team, {@code enemies} in Team 200. */
    static ObjectNode info(long creation, List<String> teammates, List<String> enemies) {
        ObjectNode info = JSON.createObjectNode().put("queueId", 420).put("gameMode", "CLASSIC")
                .put("gameDuration", 1800).put("gameCreation", creation).put("gameEndTimestamp", creation + 1_800_000);
        ArrayNode participants = info.putArray("participants");
        participants.addObject().put("puuid", ME).put("teamId", 100).put("championId", 103).put("win", true);
        teammates.forEach(p -> participants.addObject().put("puuid", p).put("teamId", 100)
                .put("riotIdGameName", "Name-" + p).put("riotIdTagline", "EUW"));
        enemies.forEach(p -> participants.addObject().put("puuid", p).put("teamId", 200)
                .put("riotIdGameName", "Name-" + p).put("riotIdTagline", "EUW"));
        return info;
    }

    @Test
    void storesOwnRowAndOnlyTeammates() throws Exception {
        SummonerHistory history = new SummonerHistory(ME);
        assertTrue(history.add("M1", info(1, List.of("duo", "BOT"), List.of("foe"))));
        var match = history.match("M1").orElseThrow();
        assertEquals(103, match.path("p").path("championId").asInt());
        assertFalse(match.path("p").has("puuid"), "die eigene PUUID steht nur einmal in der Datei");
        assertEquals(1, match.path("mates").size(), "Gegner und KI-Spieler zählen nicht");
        assertEquals("duo", history.mate(match.path("mates").get(0).asInt()).puuid());
        assertFalse(history.add("M2", info(2, List.of(), List.of()).set("participants", JSON.createArrayNode())),
                "Spieler fehlt im Match");
    }

    @Test
    void arenaPairsBySubteamAndLegacyByParticipantOrder() {
        ObjectNode arena = JSON.createObjectNode();
        ObjectNode own = arena.objectNode().put("playerSubteamId", 3).put("teamId", 0);
        assertTrue(SummonerHistory.sameTeam(own, 0, arena.objectNode().put("playerSubteamId", 3).put("teamId", 0), 5, 16));
        assertFalse(SummonerHistory.sameTeam(own, 0, arena.objectNode().put("playerSubteamId", 4).put("teamId", 0), 1, 16));

        ObjectNode legacy = arena.objectNode();
        assertTrue(SummonerHistory.sameTeam(legacy, 1, legacy, 4, 10));
        assertFalse(SummonerHistory.sameTeam(legacy, 1, legacy, 5, 10));
        assertFalse(SummonerHistory.sameTeam(legacy, 1, legacy, 2, 16), "ohne teamId nur bei 5 gegen 5");
    }

    @Test
    void gzipRoundTripKeepsNewestMatchesAndReferencedMates() throws Exception {
        SummonerHistory history = new SummonerHistory(ME);
        for (int i = 0; i < SummonerHistory.MAX_STORED + 5; i++) {
            history.add("M" + i, info(i, List.of("duo", "solo-" + i), List.of()));
        }
        history.markGone("WEG");
        byte[] gzip = history.changedGzip();
        assertNotNull(gzip);
        assertEquals(0x1f, gzip[0] & 0xff, "gzip-Kennung");
        assertNull(history.changedGzip(), "unverändert wird nicht erneut geschrieben");

        SummonerHistory read = SummonerHistory.read(ME, gzip);
        assertEquals(SummonerHistory.MAX_STORED, read.size());
        assertFalse(read.contains("M0"), "älteste fallen weg");
        assertTrue(read.contains("M" + (SummonerHistory.MAX_STORED + 4)));
        assertTrue(read.contains("WEG"));
        var newest = read.match("M" + (SummonerHistory.MAX_STORED + 4)).orElseThrow();
        assertEquals("duo", read.mate(newest.path("mates").get(0).asInt()).puuid());
        assertEquals("solo-" + (SummonerHistory.MAX_STORED + 4), read.mate(newest.path("mates").get(1).asInt()).puuid());
    }

    @Test
    void newestRiotIdWinsAndForeignFilesAreIgnored() throws Exception {
        SummonerHistory history = new SummonerHistory(ME);
        ObjectNode later = info(200, List.of("duo"), List.of());
        ((ObjectNode) later.path("participants").get(1)).put("riotIdGameName", "Neu");
        history.add("M2", later);
        history.add("M1", info(100, List.of("duo"), List.of()));
        int ref = history.match("M1").orElseThrow().path("mates").get(0).asInt();
        assertEquals("Neu", history.mate(ref).name(), "ein älteres Match überschreibt den neueren Namen nicht");

        byte[] gzip = history.changedGzip();
        assertEquals(0, SummonerHistory.read("jemand-anders", gzip).size());
    }
}
