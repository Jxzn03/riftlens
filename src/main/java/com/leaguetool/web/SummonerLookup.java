package com.leaguetool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.leaguetool.cache.FileCache;
import com.leaguetool.data.riot.LeagueEntry;
import com.leaguetool.data.riot.RiotAccount;
import com.leaguetool.data.riot.RiotApiClient;
import com.leaguetool.data.riot.RiotApiException;
import com.leaguetool.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fragt einen Summoner über die Riot-API ab: Profil, Ränge, Meisterschaft, die letzten Matches (bis
 * {@value #MAX_MATCHES}) und häufige Mitspieler.
 *
 * <p>Matches ändern sich nicht mehr und landen in der {@link SummonerHistory} des Spielers. Wiederholte Abfragen
 * kosten dadurch nur die fünf Profil-Aufrufe, je 100 Matches eine Seite Match-IDs und die neuen Spiele. Der erste
 * Abruf großer Mengen dauert wegen des Rate-Limits (Dev-Key: 100 Anfragen in 2 Minuten) mehrere Minuten.
 */
final class SummonerLookup {

    static final int MAX_MATCHES = 1000;
    /** Riots Obergrenze je Aufruf der Match-ID-Liste. */
    private static final int PAGE_SIZE = 100;
    /** Ab so vielen gemeinsamen Spielen gilt ein Teamkollege als häufiger Mitspieler (Duo/Premade). */
    static final int MIN_SHARED_GAMES = 3;
    private static final int MAX_MATES = 15;
    private static final int MAX_NAME_LOOKUPS = 10;
    /** Zwischendurch speichern, damit ein abgebrochener langer Abruf nicht von vorn beginnt. */
    private static final int SAVE_EVERY = 50;
    /** Matches unter dieser Dauer (Sekunden) gelten als Remake. */
    private static final int MIN_DURATION_SEC = 300;
    private static final int MASTERY_COUNT = 10;
    private static final int PARALLEL_DOWNLOADS = 4;
    private static final int PRACTICE_TOOL_QUEUE = 3140;
    /** Nach Riots offizieller Liste (static.developer.riotgames.com/docs/lol/queues.json). */
    private static final Map<Integer, String> QUEUES = Map.ofEntries(
            Map.entry(0, "Custom Games"), Map.entry(1710, "Arena"), Map.entry(400, "Normal Draft"), Map.entry(420, "Ranked Solo/Duo"), Map.entry(430, "Normal Blind"),
            Map.entry(440, "Ranked Flex"), Map.entry(450, "ARAM"), Map.entry(480, "Swiftplay"), Map.entry(490, "Quickplay"),
            Map.entry(700, "Clash"), Map.entry(710, "5v5 Teamranked"), Map.entry(720, "ARAM Clash"), Map.entry(900, "ARURF"), Map.entry(1020, "One for All"),
            Map.entry(1300, "Nexus Blitz"), Map.entry(1700, "Arena"), Map.entry(1900, "URF"), Map.entry(830, "Coop vs. KI"),
            Map.entry(840, "Coop vs. KI"), Map.entry(850, "Coop vs. KI"), Map.entry(870, "Coop vs. KI"),
            Map.entry(880, "Coop vs. KI"), Map.entry(890, "Coop vs. KI"), Map.entry(2300, "Brawl"),
            Map.entry(2400, "ARAM: Mayhem"), Map.entry(3100, "Custom Games"), Map.entry(3130, "Custom Games"));
    private static final Map<String, String> POSITIONS = Map.of("TOP", "Top", "JUNGLE", "Jungle", "MIDDLE", "Mid",
            "BOTTOM", "ADC", "UTILITY", "Support");
    private static final Map<String, String> RANKED_QUEUES = Map.of("RANKED_SOLO_5x5", "Solo/Duo", "RANKED_FLEX_SR", "Flex");

    private final RiotApiClient api;
    private final FileCache cache;
    private final String patch;
    private final Progress progress;

    /** Fortschritt beim Laden: {@code done} von {@code total} neuen Matches, {@code cached} kamen aus dem Cache. */
    @FunctionalInterface
    interface Progress {
        void report(int done, int total, int cached);
    }

    SummonerLookup(RiotApiClient api, FileCache cache, String patch, Progress progress) {
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
            Matches matches = matches(account.puuid(), matchCount);
            return Dto.obj(
                    "gameName", account.gameName(), "tagLine", account.tagLine(), "platform", api.platform().id(),
                    "level", summoner.path("summonerLevel").asInt(),
                    "icon", Dto.patchIcon(patch, "profileicon", summoner.path("profileIconId").asInt() + ".png"),
                    "ranked", ranked.stream().filter(e -> RANKED_QUEUES.containsKey(e.queueType())).map(SummonerLookup::ranked).toList(),
                    "mastery", mastery(masteries),
                    "matches", matches.rows(), "mates", matches.mates(), "minSharedGames", MIN_SHARED_GAMES);
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

    /** Matches der Abfrage und daraus die häufigen Mitspieler. */
    private record Matches(List<Map<String, Object>> rows, List<Map<String, Object>> mates) {
    }

    private Matches matches(String puuid, int count) throws IOException {
        List<String> ids = matchIds(puuid, count);
        SummonerHistory history = loadHistory(puuid);
        List<String> missing = new ArrayList<>();
        for (String id : ids) {
            if (!history.contains(id) && !adoptLegacy(history, id)) {
                missing.add(id);
            }
        }
        try {
            download(history, missing, ids.size() - missing.size());
        } catch (IOException | RuntimeException e) {
            // Bereits geladene Matches nicht verlieren, z. B. wenn der Key mitten im Abruf abläuft.
            try {
                save(history);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        List<JsonNode> stored = new ArrayList<>();
        for (String id : ids) {
            history.match(id).filter(m -> !isExcluded(m.path("queueId").asInt())).ifPresent(m -> {
                rows.add(matchRow(id, m, m.path("p")));
                stored.add(m);
            });
        }
        List<Map<String, Object>> mates = mates(history, rows, stored);
        save(history);
        return new Matches(rows, mates);
    }

    /** Übungstool (PRACTICETOOL, Queue 3140) zählt nicht zur Wertung und erscheint weder in Spielen noch bei Mitspielern. */
    static boolean isExcluded(int queueId) {
        return queueId == PRACTICE_TOOL_QUEUE;
    }

    /** Neueste Match-IDs, seitenweise zu je {@value #PAGE_SIZE} (Riots Obergrenze je Aufruf). */
    private List<String> matchIds(String puuid, int count) throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        for (int start = 0; start < count; start += PAGE_SIZE) {
            int size = Math.min(PAGE_SIZE, count - start);
            List<String> page = api.recentMatchIds(puuid, start, size);
            ids.addAll(page);
            if (page.size() < size) {
                break;
            }
        }
        return ids.stream().limit(count).toList();
    }

    private SummonerHistory loadHistory(String puuid) {
        Optional<Path> file = cache.file(FileCache.GLOBAL, SummonerHistory.cacheKey(puuid));
        if (file.isPresent()) {
            try {
                return SummonerHistory.read(puuid, Files.readAllBytes(file.get()));
            } catch (IOException e) {
                // Beschädigte Datei: neu aufbauen, die Matches werden erneut geladen.
            }
        }
        return new SummonerHistory(puuid);
    }

    private void save(SummonerHistory history) throws IOException {
        byte[] gzip = history.changedGzip();
        if (gzip == null) {
            return;
        }
        try {
            cache.writeBytes(FileCache.GLOBAL, SummonerHistory.cacheKey(history.puuid()), gzip);
        } catch (IOException e) {
            history.markDirty();
            throw e;
        }
    }

    /**
     * Übernimmt ein Match aus dem früheren Cache (eine Datei je Match, alle Teilnehmer ohne Riot-ID). Dort wird
     * nichts mehr geschrieben; die Dateien bleiben für die Historien anderer Spieler lesbar.
     */
    private boolean adoptLegacy(SummonerHistory history, String id) {
        try {
            Optional<String> legacy = cache.readText(FileCache.GLOBAL, SummonerHistory.LEGACY_PREFIX + id + ".json");
            return legacy.isPresent() && history.add(id, Json.MAPPER.readTree(legacy.get()));
        } catch (IOException e) {
            return false;
        }
    }

    /** Lädt die fehlenden Matches parallel; das Rate-Limit regelt der Client. */
    private void download(SummonerHistory history, List<String> missing, int cached) throws IOException {
        AtomicInteger done = new AtomicInteger();
        progress.report(0, missing.size(), cached);
        if (missing.isEmpty()) {
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL_DOWNLOADS, Thread.ofPlatform().daemon().name("lt-summoner").factory());
        try {
            List<Future<?>> downloads = new ArrayList<>();
            for (String id : missing) {
                downloads.add(pool.submit(() -> {
                    fetchMatch(history, id);
                    progress.report(done.incrementAndGet(), missing.size(), cached);
                    return null;
                }));
            }
            for (int i = 0; i < downloads.size(); i++) {
                await(downloads.get(i));
                if ((i + 1) % SAVE_EVERY == 0) {
                    save(history);
                }
            }
        } finally {
            // Bei einem Fehler nicht auf die restlichen (gedrosselten) Abrufe warten.
            pool.shutdownNow();
        }
    }

    private void fetchMatch(SummonerHistory history, String id) throws IOException {
        try {
            if (!history.add(id, api.match(id).path("info"))) {
                history.markGone(id);
            }
        } catch (RiotApiException e) {
            if (!e.isNotFound()) {
                throw e;
            }
            history.markGone(id);
        }
    }

    private static void await(Future<?> future) throws IOException {
        try {
            future.get();
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

    // ------------------------------------------------------------------ Mitspieler

    /**
     * Teamkollegen mit mindestens {@value #MIN_SHARED_GAMES} gemeinsamen Spielen (ohne Remakes), häufigste zuerst.
     * Jede Match-Zeile bekommt {@code mates} mit Verweisen in diese Liste; die Winrate je Duo rechnet die Anzeige,
     * damit der Modus-Filter mitwirkt.
     */
    private List<Map<String, Object>> mates(SummonerHistory history, List<Map<String, Object>> rows,
                                            List<JsonNode> stored) throws IOException {
        Map<Integer, Integer> games = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            if (!(Boolean) rows.get(i).get("remake")) {
                stored.get(i).path("mates").forEach(ref -> games.merge(ref.asInt(), 1, Integer::sum));
            }
        }
        List<Integer> selected = games.entrySet().stream()
                .filter(e -> e.getValue() >= MIN_SHARED_GAMES)
                .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_MATES).map(Map.Entry::getKey).toList();
        resolveNames(history, selected);
        Map<Integer, Integer> position = new HashMap<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (int ref : selected) {
            SummonerHistory.Mate mate = history.mate(ref);
            position.put(ref, result.size());
            result.add(Dto.obj("name", mate.name(), "tag", mate.tag()));
        }
        for (int i = 0; i < rows.size(); i++) {
            List<Integer> refs = new ArrayList<>();
            stored.get(i).path("mates").forEach(ref -> {
                Integer pos = position.get(ref.asInt());
                if (pos != null) {
                    refs.add(pos);
                }
            });
            rows.get(i).put("mates", refs);
        }
        return result;
    }

    /** Fehlende Riot-IDs (Mitspieler aus dem alten Cache) über account-v1 nachtragen, höchstens wenige Aufrufe. */
    private void resolveNames(SummonerHistory history, List<Integer> refs) throws IOException {
        int lookups = 0;
        for (int ref : refs) {
            SummonerHistory.Mate mate = history.mate(ref);
            if (!mate.name().isBlank() || lookups >= MAX_NAME_LOOKUPS) {
                continue;
            }
            lookups++;
            try {
                RiotAccount account = api.accountByPuuid(mate.puuid());
                if (!account.gameName().isBlank()) {
                    history.rename(ref, account.gameName(), account.tagLine());
                }
            } catch (RiotApiException e) {
                if (e.isAuthProblem()) {
                    throw e;
                }
                // Konto unbekannt oder Fehler: ohne Namen anzeigen.
            }
        }
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
