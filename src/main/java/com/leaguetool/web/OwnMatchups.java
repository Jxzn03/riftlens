package com.leaguetool.web;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Eigene Matchup-Bilanz für den Draft: aus der gespeicherten Match-Historie des eigenen Kontos, ohne Riot-Abruf.
 * Gezählt werden Spiele auf Summoner's Rift mit festen Positionen (Normal, Ranked, Swiftplay, Clash) mit bekanntem
 * Lane-Gegner, ohne Remakes. Enthält nur Champions und Ergebnisse des eigenen Kontos, keine Daten anderer Spieler.
 */
final class OwnMatchups {

    /** Wie {@link SummonerRecommendations}: Queues mit festen Positionen. */
    static final Set<Integer> RIFT_QUEUES = Set.of(400, 420, 430, 440, 480, 490, 700, 710); // 710: 5v5 Teamranked (Summoner's Rift)

    private OwnMatchups() {
    }

    /**
     * @return {@code vs}: Gegner-Key → Spiele, Siege und je eigenem Champion {@code [Spiele, Siege, Kills, Tode, Assists]};
     * {@code own}: eigener Champion → {@code [Spiele, Siege]} (alle Positionen); {@code games}: gezählte Spiele
     */
    static Map<String, Object> of(List<JsonNode> matches) {
        Map<Integer, Group> vs = new TreeMap<>();
        Map<Integer, int[]> own = new TreeMap<>();
        int games = 0;
        long newest = 0;
        for (JsonNode m : matches) {
            JsonNode p = m.path("p");
            if (m.path("x").asInt() < SummonerHistory.SCHEMA || !RIFT_QUEUES.contains(m.path("queueId").asInt())
                    || SummonerLookup.isRemake(m, p)) {
                continue;
            }
            int champion = p.path("championId").asInt();
            boolean win = p.path("win").asBoolean();
            int[] ownStats = own.computeIfAbsent(champion, k -> new int[2]);
            ownStats[0]++;
            ownStats[1] += win ? 1 : 0;
            newest = Math.max(newest, m.path("gameCreation").asLong());
            int opponent = m.path("o").path("championId").asInt();
            if (opponent <= 0) {
                continue;
            }
            games++;
            Group group = vs.computeIfAbsent(opponent, k -> new Group());
            group.games++;
            group.wins += win ? 1 : 0;
            int[] line = group.champions.computeIfAbsent(champion, k -> new int[5]);
            line[0]++;
            line[1] += win ? 1 : 0;
            line[2] += p.path("kills").asInt();
            line[3] += p.path("deaths").asInt();
            line[4] += p.path("assists").asInt();
        }
        Map<String, Object> outVs = new TreeMap<>();
        vs.forEach((key, group) -> {
            List<Map<String, Object>> champions = new ArrayList<>();
            group.champions.forEach((champion, line) -> champions.add(Dto.obj("key", champion, "games", line[0],
                    "wins", line[1], "kills", line[2], "deaths", line[3], "assists", line[4])));
            champions.sort((a, b) -> Integer.compare((Integer) b.get("games"), (Integer) a.get("games")));
            outVs.put(String.valueOf(key), Dto.obj("games", group.games, "wins", group.wins, "champions", champions));
        });
        Map<String, Object> outOwn = new TreeMap<>();
        own.forEach((key, s) -> outOwn.put(String.valueOf(key), Dto.obj("games", s[0], "wins", s[1])));
        return Dto.obj("games", games, "newest", newest, "vs", outVs, "own", outOwn);
    }

    private static final class Group {
        int games;
        int wins;
        final Map<Integer, int[]> champions = new TreeMap<>();
    }
}
