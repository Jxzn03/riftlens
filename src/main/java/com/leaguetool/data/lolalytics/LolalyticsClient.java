package com.leaguetool.data.lolalytics;

import com.leaguetool.cache.FileCache;
import com.leaguetool.data.http.HttpFetcher;
import com.leaguetool.model.Role;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Holt die öffentliche Tierliste von Lolalytics (nur privater Eigengebrauch, Anzeige in der App).
 *
 * <p>Rücksicht auf den Betreiber: ehrlicher User-Agent des Fetchers, mindestens {@link #MIN_INTERVAL}
 * zwischen zwei Anfragen, Antworten werden {@link #MAX_AGE} lang gecacht (bei Netzfehler gilt auch ein
 * älterer Cache-Eintrag). Es gibt keine Umgehung von Schutzmaßnahmen: Wird der Zugriff verweigert, wird
 * der Fehler gemeldet.
 */
public final class LolalyticsClient {

    public static final String BASE_URL = "https://lolalytics.com/lol/tierlist/";
    public static final Duration MIN_INTERVAL = Duration.ofSeconds(2);
    public static final Duration MAX_AGE = Duration.ofHours(12);
    /** Datenschnittstelle der Website; liefert die Team-Werte, die nicht in der Seite stehen. Nicht dokumentiert. */
    static final String TEAM_API_URL = "https://a1.lolalytics.com/mega/";

    private final HttpFetcher http;
    private final FileCache cache;
    private final Duration minInterval;
    private final java.util.Map<String, Object> locks = new java.util.concurrent.ConcurrentHashMap<>();
    private long lastRequestNanos;
    private boolean requestedBefore;

    public LolalyticsClient(HttpFetcher http, FileCache cache) {
        this(http, cache, MIN_INTERVAL);
    }

    /** @param minInterval Mindestabstand zwischen zwei Anfragen; außerhalb von Tests immer {@link #MIN_INTERVAL} */
    public LolalyticsClient(HttpFetcher http, FileCache cache, Duration minInterval) {
        this.http = Objects.requireNonNull(http, "http");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.minInterval = Objects.requireNonNull(minInterval, "minInterval");
    }

    /** Name einer Rolle in den URLs von Lolalytics. */
    public static String lane(Role role) {
        return switch (role) {
            case TOP -> "top";
            case JUNGLE -> "jungle";
            case MIDDLE -> "middle";
            case BOTTOM -> "bottom";
            case UTILITY -> "support";
        };
    }

    /**
     * @param tier z. B. {@code emerald_plus}, {@code gold}, {@code all}
     * @param lane {@code all}, {@code top}, {@code jungle}, {@code middle}, {@code bottom}, {@code support}
     */
    public LolalyticsTierList tierList(String tier, String lane) throws IOException {
        String t = safe(tier, "emerald_plus");
        String l = safe(lane, "all");
        String key = "lolalytics/tierlist_" + t + "_" + l + ".html";
        FileCache.CacheResult res = cache.getFreshOrStale(FileCache.GLOBAL, key, MAX_AGE, () -> fetch(t, l));
        try {
            return LolalyticsParser.parse(res.content());
        } catch (IOException e) {
            cache.delete(FileCache.GLOBAL, key); // unbrauchbaren Eintrag nicht behalten
            throw e;
        }
    }

    /** Lanes, die für die Gesamtansicht zusätzlich geholt werden. */
    public static final java.util.List<String> LANES = java.util.List.of("top", "jungle", "middle", "bottom", "support");

    /**
     * Gesamtansicht: Die Seite liefert pro Abfrage nur die ersten ~20 Champions befüllt aus. Darum werden
     * "all" und alle fünf Lanes geholt (je gecacht, mit Pause) und zusammengeführt; je Champion gilt die
     * Zeile mit dem größten Lane-Anteil.
     */
    public LolalyticsTierList tierListAllLanes(String tier) throws IOException {
        LolalyticsTierList base = tierList(tier, "all");
        java.util.Map<String, LolalyticsChampion> merged = new java.util.LinkedHashMap<>();
        base.champions().forEach(c -> merged.put(c.slug(), c));
        for (String lane : LANES) {
            for (LolalyticsChampion c : tierList(tier, lane).champions()) {
                merged.merge(c.slug(), c, (a, b) -> b.laneShare() > a.laneShare() ? b : a);
            }
        }
        return new LolalyticsTierList(base.patch(), base.avgWinRate(), new java.util.ArrayList<>(merged.values()));
    }

    /**
     * Vollständige Tierliste mit allen Champions (aus dem eingebetteten Seitenzustand, ein Abruf genügt).
     *
     * @param lane {@code all} (je Champion die Hauptlane) oder eine einzelne Lane
     */
    public LolalyticsFullTierList fullTierList(String tier, String lane, LolalyticsPeriod period) throws IOException {
        String t = safe(tier, "emerald_plus");
        String l = safe(lane, "all");
        // "v4": enthält PBI und Lane-Anteil; ältere Cache-Einträge ohne diese Felder dürfen nicht weiterverwendet werden.
        String key = "lolalytics/full_v4_" + t + "_" + l + "_" + period.cacheSuffix() + ".json";
        String url = BASE_URL + "?tier=" + t + "&lane=" + l + patchQuery(period);
        return LolalyticsState.parseTierList(cachedExtract(key, url, LolalyticsState::extractTierList));
    }

    /**
     * Statistik eines Champions (Builds, Runen, Spells, Skill-Reihenfolgen).
     *
     * @param slug Pfadname aus {@link LolalyticsFullTierList#slugs()}
     * @param lane eine Lane oder {@code null} für die Hauptlane des Champions
     */
    public LolalyticsBuild build(String slug, String tier, String lane, LolalyticsPeriod period) throws IOException {
        requireSlug(slug);
        String t = safe(tier, "emerald_plus");
        String l = lane == null ? null : safe(lane, "all");
        // "v3": enthält Matchups mit Delta-Werten, Schaden und Spieldauer; ältere Cache-Einträge haben diese Felder nicht.
        String key = "lolalytics/build_v3_" + slug + "_" + t + "_" + (l == null ? "main" : l) + "_" + period.cacheSuffix() + ".json";
        String url = "https://lolalytics.com/lol/" + slug + "/build/?tier=" + t + (l == null ? "" : "&lane=" + l)
                + patchQuery(period);
        return LolalyticsState.parseBuild(cachedExtract(key, url, LolalyticsState::extractBuild));
    }

    /**
     * Team-Werte eines Champions der letzten 30 Tage. Steht nicht in der Seite, sondern kommt wie im Browser von der
     * Datenschnittstelle der Website; es gelten dieselbe Drosselung und derselbe Cache wie für die Seiten.
     */
    public LolalyticsPairs teammates(String slug, String tier, String lane) throws IOException {
        requireSlug(slug);
        String t = safe(tier, "emerald_plus");
        String l = safe(lane, "all");
        String key = "lolalytics/team_v1_" + slug + "_" + t + "_" + l + "_30d.json";
        String url = TEAM_API_URL + "?ep=build-team&v=1&patch=" + LolalyticsPeriod.LAST_30_DAYS.patchParam() + "&c=" + slug
                + "&lane=" + l + "&tier=" + t + "&queue=ranked&region=all";
        return LolalyticsState.parseTeam(cachedExtract(key, url, LolalyticsState::extractTeam));
    }

    private static void requireSlug(String slug) {
        if (slug == null || !slug.matches("[a-z0-9]{1,30}")) {
            throw new IllegalArgumentException("Ungültiger Champion-Pfad: " + slug);
        }
    }

    private static String patchQuery(LolalyticsPeriod period) {
        return period.patchParam() == null ? "" : "&patch=" + period.patchParam();
    }

    /**
     * Holt die Seite höchstens einmal gleichzeitig je Eintrag und speichert nur die extrahierten Daten. Unbrauchbare
     * Antworten landen so gar nicht erst im Cache; ein älterer gültiger Eintrag bleibt dann erhalten.
     */
    private String cachedExtract(String key, String url, Extractor extractor) throws IOException {
        synchronized (locks.computeIfAbsent(key, k -> new Object())) {
            return cache.getFreshOrStale(FileCache.GLOBAL, key, MAX_AGE, () -> {
                throttle();
                return extractor.extract(http.getText(url));
            }).content();
        }
    }

    @FunctionalInterface
    private interface Extractor {
        String extract(String html) throws IOException;
    }

    private String fetch(String tier, String lane) throws IOException {
        throttle();
        return http.getText(BASE_URL + "?tier=" + tier + "&lane=" + lane);
    }

    private synchronized void throttle() throws IOException {
        if (requestedBefore) {
            long waitMs = minInterval.toMillis() - (System.nanoTime() - lastRequestNanos) / 1_000_000;
            if (waitMs > 0) {
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Abgebrochen");
                }
            }
        }
        requestedBefore = true;
        lastRequestNanos = System.nanoTime();
    }

    private static String safe(String v, String fallback) {
        if (v == null || v.isBlank()) {
            return fallback;
        }
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (!s.matches("[a-z0-9_]{1,30}")) {
            throw new IllegalArgumentException("Ungültiger Wert: " + v);
        }
        return s;
    }
}
