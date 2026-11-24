package com.leaguetool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leaguetool.data.lolalytics.LolalyticsFullTierList;
import com.leaguetool.model.Role;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummonerRecommendationsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Map<String, Object> row(int champion, String position, boolean win) {
        return Dto.obj("queueId", 420, "remake", false, "championKey", champion, "position", position, "win", win);
    }

    private static LolalyticsFullTierList.Row tier(double winRate, double pickRate, double banRate) {
        return new LolalyticsFullTierList.Row(1, "S", "jungle", 0.9, winRate, pickRate, banRate, 0, 10_000);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("items");
    }

    @Test
    void recommendsUnplayedStrongChampionsOfTheMainPosition() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(row(1, "Jungle", i < 7)); // Assassine, viel gespielt und erfolgreich
        }
        rows.add(row(2, "Top", false));
        Map<Integer, LolalyticsFullTierList.Row> list = new HashMap<>();
        list.put(1, tier(0.55, 0.10, 0.10)); // gespielt: fällt weg
        list.put(3, tier(0.53, 0.05, 0.01)); // stark, Assassine, kaum gebannt
        list.put(4, tier(0.47, 0.05, 0.01)); // schwach
        Map<Integer, List<String>> tags = Map.of(1, List.of("Assassin"), 3, List.of("Assassin"), 4, List.of("Tank"));
        Map<Role, LolalyticsFullTierList> lists = Map.of(Role.JUNGLE,
                new LolalyticsFullTierList("16.23", 1_000_000, 0.5, list, Map.of()));

        Map<String, Object> result = SummonerRecommendations.compute(rows, Map.of(3, 2), tags, lists);

        assertEquals("ok", result.get("status"));
        assertEquals("JUNGLE", result.get("mainRole"));
        List<Map<String, Object>> items = items(result);
        assertEquals(List.of(3, 4), items.stream().map(i -> i.get("championKey")).toList(), "gespielter Champion fehlt");
        @SuppressWarnings("unchecked")
        List<String> codes = ((List<Map<String, Object>>) items.getFirst().get("tags")).stream().map(t -> (String) t.get("code")).toList();
        assertEquals(List.of("main_position_match", "archetype_fit", "new_for_summoner", "honey_champion", "low_ban_rate"), codes);
        assertEquals(2, items.getFirst().get("mastery"));
    }

    @Test
    void familiarChampionsSubPositionAndClassMixAreConsidered() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            rows.add(row(1, "Jungle", i < 6));
        }
        for (int i = 0; i < 4; i++) {
            rows.add(row(2, "Top", true));
        }
        Map<Integer, LolalyticsFullTierList.Row> jungle = new HashMap<>();
        Map<Integer, List<String>> tags = new HashMap<>(Map.of(1, List.of("Assassin"), 2, List.of("Fighter")));
        for (int k = 10; k < 20; k++) {
            jungle.put(k, tier(0.53, 0.05, 0.05));
            tags.put(k, List.of("Assassin"));
        }
        jungle.put(20, tier(0.50, 0.05, 0.05));
        tags.put(20, List.of("Tank"));
        Map<Integer, LolalyticsFullTierList.Row> top = Map.of(30, new LolalyticsFullTierList.Row(1, "S", "top", 0.9, 0.53, 0.05, 0.05, 0, 10_000));
        tags.put(30, List.of("Fighter"));
        Map<Role, LolalyticsFullTierList> lists = Map.of(
                Role.JUNGLE, new LolalyticsFullTierList("16.23", 1_000_000, 0.5, jungle, Map.of()),
                Role.TOP, new LolalyticsFullTierList("16.23", 1_000_000, 0.5, top, Map.of()));

        List<Map<String, Object>> items = items(SummonerRecommendations.compute(rows, Map.of(20, 7), tags, lists));

        List<Object> keys = items.stream().map(i -> i.get("championKey")).toList();
        assertEquals(SummonerRecommendations.COUNT, keys.size());
        assertTrue(keys.contains(20), "schwächerer Tank kommt dank Klassen-Mix trotz zehn Assassinen hinein");
        assertTrue(keys.contains(30), "Kandidat der Nebenposition");
        Map<String, Object> tank = items.stream().filter(i -> i.get("championKey").equals(20)).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> codes = ((List<Map<String, Object>>) tank.get("tags")).stream().map(t -> (String) t.get("code")).toList();
        assertTrue(codes.contains("familiar") && !codes.contains("new_for_summoner"), codes.toString());
    }

    @Test
    void otherOwnAccountsCountAsPlayedButWeighLess() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            rows.add(row(1, "Jungle", true));
        }
        List<Map<String, Object>> other = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            other.add(row(3, "Top", true)); // Zweitkonto: Champion 3 oft gespielt, andere Position
        }
        Map<Integer, LolalyticsFullTierList.Row> list = new HashMap<>();
        list.put(3, tier(0.53, 0.05, 0.01));
        list.put(4, tier(0.52, 0.05, 0.01));
        Map<Integer, List<String>> tags = Map.of(1, List.of("Assassin"), 3, List.of("Assassin"), 4, List.of("Tank"));
        Map<Role, LolalyticsFullTierList> lists = Map.of(Role.JUNGLE, new LolalyticsFullTierList("16.23", 1_000_000, 0.5, list, Map.of()));

        Map<String, Object> alone = SummonerRecommendations.compute(rows, Map.of(), tags, lists);
        Map<String, Object> together = SummonerRecommendations.compute(rows, other, Map.of(), tags, lists);

        assertTrue(items(alone).stream().anyMatch(i -> i.get("championKey").equals(3)));
        assertFalse(items(together).stream().anyMatch(i -> i.get("championKey").equals(3)), "auf dem Zweitkonto schon gespielt");
        assertEquals("JUNGLE", together.get("mainRole"), "10 halb gewichtete Top-Spiele (5) überwiegen 6 Jungle-Spiele nicht");
    }

    @Test
    @SuppressWarnings("unchecked")
    void bansFavourLaneOpponentsYouLoseAgainstAndNeverRepeatPicks() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Map<String, Object> r = new HashMap<>(row(1, "Jungle", false));
            r.put("opponent", Dto.obj("key", 7, "name", "Gegner"));
            rows.add(r);
        }
        Map<Integer, LolalyticsFullTierList.Row> list = new HashMap<>();
        for (int k = 10; k < 22; k++) {
            list.put(k, tier(0.52, 0.05, 0.01));
        }
        list.put(7, tier(0.49, 0.05, 0.01)); // schwach laut Meta, aber 0:4 gegen dich
        list.put(1, tier(0.55, 0.05, 0.01)); // eigener Champion: nie als Bann
        Map<Role, LolalyticsFullTierList> lists = Map.of(Role.JUNGLE, new LolalyticsFullTierList("16.23", 1_000_000, 0.5, list, Map.of()));

        Map<String, Object> result = SummonerRecommendations.compute(rows, Map.of(), Map.of(), lists);

        List<Map<String, Object>> bans = (List<Map<String, Object>>) result.get("bans");
        assertEquals(SummonerRecommendations.BAN_COUNT, bans.size());
        assertEquals(7, bans.getFirst().get("championKey"));
        assertEquals(SummonerRecommendations.COUNT, items(result).size());
        List<Object> picks = items(result).stream().map(i -> i.get("championKey")).toList();
        assertTrue(bans.stream().noneMatch(b -> picks.contains(b.get("championKey")) || b.get("championKey").equals(1)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aLongLosingRecordOutweighsAShortOne() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            rows.add(vs(8, false)); // 0:3
        }
        for (int i = 0; i < 11; i++) {
            rows.add(vs(9, i < 3)); // 3:8
        }
        Map<Integer, LolalyticsFullTierList.Row> list = new HashMap<>();
        for (int k = 8; k < 22; k++) {
            list.put(k, tier(0.51, 0.05, 0.01));
        }
        Map<Role, LolalyticsFullTierList> lists = Map.of(Role.JUNGLE, new LolalyticsFullTierList("16.23", 1_000_000, 0.5, list, Map.of()));

        List<Map<String, Object>> bans = (List<Map<String, Object>>) SummonerRecommendations.compute(rows, Map.of(), Map.of(), lists).get("bans");

        assertEquals(List.of(9, 8), bans.stream().limit(2).map(b -> b.get("championKey")).toList(), "3:8 wiegt schwerer als 0:3");
    }

    private static Map<String, Object> vs(int opponent, boolean win) {
        Map<String, Object> r = new HashMap<>(row(1, "Jungle", win));
        r.put("opponent", Dto.obj("key", opponent, "name", "Gegner"));
        return r;
    }
    @Test
    void withoutCachedTierListThereIsAHintInsteadOfAFetch() {
        Map<String, Object> result = SummonerRecommendations.compute(List.of(row(1, "Mid", true)), Map.of(), Map.of(), Map.of());
        assertEquals("no-meta", result.get("status"));
        assertEquals("Mid", result.get("mainRoleLabel"));
        assertEquals("no-games", SummonerRecommendations.compute(List.of(), Map.of(), Map.of(), Map.of()).get("status"));
    }

    private static JsonNode stored(int champion, int opponent, boolean win, int queue) {
        ObjectNode m = JSON.createObjectNode().put("x", SummonerHistory.SCHEMA).put("queueId", queue).put("gameDuration", 1800)
                .put("gameEndTimestamp", 1L).put("gameCreation", 1L);
        m.putObject("p").put("championId", champion).put("win", win).put("kills", 3).put("deaths", 1).put("assists", 2);
        if (opponent > 0) {
            m.putObject("o").put("championId", opponent);
        }
        return m;
    }

    @Test
    @SuppressWarnings("unchecked")
    void teamRankedCountsAsRiftQueue() {
        Map<String, Object> result = OwnMatchups.of(List.of(stored(91, 163, false, 710), stored(91, 163, true, 420)));
        assertEquals(2, result.get("games"), "5v5 Teamranked (710) läuft auf Summoner's Rift mit festen Positionen");
        Map<String, Object> vs = (Map<String, Object>) ((Map<String, Object>) result.get("vs")).get("163");
        assertEquals(2, vs.get("games"));

        List<Map<String, Object>> rows = List.of(
                Dto.obj("queueId", 710, "remake", false, "championKey", 1, "position", "Top", "win", true),
                Dto.obj("queueId", 710, "remake", false, "championKey", 1, "position", "Top", "win", true),
                row(2, "Jungle", true));
        assertEquals("TOP", SummonerRecommendations.compute(rows, Map.of(), Map.of(), Map.of()).get("mainRole"));
    }
    @Test
    @SuppressWarnings("unchecked")
    void ownMatchupsCountOnlyRiftGamesWithALaneOpponent() {
        List<JsonNode> matches = List.of(stored(91, 163, false, 420), stored(91, 163, false, 400), stored(7, 163, true, 420),
                stored(91, 0, true, 420), stored(91, 163, true, 450));
        Map<String, Object> result = OwnMatchups.of(matches);
        assertEquals(3, result.get("games"));
        Map<String, Object> vs = (Map<String, Object>) ((Map<String, Object>) result.get("vs")).get("163");
        assertEquals(3, vs.get("games"));
        assertEquals(1, vs.get("wins"));
        List<Map<String, Object>> champions = (List<Map<String, Object>>) vs.get("champions");
        assertEquals(91, champions.getFirst().get("key"), "Talon gegen Taliyah: 0:2");
        assertEquals(2, champions.getFirst().get("games"));
        assertEquals(0, champions.getFirst().get("wins"));
        Map<String, Object> own = (Map<String, Object>) ((Map<String, Object>) result.get("own")).get("91");
        assertEquals(3, own.get("games"), "eigene Spiele zählen auch ohne Lane-Gegner, ARAM nicht");
        assertFalse(((Map<String, Object>) result.get("vs")).containsKey("0"));
        assertTrue(((Map<String, Object>) result.get("own")).containsKey("7"));
    }
}
