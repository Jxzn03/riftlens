package com.leaguetool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leaguetool.cache.FileCache;
import com.leaguetool.data.riot.LeagueEntry;
import com.leaguetool.data.riot.RiotAccount;
import com.leaguetool.data.riot.RiotApiClient;
import com.leaguetool.data.riot.RiotApiException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Fragt einen Summoner über die Riot-API ab: Profil, Ränge, Meisterschaft und die letzten Matches.
 *
 * <p>Matches ändern sich nicht mehr und werden gekürzt im Datei-Cache abgelegt. Wiederholte Abfragen kosten dadurch
 * nur die fünf Profil-Aufrufe und schonen das Rate-Limit (Dev-Key: 100 Anfragen in 2 Minuten).
 */
final class SummonerLookup {

    static final int MAX_MATCHES = 100;
    /** Matches unter dieser Dauer (Sekunden) gelten als Remake. */
    private static final int MIN_DURATION_SEC = 300;
    private static final int MASTERY_COUNT = 10;
    private static final int PARALLEL_DOWNLOADS = 4;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> INFO_FIELDS = List.of("queueId", "gameMode", "gameDuration", "gameEndTimestamp",
            "gameCreation");
    private static final List<String> PARTICIPANT_FIELDS = List.of("puuid", "championId", "championName", "teamPosition",
            "win", "kills", "deaths", "assists", "totalMinionsKilled", "neutralMinionsKilled", "gameEndedInEarlySurrender",
            "summoner1Id", "summoner2Id", "item0", "item1", "item2", "item3", "item4", "item5", "item6");
    /** Nach Riots offizieller Liste (static.developer.riotgames.com/docs/lol/queues.json). */
    private static final Map<Integer, String> QUEUES = Map.ofEntries(
            Map.entry(0, "Benutzerdefiniert"), Map.entry(1710, "Arena"), Map.entry(400, "Normal Draft"), Map.entry(420, "Ranked Solo/Duo"), Map.entry(430, "Normal Blind"),
            Map.entry(440, "Ranked Flex"), Map.entry(450, "ARAM"), Map.entry(480, "Swiftplay"), Map.entry(490, "Quickplay"),
            Map.entry(700, "Clash"), Map.entry(710, "5 vs 5 Teamranked"), Map.entry(720, "ARAM Clash"), Map.entry(900, "ARURF"), Map.entry(1020, "One for All"),
            Map.entry(1300, "Nexus Blitz"), Map.entry(1700, "Arena"), Map.entry(1900, "URF"), Map.entry(830, "Coop vs. KI"),
            Map.entry(840, "Coop vs. KI"), Map.entry(850, "Coop vs. KI"), Map.entry(870, "Coop vs. KI"),
            Map.entry(880, "Coop vs. KI"), Map.entry(890, "Coop vs. KI"), Map.entry(2300, "Brawl"),
            Map.entry(2400, "ARAM: Mayhem"), Map.entry(3100, "Tournament Code (Custom Game)"));
    private static final Map<String, String> POSITIONS = Map.of("TOP", "Top", "JUNGLE", "Jungle", "MIDDLE", "Mid",
            "BOTTOM", "Bot", "UTILITY", "Support");
    private static final Map<String, String> RANKED_QUEUES = Map.of("RANKED_SOLO_5x5", "Solo/Duo", "RANKED_FLEX_SR", "Flex");

    private final RiotApiClient api;
    private final FileCache cache;
    private final String patch;
    private final BiConsumer<Integer, Integer> progress;

    /** @param progress erhält (fertig, gesamt) während die Matches geladen werden */
    SummonerLookup(RiotApiClient api, FileCache cache, String patch, BiConsumer<Integer, Integer> progress) {
        this.api = api;
        this.cache = cache;
        this.patch = patch;
        this.progress = progress;
    }

    Map<String, Object> lookup(String riotId, int matchCount) throws IOException {
        String[] id = parseRiotId(riotId);
        try {
            RiotAccount account = account(id[0], id[1]);
            JsonNode summoner = summoner(account);
            List<LeagueEntry> ranked = api.leagueEntriesByPuuid(account.puuid());
            JsonNode masteries = api.topChampionMasteries(account.puuid(), MASTERY_COUNT);
            List<Map<String, Object>> matches = matches(account.puuid(), matchCount);
            return Dto.obj(
                    "gameName", account.gameName(), "tagLine", account.tagLine(), "platform", api.platform().id(),
                    "level", summoner.path("summonerLevel").asInt(),
                    "icon", "/img/" + patch + "/profileicon/" + summoner.path("profileIconId").asInt() + ".png",
                    "ranked", ranked.stream().filter(e -> RANKED_QUEUES.containsKey(e.queueType())).map(SummonerLookup::ranked).toList(),
                    "mastery", mastery(masteries),
                    "matches", matches);
        } catch (RiotApiException e) {
            throw new ApiException(e.isAuthProblem() ? 403 : 502, e.getMessage());
        }
    }

    /** {@code Name#TAG} in Name und Tag; getrennt wird am letzten {@code #}. */
    static String[] parseRiotId(String raw) {
        String value = raw == null ? "" : raw.trim();
        int hash = value.lastIndexOf('#');
        if (hash <= 0 || hash == value.length() - 1) {
            throw new ApiException(400, "Bitte die Riot-ID im Format Name#TAG eingeben.");
        }
        return new String[] {value.substring(0, hash).trim(), value.substring(hash + 1).trim()};
    }

    private RiotAccount account(String name, String tag) throws IOException {
        try {
            return api.accountByRiotId(name, tag);
        } catch (RiotApiException e) {
            if (e.isNotFound()) {
                throw new ApiException(404, "Riot-ID „" + name + "#" + tag + "“ wurde nicht gefunden.");
            }
            throw e;
        }
    }

    private JsonNode summoner(RiotAccount account) throws IOException {
        try {
            return api.summonerByPuuid(account.puuid());
        } catch (RiotApiException e) {
            if (e.isNotFound()) {
                throw new ApiException(404, account.gameName() + "#" + account.tagLine()
                        + " hat auf " + api.platform().name() + " kein LoL-Konto. Bitte die Region prüfen.");
            }
            throw e;
        }
    }

    private static Map<String, Object> ranked(LeagueEntry e) {
        int games = e.wins() + e.losses();
        return Dto.obj("queue", RANKED_QUEUES.get(e.queueType()), "tier", e.tier() == null ? "" : e.tier().label(),
                "division", e.tier() != null && e.tier().isApex() ? "" : e.rank(), "lp", e.leaguePoints(),
                "wins", e.wins(), "losses", e.losses(), "winRate", games == 0 ? 0 : (double) e.wins() / games);
    }

    private static List<Map<String, Object>> mastery(JsonNode masteries) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode m : masteries) {
            rows.add(Dto.obj("championKey", m.path("championId").asInt(), "level", m.path("championLevel").asInt(),
                    "points", m.path("championPoints").asLong(), "lastPlayed", m.path("lastPlayTime").asLong()));
        }
        return rows;
    }

    // ------------------------------------------------------------------ Matches

    private List<Map<String, Object>> matches(String puuid, int count) throws IOException {
        List<String> ids = api.recentMatchIds(puuid, count);
        AtomicInteger done = new AtomicInteger();
        progress.accept(0, ids.size());
        List<Future<Optional<JsonNode>>> downloads = new ArrayList<>();
        List<Map<String, Object>> result = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(PARALLEL_DOWNLOADS, r -> {
            Thread t = new Thread(r, "lt-summoner");
            t.setDaemon(true);
            return t;
        })) {
            for (String id : ids) {
                downloads.add(pool.submit(() -> {
                    Optional<JsonNode> match = compactMatch(id);
                    progress.accept(done.incrementAndGet(), ids.size());
                    return match;
                }));
            }
            for (int i = 0; i < ids.size(); i++) {
                Optional<JsonNode> match = await(downloads.get(i));
                if (match.isPresent()) {
                    match(ids.get(i), match.get(), puuid).ifPresent(result::add);
                }
            }
        }
        return result;
    }

    private static Optional<JsonNode> await(Future<Optional<JsonNode>> future) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Abfrage unterbrochen", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException(WebContext.message(e), e);
        }
    }

    /** Match aus dem Cache oder von der API; leer, wenn Riot es nicht (mehr) kennt. */
    private Optional<JsonNode> compactMatch(String id) throws IOException {
        String key = "riot/match/" + id + ".json";
        Optional<String> cached = cache.readText(FileCache.GLOBAL, key);
        if (cached.isPresent()) {
            return Optional.of(JSON.readTree(cached.get()));
        }
        JsonNode full;
        try {
            full = api.match(id);
        } catch (RiotApiException e) {
            if (e.isNotFound()) {
                return Optional.empty();
            }
            throw e;
        }
        JsonNode compact = compact(full.path("info"));
        cache.writeText(FileCache.GLOBAL, key, JSON.writeValueAsString(compact));
        return Optional.of(compact);
    }

    private static JsonNode compact(JsonNode info) {
        ObjectNode out = JSON.createObjectNode();
        for (String field : INFO_FIELDS) {
            if (info.has(field)) {
                out.set(field, info.get(field));
            }
        }
        var participants = out.putArray("participants");
        for (JsonNode p : info.path("participants")) {
            ObjectNode compact = participants.addObject();
            for (String field : PARTICIPANT_FIELDS) {
                if (p.has(field)) {
                    compact.set(field, p.get(field));
                }
            }
        }
        return out;
    }

    private static Optional<Map<String, Object>> match(String id, JsonNode info, String puuid) {
        for (JsonNode p : info.path("participants")) {
            if (puuid.equals(p.path("puuid").asText())) {
                return Optional.of(matchRow(id, info, p));
            }
        }
        return Optional.empty();
    }

    /**
     * Spieldauer in Sekunden oder -1, wenn {@code gameDuration} fehlt. Ohne {@code gameEndTimestamp} (alte Matches)
     * liefert Riot Millisekunden.
     */
    static long durationSeconds(JsonNode info) {
        JsonNode duration = info.path("gameDuration");
        if (!duration.isNumber()) {
            return -1;
        }
        long value = duration.asLong();
        JsonNode end = info.path("gameEndTimestamp");
        boolean seconds = !end.isMissingNode() && !end.isNull();
        return seconds ? value : value / 1000;
    }

    private static Map<String, Object> matchRow(String id, JsonNode info, JsonNode p) {
        long duration = Math.max(0, durationSeconds(info));
        boolean remake = duration < MIN_DURATION_SEC || p.path("gameEndedInEarlySurrender").asBoolean(false);
        int queue = info.path("queueId").asInt();
        long ended = info.path("gameEndTimestamp").asLong(info.path("gameCreation").asLong() + duration * 1000);
        List<Integer> items = new ArrayList<>();
        for (int slot = 0; slot <= 6; slot++) {
            int item = p.path("item" + slot).asInt();
            if (item > 0) {
                items.add(item);
            }
        }
        List<Integer> spells = new ArrayList<>();
        for (String field : List.of("summoner1Id", "summoner2Id")) {
            if (p.path(field).asInt() > 0) {
                spells.add(p.path(field).asInt());
            }
        }
        return Dto.obj("id", id, "queueId", queue, "mode", modeLabel(queue, info.path("gameMode").asText("")),
                "remake", remake, "championKey", p.path("championId").asInt(), "championName", p.path("championName").asText(""),
                "win", p.path("win").asBoolean(), "kills", p.path("kills").asInt(), "deaths", p.path("deaths").asInt(),
                "assists", p.path("assists").asInt(),
                "cs", p.path("totalMinionsKilled").asInt() + p.path("neutralMinionsKilled").asInt(),
                "durationSec", duration, "endedAt", ended,
                "position", POSITIONS.getOrDefault(p.path("teamPosition").asText(""), ""),
                "items", items, "spells", spells);
    }

    /** Queue als Klartext; nicht dokumentierte Queues zeigen Spielmodus und Nummer statt einer geratenen Bezeichnung. */
    static String modeLabel(int queueId, String gameMode) {
        String label = QUEUES.get(queueId);
        if (label != null) {
            return label;
        }
        // Riot vergibt für Arena immer wieder neue Queue-Nummern (1700, 1710, 1750); der Spielmodus bleibt CHERRY.
        if ("CHERRY".equals(gameMode)) {
            return "Arena";
        }
        if (gameMode == null || gameMode.isBlank()) {
            return "Queue " + queueId;
        }
        return ("CLASSIC".equals(gameMode) ? "Summoner's Rift" : gameMode) + " (Queue " + queueId + ")";
    }
}
