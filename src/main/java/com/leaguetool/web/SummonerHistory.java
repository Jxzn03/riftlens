package com.leaguetool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.leaguetool.util.Json;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Gespeicherte Match-Historie eines Spielers, gzip-komprimiert unter {@code riot/history/<puuid>.json.gz}.
 *
 * <p>Je Match bleibt nur die eigene Zeile und eine Liste von Verweisen auf die Teamkollegen. Jeder Mitspieler steht
 * einmal in der Mitspieler-Tabelle (PUUID und zuletzt gesehene Riot-ID). Daraus lassen sich häufige Mitspieler
 * und die Duo-Winrate ohne neuen Abruf auswerten. Gehalten werden die {@link #MAX_STORED} neuesten Matches.
 * Threadsicher, weil Matches parallel geladen werden.
 */
final class SummonerHistory {

    static final int VERSION = 1;
    /** Etwas mehr als {@link SummonerLookup#MAX_MATCHES}, damit neue Spiele die größte Abfrage nicht neu laden lassen. */
    static final int MAX_STORED = 1200;
    static final String LEGACY_PREFIX = "riot/match/";

    private static final List<String> INFO_FIELDS = List.of("queueId", "gameMode", "gameDuration", "gameEndTimestamp",
            "gameCreation");
    private static final List<String> OWN_FIELDS = List.of("championId", "championName", "teamPosition",
            "win", "kills", "deaths", "assists", "totalMinionsKilled", "neutralMinionsKilled", "gameEndedInEarlySurrender",
            "summoner1Id", "summoner2Id", "item0", "item1", "item2", "item3", "item4", "item5", "item6");
    /** Riot meldet KI-Gegner und -Mitspieler mit dieser PUUID. */
    private static final String BOT_PUUID = "BOT";

    /** Mitspieler; {@code name} ist leer, wenn die Riot-ID unbekannt ist (Matches aus dem alten Cache). */
    record Mate(String puuid, String name, String tag, long seen) {
    }

    private final String puuid;
    private final List<Mate> mates = new ArrayList<>();
    private final Map<String, Integer> mateIndex = new HashMap<>();
    private final Map<String, ObjectNode> matches = new HashMap<>();
    private final Set<String> gone = new LinkedHashSet<>();
    private boolean dirty;

    SummonerHistory(String puuid) {
        this.puuid = puuid;
    }

    String puuid() {
        return puuid;
    }

    static String cacheKey(String puuid) {
        return "riot/history/" + puuid + ".json.gz";
    }

    // ------------------------------------------------------------------ Lesen / Schreiben

    /** Liest eine gespeicherte Historie; anderes Format oder fremde PUUID ergibt eine leere. */
    static SummonerHistory read(String puuid, byte[] gzip) throws IOException {
        JsonNode root;
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
            root = Json.MAPPER.readTree(in);
        }
        SummonerHistory history = new SummonerHistory(puuid);
        if (root == null || root.path("v").asInt() != VERSION || !puuid.equals(root.path("puuid").asText())) {
            return history;
        }
        for (JsonNode m : root.path("mates")) {
            history.mateIndex.put(m.path("puuid").asText(), history.mates.size());
            history.mates.add(new Mate(m.path("puuid").asText(), m.path("name").asText(""), m.path("tag").asText(""),
                    m.path("seen").asLong()));
        }
        for (JsonNode m : root.path("matches")) {
            if (m instanceof ObjectNode match && match.hasNonNull("id")) {
                history.matches.put(match.get("id").asText(), match);
            }
        }
        root.path("gone").forEach(id -> history.gone.add(id.asText()));
        return history;
    }

    /**
     * Komprimierte Form zum Speichern oder {@code null}, wenn sich seit dem letzten Aufruf nichts geändert hat.
     * Behält die neuesten {@link #MAX_STORED} Matches und nur Mitspieler, auf die noch verwiesen wird.
     */
    synchronized byte[] changedGzip() throws IOException {
        if (!dirty) {
            return null;
        }
        List<ObjectNode> kept = matches.values().stream()
                .sorted(Comparator.comparingLong((ObjectNode m) -> m.path("gameCreation").asLong()).reversed())
                .limit(MAX_STORED).toList();
        Map<Integer, Integer> remap = new HashMap<>();
        ArrayNode outMates = Json.MAPPER.createArrayNode();
        ArrayNode outMatches = Json.MAPPER.createArrayNode();
        for (ObjectNode match : kept) {
            ObjectNode copy = match.deepCopy();
            ArrayNode refs = copy.putArray("mates");
            for (JsonNode ref : match.path("mates")) {
                refs.add(remap.computeIfAbsent(ref.asInt(), old -> {
                    Mate mate = mates.get(old);
                    outMates.addObject().put("puuid", mate.puuid()).put("name", mate.name()).put("tag", mate.tag())
                            .put("seen", mate.seen());
                    return outMates.size() - 1;
                }));
            }
            outMatches.add(copy);
        }
        ObjectNode root = Json.MAPPER.createObjectNode().put("v", VERSION).put("puuid", puuid);
        root.set("mates", outMates);
        root.set("matches", outMatches);
        ArrayNode outGone = root.putArray("gone");
        gone.forEach(outGone::add);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStream out = new GZIPOutputStream(bytes)) {
            Json.MAPPER.writeValue(out, root);
        }
        dirty = false;
        return bytes.toByteArray();
    }

    /** Nach einem fehlgeschlagenen Speichern: beim nächsten Mal erneut schreiben. */
    synchronized void markDirty() {
        dirty = true;
    }

    // ------------------------------------------------------------------ Matches

    /** {@code true}, wenn das Match gespeichert ist oder Riot es nicht (mehr) kennt. */
    synchronized boolean contains(String id) {
        return matches.containsKey(id) || gone.contains(id);
    }

    synchronized Optional<JsonNode> match(String id) {
        return Optional.ofNullable(matches.get(id));
    }

    synchronized int size() {
        return matches.size();
    }

    /** Merkt sich ein Match, das Riot nicht (mehr) liefert, damit es nicht erneut abgefragt wird. */
    synchronized void markGone(String id) {
        dirty |= gone.add(id);
    }

    /**
     * Übernimmt ein Match aus dem {@code info}-Teil eines MatchDto (oder dem alten Cache mit gekürzten Teilnehmern).
     *
     * @return {@code false}, wenn der Spieler darin nicht vorkommt
     */
    synchronized boolean add(String id, JsonNode info) {
        JsonNode participants = info.path("participants");
        int ownIndex = -1;
        for (int i = 0; i < participants.size(); i++) {
            if (puuid.equals(participants.get(i).path("puuid").asText())) {
                ownIndex = i;
                break;
            }
        }
        if (ownIndex < 0) {
            return false;
        }
        JsonNode own = participants.get(ownIndex);
        ObjectNode out = Json.MAPPER.createObjectNode().put("id", id);
        copy(info, out, INFO_FIELDS);
        copy(own, out.putObject("p"), OWN_FIELDS);
        ArrayNode refs = out.putArray("mates");
        long seen = info.path("gameCreation").asLong();
        for (int i = 0; i < participants.size(); i++) {
            JsonNode other = participants.get(i);
            if (i != ownIndex && sameTeam(own, ownIndex, other, i, participants.size())) {
                int ref = mateRef(other, seen);
                if (ref >= 0) {
                    refs.add(ref);
                }
            }
        }
        matches.put(id, out);
        gone.remove(id);
        dirty = true;
        return true;
    }

    /**
     * Teamkollege? Arena paart über {@code playerSubteamId}, sonst gilt {@code teamId}. Der alte Cache kennt keine
     * {@code teamId}; dort listet Riot Team 100 zuerst und dann Team 200 mit je fünf Spielern.
     */
    static boolean sameTeam(JsonNode own, int ownIndex, JsonNode other, int otherIndex, int players) {
        int ownSubteam = own.path("playerSubteamId").asInt(0);
        if (ownSubteam > 0) {
            return other.path("playerSubteamId").asInt(0) == ownSubteam;
        }
        if (own.has("teamId") && other.has("teamId")) {
            return own.path("teamId").asInt() == other.path("teamId").asInt();
        }
        return players == 10 && ownIndex / 5 == otherIndex / 5;
    }

    private int mateRef(JsonNode participant, long seen) {
        String matePuuid = participant.path("puuid").asText("");
        if (matePuuid.isBlank() || BOT_PUUID.equals(matePuuid)) {
            return -1;
        }
        String name = participant.path("riotIdGameName").asText("");
        String tag = participant.path("riotIdTagline").asText("");
        Integer index = mateIndex.get(matePuuid);
        if (index == null) {
            mateIndex.put(matePuuid, mates.size());
            mates.add(new Mate(matePuuid, name, tag, name.isBlank() ? 0 : seen));
            return mates.size() - 1;
        }
        Mate known = mates.get(index);
        if (!name.isBlank() && seen >= known.seen()) {
            mates.set(index, new Mate(matePuuid, name, tag, seen));
        }
        return index;
    }

    private static void copy(JsonNode from, ObjectNode to, List<String> fields) {
        for (String field : fields) {
            if (from.has(field)) {
                to.set(field, from.get(field));
            }
        }
    }

    // ------------------------------------------------------------------ Mitspieler

    synchronized Mate mate(int index) {
        return mates.get(index);
    }

    /** Riot-ID nachgetragen (über account-v1), für Mitspieler aus dem alten Cache. */
    synchronized void rename(int index, String name, String tag) {
        Mate mate = mates.get(index);
        mates.set(index, new Mate(mate.puuid(), name, tag, mate.seen()));
        dirty = true;
    }
}
