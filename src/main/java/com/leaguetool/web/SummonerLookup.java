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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
            JsonNode masteries = api.championMasteries(account.puuid());
            Matches matches = matches(account.puuid(), matchCount);
            return Dto.obj(
                    "gameName", account.gameName(), "tagLine", account.tagLine(), "platform", api.platform().id(),
                    "level", summoner.path("summonerLevel").asInt(),
                    "icon", Dto.patchIcon(patch, "profileicon", summoner.path("profileIconId").asInt() + ".png"),
                    "ranked", ranked.stream().filter(e -> e.queueType() != null && e.queueType().startsWith("RANKED_")).map(SummonerLookup::ranked).toList(),
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

    /**
     * Anzeigename einer Ranked-Warteschlange: Solo/Duo und Flex fest, Team-Warteschlangen (z. B. 5v5 Teamranked) als
     * „Teamranked 5v5“, andere lesbar aus dem Riot-Namen. Liefert league-v4 sie mit, erscheinen sie ohne weiteren Aufruf.
     */
    static String queueLabel(String queueType) {
        String known = RANKED_QUEUES.get(queueType);
        if (known != null) {
            return known;
        }
        if (queueType.contains("TEAM")) {
            return "Teamranked 5v5";
        }
        String name = queueType.replaceFirst("^RANKED_", "").replace('_', ' ').toLowerCase(Locale.ROOT);
        return name.isEmpty() ? queueType : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    static Map<String, Object> ranked(LeagueEntry e) {
        int games = e.wins() + e.losses();
        return Dto.obj("queue", queueLabel(e.queueType()), "tier", e.tier() == null ? "" : e.tier().label(),
                "division", e.tier() != null && e.tier().isApex() ? "" : e.rank(), "lp", e.leaguePoints(),
                "wins", e.wins(), "losses", e.losses(), "winRate", games == 0 ? 0 : (double) e.wins() / games);
    }

    /**
     * Alle Meisterschaften des Kontos, wie Riot sie liefert (Punkte absteigend, wie bei op.gg). {@code grades} sind
     * die Noten der laufenden Saison-Meilensteine, {@code untilNext} wird nach Stufe 10 negativ (Punkte über der Stufe).
     */
    static List<Map<String, Object>> mastery(JsonNode masteries) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode m : masteries) {
            List<String> grades = new ArrayList<>();
            m.path("milestoneGrades").forEach(g -> grades.add(g.asText()));
            JsonNode next = m.path("nextSeasonMilestone");
            Map<String, Integer> required = new java.util.TreeMap<>();
            next.path("requireGradeCounts").fields().forEachRemaining(e -> required.put(e.getKey(), e.getValue().asInt()));
            rows.add(Dto.obj("championKey", m.path("championId").asInt(), "level", m.path("championLevel").asInt(),
                    "points", m.path("championPoints").asLong(), "lastPlayed", m.path("lastPlayTime").asLong(),
                    "sinceLevel", m.path("championPointsSinceLastLevel").asLong(),
                    "untilNext", m.path("championPointsUntilNextLevel").asLong(),
                    "tokens", m.path("tokensEarned").asInt(), "seasonMilestone", m.path("championSeasonMilestone").asInt(),
                    "grades", grades, "required", required, "rewardMarks", next.path("rewardMarks").asInt(0)));
        }
        return rows;
    }

    // ------------------------------------------------------------------ Alle eigenen Konten

    /**
     * Match-Zeilen aller eigenen Konten nur aus dem Cache, neueste zuerst. Jede Zeile trägt die Kontonummer
     * ({@code account}) und eine eindeutige {@code id} (ein Spiel zweier eigener Konten zusammen zählt für beide).
     * Mitspieler werden über alle Konten nach PUUID zusammengezählt; eigene Konten zählen nicht als Mitspieler.
     */
    static Map<String, Object> combinedMatches(FileCache cache, List<String> puuids) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<List<String>> rowMates = new ArrayList<>();
        Map<String, Integer> games = new HashMap<>();
        Map<String, SummonerHistory.Mate> known = new HashMap<>();
        for (int a = 0; a < puuids.size(); a++) {
            SummonerHistory history = loadHistory(cache, puuids.get(a));
            for (JsonNode m : history.matches()) {
                if (isExcluded(m.path("queueId").asInt())) {
                    continue;
                }
                String id = m.path("id").asText("");
                Map<String, Object> row = matchRow(id + "-" + a, m, m.path("p"));
                row.put("account", a);
                List<String> mates = new ArrayList<>();
                for (JsonNode ref : m.path("mates")) {
                    SummonerHistory.Mate mate = history.mate(ref.asInt());
                    if (mate == null || mate.puuid().isBlank() || puuids.contains(mate.puuid())) {
                        continue;
                    }
                    mates.add(mate.puuid());
                    known.merge(mate.puuid(), mate, (old, now) -> old.name().isBlank() ? now : old);
                    if (!(Boolean) row.get("remake")) {
                        games.merge(mate.puuid(), 1, Integer::sum);
                    }
                }
                rows.add(row);
                rowMates.add(mates);
            }
        }
        List<String> selected = games.entrySet().stream()
                .filter(e -> e.getValue() >= MIN_SHARED_GAMES)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_MATES).map(Map.Entry::getKey).toList();
        Map<String, Integer> position = new HashMap<>();
        List<Map<String, Object>> mates = new ArrayList<>();
        for (String puuid : selected) {
            position.put(puuid, mates.size());
            SummonerHistory.Mate mate = known.get(puuid);
            mates.add(Dto.obj("name", mate.name(), "tag", mate.tag()));
        }
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).put("mates", rowMates.get(i).stream().map(position::get).filter(Objects::nonNull).toList());
        }
        rows.sort(Comparator.comparingLong((Map<String, Object> r) -> ((Number) r.get("startedAt")).longValue()).reversed());
        return Dto.obj("matches", rows, "mates", mates, "minSharedGames", MIN_SHARED_GAMES);
    }

    /**
     * Meisterschaft mehrerer Konten je Champion: Punkte zusammengezählt, Stufe, Fortschritt und Noten vom Konto mit der
     * höchsten Stufe (bei Gleichstand mehr Punkte), zuletzt gespielt das jüngste Datum. Sortiert nach Punkten.
     */
    static List<Map<String, Object>> combinedMastery(List<List<Map<String, Object>>> perAccount) {
        Map<Integer, Map<String, Object>> best = new HashMap<>();
        Map<Integer, Long> points = new HashMap<>();
        Map<Integer, Long> lastPlayed = new HashMap<>();
        Map<Integer, Integer> accounts = new HashMap<>();
        Comparator<Map<String, Object>> rank = Comparator.<Map<String, Object>>comparingInt(m -> (Integer) m.get("level"))
                .thenComparingLong(m -> ((Number) m.get("points")).longValue());
        for (List<Map<String, Object>> list : perAccount) {
            for (Map<String, Object> m : list) {
                int key = (Integer) m.get("championKey");
                best.merge(key, m, (old, now) -> rank.compare(now, old) > 0 ? now : old);
                points.merge(key, ((Number) m.get("points")).longValue(), Long::sum);
                lastPlayed.merge(key, ((Number) m.get("lastPlayed")).longValue(), Math::max);
                accounts.merge(key, 1, Integer::sum);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        best.forEach((key, m) -> {
            Map<String, Object> row = new LinkedHashMap<>(m);
            row.put("points", points.get(key));
            row.put("lastPlayed", lastPlayed.get(key));
            row.put("accounts", accounts.get(key));
            out.add(row);
        });
        out.sort(Comparator.comparingLong((Map<String, Object> m) -> ((Number) m.get("points")).longValue()).reversed()
                .thenComparingInt(m -> (Integer) m.get("championKey")));
        return out;
    }

    // ------------------------------------------------------------------ Matches

    /** Matches der Abfrage und daraus die häufigen Mitspieler. */
    private record Matches(List<Map<String, Object>> rows, List<Map<String, Object>> mates) {
    }

    /**
     * Remakes und Übungstool zählen nicht zur Wertung: Für jedes solche Spiel wird ein älteres nachgeladen, damit die
     * angefragte Anzahl gewerteter Spiele zusammenkommt (höchstens {@value #MAX_TOP_UP} zusätzliche Matches). Von Riot
     * nicht mehr gelieferte Matches (404) werden nicht ersetzt.
     */
    static final int MAX_TOP_UP = 30;

    private Matches matches(String puuid, int count) throws IOException {
        SummonerHistory history = loadHistory(cache, puuid);
        List<String> ids = new ArrayList<>();
        try {
            boolean more = fetch(history, puuid, ids, count);
            int extra = 0;
            int need = count - ids.size() + skipped(history, ids);
            while (more && need > 0 && extra < MAX_TOP_UP) {
                int size = Math.min(need, MAX_TOP_UP - extra);
                int before = ids.size();
                try {
                    more = fetch(history, puuid, ids, size);
                } catch (IOException e) {
                    // Nachladen ist nur eine Ergänzung: die bisherigen Matches reichen für die Antwort.
                    break;
                }
                extra += ids.size() - before;
                need = count - ids.size() + skipped(history, ids);
            }
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

    /**
     * Die nächsten {@code size} Match-IDs ab {@code ids.size()} anhängen und fehlende Matches laden.
     * @return ob Riot volle Seiten geliefert hat, es also ältere Matches geben kann
     */
    private boolean fetch(SummonerHistory history, String puuid, List<String> ids, int size) throws IOException {
        List<String> page = matchIds(puuid, ids.size(), size);
        List<String> fresh = page.stream().filter(id -> !ids.contains(id)).toList();
        ids.addAll(fresh);
        List<String> missing = fresh.stream().filter(id -> !history.contains(id)).toList();
        download(history, new ArrayList<>(missing), ids.size() - missing.size());
        return page.size() == size;
    }

    /** Geladene Matches ohne Wertung (Remake oder Übungstool). */
    private static int skipped(SummonerHistory history, List<String> ids) {
        int skipped = 0;
        for (String id : ids) {
            JsonNode m = history.match(id).orElse(null);
            if (m != null && (isExcluded(m.path("queueId").asInt()) || isRemake(m, m.path("p")))) {
                skipped++;
            }
        }
        return skipped;
    }

    /** Übungstool (PRACTICETOOL, Queue 3140) zählt nicht zur Wertung und erscheint weder in Spielen noch bei Mitspielern. */
    static boolean isExcluded(int queueId) {
        return queueId == PRACTICE_TOOL_QUEUE;
    }

    /** Match-IDs ab {@code start}, seitenweise zu je {@value #PAGE_SIZE} (Riots Obergrenze je Aufruf). */
    private List<String> matchIds(String puuid, int start, int count) throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        for (int offset = 0; offset < count; offset += PAGE_SIZE) {
            int size = Math.min(PAGE_SIZE, count - offset);
            List<String> page = api.recentMatchIds(puuid, start + offset, size);
            ids.addAll(page);
            if (page.size() < size) {
                break;
            }
        }
        return ids.stream().limit(count).toList();
    }

    /** Match-Zeilen eines Kontos nur aus dem Cache (ohne Übungstool), z. B. weitere eigene Konten für Empfehlungen. */
    static List<Map<String, Object>> cachedRows(FileCache cache, String puuid) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode m : loadHistory(cache, puuid).matches()) {
            if (!isExcluded(m.path("queueId").asInt())) {
                rows.add(matchRow("", m, m.path("p")));
            }
        }
        return rows;
    }

    /** Gespeicherte Historie des Spielers (ohne Abruf) oder eine leere. */
    static SummonerHistory loadHistory(FileCache cache, String puuid) {
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
     * nichts mehr geschrieben; die Dateien bleiben für die Historien anderer Spieler lesbar. Nur noch Ersatz, wenn Riot
     * das Match nicht mehr liefert: Der alte Cache kennt weder Positionen noch Teams (Schema 1, ohne Lane-Gegner).
     */
    private boolean adoptLegacy(SummonerHistory history, String id) {
        try {
            Optional<String> legacy = cache.readText(FileCache.GLOBAL, SummonerHistory.LEGACY_PREFIX + id + ".json");
            return legacy.isPresent() && history.add(id, Json.MAPPER.readTree(legacy.get()), 1);
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
            // Riot kennt das Match nicht mehr: ein vorhandener (veralteter) Eintrag oder der alte Cache bleibt lesbar.
            if (history.match(id).isEmpty()) {
                adoptLegacy(history, id);
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

    /** Remake: unter 5 Minuten oder vorzeitig aufgegeben; zählt in keiner Wertung. */
    static boolean isRemake(JsonNode info, JsonNode p) {
        return Math.max(0, durationSeconds(info)) < MIN_DURATION_SEC || p.path("gameEndedInEarlySurrender").asBoolean(false);
    }

    private static Map<String, Object> matchRow(String id, JsonNode info, JsonNode p) {
        long duration = Math.max(0, durationSeconds(info));
        boolean remake = isRemake(info, p);
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
        Map<String, Object> row = Dto.obj("id", id, "queueId", queue, "mode", modeLabel(queue, info.path("gameMode").asText("")),
                "remake", remake, "championKey", p.path("championId").asInt(), "championName", p.path("championName").asText(""),
                "win", p.path("win").asBoolean(), "kills", p.path("kills").asInt(), "deaths", p.path("deaths").asInt(),
                "assists", p.path("assists").asInt(),
                "cs", p.path("totalMinionsKilled").asInt() + p.path("neutralMinionsKilled").asInt(),
                "durationSec", duration, "endedAt", ended, "startedAt", info.path("gameCreation").asLong(),
                "position", POSITIONS.getOrDefault(p.path("teamPosition").asText(""), ""),
                "items", items, "spells", spells);
        if (info.path("x").asInt() >= SummonerHistory.SCHEMA) {
            row.putAll(details(info, p));
        }
        return row;
    }

    /**
     * Werte für Champions-Tabelle und Spielstil (wie op.gg): Seite, Kill-Beteiligung, Schaden und Anteil, Sicht,
     * Gold, Mehrfachkills, Lane-Vorsprung und der Champion des Lane-Gegners. Fehlende Riot-Felder bleiben {@code null}.
     */
    static Map<String, Object> details(JsonNode info, JsonNode p) {
        JsonNode c = p.path("c");
        int teamKills = info.path("tk").asInt();
        // Ausdrücklich Double: ein gemischter Bedingungsausdruck würde null in double auspacken.
        Double kp = null;
        if (c.path("killParticipation").isNumber()) {
            kp = Math.min(1, c.path("killParticipation").asDouble());
        } else if (teamKills > 0) {
            kp = Math.min(1, (double) (p.path("kills").asInt() + p.path("assists").asInt()) / teamKills);
        }
        Double damageShare = c.path("teamDamagePercentage").isNumber() ? Double.valueOf(c.path("teamDamagePercentage").asDouble()) : null;
        Integer soloKills = c.path("soloKills").isNumber() ? Integer.valueOf(c.path("soloKills").asInt()) : null;
        Boolean laneLead = info.path("o").isObject() && c.path("laningPhaseGoldExpAdvantage").isNumber()
                ? Boolean.valueOf(c.path("laningPhaseGoldExpAdvantage").asInt() > 0) : null;
        JsonNode opponent = info.path("o");
        int team = p.path("teamId").asInt();
        return Dto.obj("side", team == 100 ? "blue" : team == 200 ? "red" : "",
                "kp", kp,
                "damage", p.path("totalDamageDealtToChampions").asLong(),
                "damageShare", damageShare,
                "damageTaken", p.path("totalDamageTaken").asLong(), "gold", p.path("goldEarned").asLong(),
                "vision", p.path("visionScore").asInt(), "wardsPlaced", p.path("wardsPlaced").asInt(),
                "wardsKilled", p.path("wardsKilled").asInt(),
                "controlWards", p.path("visionWardsBoughtInGame").asInt(p.path("detectorWardsPlaced").asInt()),
                "multi", List.of(p.path("doubleKills").asInt(), p.path("tripleKills").asInt(),
                        p.path("quadraKills").asInt(), p.path("pentaKills").asInt()),
                "soloKills", soloKills, "laneLead", laneLead,
                "opponent", opponent.isObject() ? Dto.obj("key", opponent.path("championId").asInt(),
                        "name", opponent.path("championName").asText("")) : null);
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
