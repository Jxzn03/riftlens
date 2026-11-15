package com.leaguetool.data.riot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leaguetool.data.http.HttpFetcher;
import com.leaguetool.data.http.HttpResult;
import com.leaguetool.model.Tier;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@link RiotApi} über HTTP. Vor jeder Anfrage wird der {@link RateLimiter} befragt.
 *
 * <ul>
 *   <li>HTTP 429: {@code Retry-After} (Sekunden, Standard 1) wird beachtet, der Limiter global pausiert,
 *       bis zu 5 Wiederholungen.</li>
 *   <li>HTTP 500/502/503/504: bis zu 3 Wiederholungen mit Backoff 1 s, 2 s, 4 s.</li>
 *   <li>sonstige Fehlerstatus: {@link RiotApiException} mit deutscher Meldung.</li>
 * </ul>
 *
 * Der API-Key wird bei jeder Anfrage neu vom Supplier gelesen (Änderungen in den Einstellungen wirken sofort)
 * und nur im Header {@code X-Riot-Token} gesendet, nie in URLs oder Meldungen.
 */
public final class RiotApiClient implements RiotApi {

    static final int MAX_RATE_LIMIT_RETRIES = 5;
    static final int MAX_SERVER_RETRIES = 3;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long MAX_RETRY_AFTER_SECONDS = 600;
    private static final Set<Integer> RETRYABLE_SERVER_STATUS = Set.of(500, 502, 503, 504);
    private static final Set<String> DIVISIONS = Set.of("I", "II", "III", "IV");

    private final HttpFetcher http;
    private final Supplier<Optional<String>> apiKey;
    private final Platform platform;
    private final RateLimiter limiter;
    private final RateLimiter.TimeSource time;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public RiotApiClient(HttpFetcher http, Supplier<Optional<String>> apiKey, Platform platform, RateLimiter limiter) {
        this(http, apiKey, platform, limiter, limiter.timeSource());
    }

    /** Konstruktor mit eigener Zeitquelle für Backoff und Retry-After (Tests). */
    public RiotApiClient(HttpFetcher http, Supplier<Optional<String>> apiKey, Platform platform, RateLimiter limiter,
                         RateLimiter.TimeSource time) {
        this.http = http;
        this.apiKey = apiKey;
        this.platform = platform;
        this.limiter = limiter;
        this.time = time;
    }

    @Override
    public Platform platform() {
        return platform;
    }

    @Override
    public List<LeagueEntry> leagueEntries(String queue, Tier tier, String division, int page) throws IOException {
        if (tier.isApex()) {
            throw new IllegalArgumentException("Für " + tier.label() + " apexLeague(...) verwenden");
        }
        if (division == null || !DIVISIONS.contains(division)) {
            throw new IllegalArgumentException("Ungültige Division: " + division + " (erlaubt: I, II, III, IV)");
        }
        if (page < 1) {
            throw new IllegalArgumentException("Seite muss mindestens 1 sein: " + page);
        }
        String url = platform.platformBaseUrl() + "/lol/league/v4/entries/" + segment(queue) + "/"
                + tier.name() + "/" + division + "?page=" + page;
        JsonNode root = getJson(url);
        List<LeagueEntry> result = new ArrayList<>();
        for (JsonNode e : root) {
            result.add(parseEntry(e, queue, tier));
        }
        return result;
    }

    /** Einträge sind nach League Points absteigend sortiert. */
    @Override
    public List<LeagueEntry> apexLeague(String queue, Tier tier) throws IOException {
        String path = switch (tier) {
            case CHALLENGER -> "challengerleagues";
            case GRANDMASTER -> "grandmasterleagues";
            case MASTER -> "masterleagues";
            default -> throw new IllegalArgumentException(tier.label() + " ist kein Apex-Tier");
        };
        String url = platform.platformBaseUrl() + "/lol/league/v4/" + path + "/by-queue/" + segment(queue);
        JsonNode root = getJson(url);
        String listQueue = root.path("queue").asText(queue);
        Tier listTier = parseTier(root.path("tier").asText(""), tier);
        List<LeagueEntry> result = new ArrayList<>();
        for (JsonNode e : root.path("entries")) {
            result.add(parseEntry(e, listQueue, listTier));
        }
        result.sort(Comparator.comparingInt(LeagueEntry::leaguePoints).reversed());
        return result;
    }

    @Override
    public String puuidBySummonerId(String summonerId) throws IOException {
        JsonNode root = getJson(platform.platformBaseUrl() + "/lol/summoner/v4/summoners/" + segment(summonerId));
        String puuid = text(root, "puuid");
        if (puuid == null) {
            throw new IOException("Antwort von summoner-v4 enthält keine puuid");
        }
        return puuid;
    }

    @Override
    public RiotAccount accountByRiotId(String gameName, String tagLine) throws IOException {
        JsonNode root = getJson(platform.accountBaseUrl() + "/riot/account/v1/accounts/by-riot-id/"
                + segment(gameName) + "/" + segment(tagLine));
        String puuid = text(root, "puuid");
        if (puuid == null) {
            throw new IOException("Antwort von account-v1 enthält keine puuid");
        }
        return new RiotAccount(puuid, root.path("gameName").asText(gameName), root.path("tagLine").asText(tagLine));
    }

    @Override
    public List<String> matchIdsByPuuid(String puuid, int queueId, int start, int count, Long startTimeEpochSec)
            throws IOException {
        if (count < 1 || count > 100) {
            throw new IllegalArgumentException("count muss zwischen 1 und 100 liegen: " + count);
        }
        if (start < 0) {
            throw new IllegalArgumentException("start darf nicht negativ sein: " + start);
        }
        StringBuilder url = new StringBuilder(platform.matchBaseUrl())
                .append("/lol/match/v5/matches/by-puuid/").append(segment(puuid)).append("/ids")
                .append("?queue=").append(queueId)
                .append("&start=").append(start)
                .append("&count=").append(count);
        if (startTimeEpochSec != null) {
            url.append("&startTime=").append(startTimeEpochSec);
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode id : getJson(url.toString())) {
            if (id.isTextual()) {
                ids.add(id.asText());
            }
        }
        return ids;
    }

    @Override
    public JsonNode match(String matchId) throws IOException {
        return getJson(platform.matchBaseUrl() + "/lol/match/v5/matches/" + segment(matchId));
    }

    @Override
    public JsonNode timeline(String matchId) throws IOException {
        return getJson(platform.matchBaseUrl() + "/lol/match/v5/matches/" + segment(matchId) + "/timeline");
    }

    @Override
    public JsonNode platformStatus() throws IOException {
        return getJson(platform.platformBaseUrl() + "/lol/status/v4/platform-data");
    }

    // ---------------------------------------------------------------- Summoner-Abfrage

    /** summoner-v4 {@code /lol/summoner/v4/summoners/by-puuid/{puuid}} (Level, Profil-Icon). */
    public JsonNode summonerByPuuid(String puuid) throws IOException {
        return getJson(platform.platformBaseUrl() + "/lol/summoner/v4/summoners/by-puuid/" + segment(puuid));
    }

    /** league-v4 {@code /lol/league/v4/entries/by-puuid/{puuid}}: Ranglisten-Einträge eines Spielers. */
    public List<LeagueEntry> leagueEntriesByPuuid(String puuid) throws IOException {
        List<LeagueEntry> result = new ArrayList<>();
        for (JsonNode e : getJson(platform.platformBaseUrl() + "/lol/league/v4/entries/by-puuid/" + segment(puuid))) {
            result.add(parseEntry(e, "", null));
        }
        return result;
    }

    /** champion-mastery-v4: die {@code count} Champions mit der höchsten Meisterschaft. */
    public JsonNode topChampionMasteries(String puuid, int count) throws IOException {
        return getJson(platform.platformBaseUrl() + "/lol/champion-mastery/v4/champion-masteries/by-puuid/"
                + segment(puuid) + "/top?count=" + count);
    }

    /** match-v5: die letzten Match-IDs über alle Spielmodi. */
    public List<String> recentMatchIds(String puuid, int count) throws IOException {
        if (count < 1 || count > 100) {
            throw new IllegalArgumentException("count muss zwischen 1 und 100 liegen: " + count);
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode id : getJson(platform.matchBaseUrl() + "/lol/match/v5/matches/by-puuid/" + segment(puuid)
                + "/ids?start=0&count=" + count)) {
            if (id.isTextual()) {
                ids.add(id.asText());
            }
        }
        return ids;
    }

    // ---------------------------------------------------------------- HTTP

    /** GET mit Key, Rate-Limit und Wiederholungen; liefert den JSON-Body. */
    JsonNode getJson(String url) throws IOException {
        String key = apiKey.get().map(String::trim).filter(k -> !k.isEmpty()).orElseThrow(() ->
                new RiotApiException(401, "Kein Riot-API-Key konfiguriert (Einstellungen oder Umgebungsvariable "
                        + "RIOT_API_KEY). Einen Dev-Key gibt es unter developer.riotgames.com."));
        Map<String, String> headers = Map.of("X-Riot-Token", key, "Accept", "application/json");
        int rateLimitRetries = 0;
        int serverRetries = 0;
        while (true) {
            acquire(url);
            HttpResult r = http.get(url, headers);
            if (r.isSuccess()) {
                try {
                    return MAPPER.readTree(r.body());
                } catch (IOException e) {
                    throw new IOException("Ungültige JSON-Antwort der Riot-API für " + path(url), e);
                }
            }
            int status = r.status();
            if (status == 429) {
                if (rateLimitRetries >= MAX_RATE_LIMIT_RETRIES) {
                    throw new RiotApiException(429, "Rate-Limit überschritten (HTTP 429), auch nach "
                            + MAX_RATE_LIMIT_RETRIES + " Wiederholungen: " + path(url));
                }
                rateLimitRetries++;
                long seconds = retryAfterSeconds(r);
                limiter.pauseUntil(time.nanoTime() + seconds * NANOS_PER_SECOND);
                continue;
            }
            if (RETRYABLE_SERVER_STATUS.contains(status)) {
                if (serverRetries >= MAX_SERVER_RETRIES) {
                    throw new RiotApiException(status, "Riot-Server-Fehler (HTTP " + status + "), auch nach "
                            + MAX_SERVER_RETRIES + " Wiederholungen: " + path(url));
                }
                sleep((1L << serverRetries) * NANOS_PER_SECOND, url);
                serverRetries++;
                continue;
            }
            throw error(status, url);
        }
    }

    private void acquire(String url) throws InterruptedIOException {
        try {
            limiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            InterruptedIOException ex = new InterruptedIOException("Abgebrochen: " + path(url));
            ex.initCause(e);
            throw ex;
        }
    }

    private void sleep(long nanos, String url) throws InterruptedIOException {
        try {
            time.sleepNanos(nanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            InterruptedIOException ex = new InterruptedIOException("Abgebrochen: " + path(url));
            ex.initCause(e);
            throw ex;
        }
    }

    private static long retryAfterSeconds(HttpResult r) {
        Optional<String> header = r.header("Retry-After");
        if (header.isEmpty()) {
            return 1;
        }
        try {
            double seconds = Double.parseDouble(header.get().trim());
            if (!Double.isFinite(seconds) || seconds < 0) {
                return 1;
            }
            return Math.min(MAX_RETRY_AFTER_SECONDS, (long) Math.ceil(seconds));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static RiotApiException error(int status, String url) {
        String p = path(url);
        String message = switch (status) {
            case 400 -> "Ungültige Anfrage an die Riot-API (HTTP 400): " + p;
            case 401 -> "Riot-API-Key fehlt oder wird nicht akzeptiert (HTTP 401)";
            case 403 -> "Key ungültig oder abgelaufen (Dev-Keys laufen nach 24 h ab) (HTTP 403)";
            case 404 -> "Daten nicht gefunden (HTTP 404): " + p;
            case 405 -> "Methode nicht erlaubt (HTTP 405): " + p;
            case 415 -> "Nicht unterstütztes Format (HTTP 415): " + p;
            default -> "Riot-API-Fehler (HTTP " + status + "): " + p;
        };
        return new RiotApiException(status, message);
    }

    // ---------------------------------------------------------------- Parsing

    private static LeagueEntry parseEntry(JsonNode e, String defaultQueue, Tier defaultTier) {
        return new LeagueEntry(
                text(e, "puuid"),
                text(e, "summonerId"),
                e.path("queueType").asText(defaultQueue),
                parseTier(e.path("tier").asText(""), defaultTier),
                e.path("rank").asText(defaultTier != null && defaultTier.isApex() ? "I" : ""),
                e.path("leaguePoints").asInt(0),
                e.path("wins").asInt(0),
                e.path("losses").asInt(0));
    }

    private static Tier parseTier(String text, Tier fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Tier.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** Textfeld oder {@code null}, wenn es fehlt, {@code null} oder leer ist. */
    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText("");
        return s.isBlank() ? null : s;
    }

    /** URL-Kodierung eines Pfadsegments (Leerzeichen als {@code %20}, nicht {@code +}). */
    static String segment(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Leeres Pfadsegment");
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Pfad ohne Host für Meldungen. */
    private static String path(String url) {
        int idx = url.indexOf(".api.riotgames.com");
        return idx >= 0 ? url.substring(idx + ".api.riotgames.com".length()) : url;
    }
}
