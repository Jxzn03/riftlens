package com.leaguetool.data.lolalytics;

import com.leaguetool.data.http.Interrupts;
import com.leaguetool.cache.FileCache;
import com.leaguetool.data.http.HttpFetcher;
import com.leaguetool.model.Role;

import java.io.IOException;
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

    public static final String DEFAULT_TIER = "emerald_plus";
    public static final String BASE_URL = "https://lolalytics.com/lol/tierlist/";
    public static final Duration MIN_INTERVAL = Duration.ofSeconds(2);
    public static final Duration MAX_AGE = Duration.ofHours(12);
    /** Pause nach einer fehlgeschlagenen Erneuerung im Hintergrund. */
    static final Duration REFRESH_PAUSE = Duration.ofMinutes(10);
    /** So oft prüft die wartende Erneuerung im Hintergrund, ob ein Abruf Vorrang braucht. */
    private static final long BACKGROUND_POLL_MS = 100;
    /** Datenschnittstelle der Website; liefert die Team-Werte, die nicht in der Seite stehen. Nicht dokumentiert. */
    static final String TEAM_API_URL = "https://a1.lolalytics.com/mega/";

    private final HttpFetcher http;
    private final FileCache cache;
    private final Duration minInterval;
    /** Letzter Patchwechsel: ältere Statistik-Einträge gelten als veraltet (nicht für die Team-Werte des Drafts). */
    private volatile java.util.function.Supplier<java.time.Instant> notBefore = () -> java.time.Instant.EPOCH;
    private final java.util.Map<String, Object> locks = new java.util.concurrent.ConcurrentHashMap<>();
    /** Laufende Abrufe, auf die jemand wartet; die Erneuerung im Hintergrund lässt ihnen den Vortritt. */
    private final java.util.concurrent.atomic.AtomicInteger foreground = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.Set<String> refreshing = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Map<String, Long> refreshPausedUntil = new java.util.concurrent.ConcurrentHashMap<>();
    /** Zuletzt geparste Cache-Inhalte; spart nur das erneute Parsen, an Lesen und Abrufen ändert sich nichts. */
    private final ParsedMemo parsed = new ParsedMemo(ParsedMemo.DEFAULT_CAPACITY);
    private java.util.concurrent.Executor refreshExecutor;
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

    /**
     * Grenze für veraltete Einträge nach einem Patchwechsel. Gilt für Tierlisten und Champion-Statistiken; die
     * Team-Werte ({@link #teammates}) behalten ihre 12 Stunden (Vorgabe für die Draft-Synergien).
     */
    public void setNotBefore(java.util.function.Supplier<java.time.Instant> notBefore) {
        this.notBefore = Objects.requireNonNull(notBefore, "notBefore");
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
     * Vollständige Tierliste mit allen Champions (aus dem eingebetteten Seitenzustand, ein Abruf genügt).
     *
     * @param lane {@code all} (je Champion die Hauptlane) oder eine einzelne Lane
     */
    public LolalyticsFullTierList fullTierList(String tier, String lane, LolalyticsPeriod period) throws IOException {
        return fullTierList(tier, lane, period, false);
    }

    /** @param staleOk ein veralteter Eintrag wird sofort geliefert und im Hintergrund erneuert ({@link #load}) */
    public LolalyticsFullTierList fullTierList(String tier, String lane, LolalyticsPeriod period, boolean staleOk)
            throws IOException {
        Source source = tierListSource(tier, lane, period);
        return parsed.parse(source.key(), load(source, staleOk), LolalyticsState::parseTierList);
    }

    /**
     * Wie {@link #fullTierList(String, String, LolalyticsPeriod)}, aber ohne Abruf und ohne Erneuerung im Hintergrund:
     * nur ein frischer Eintrag, sonst {@code null}. Liefert genau dann etwas, wenn der normale Aufruf ohne Abruf
     * auskäme.
     */
    public LolalyticsFullTierList fullTierListIfCached(String tier, String lane, LolalyticsPeriod period) throws IOException {
        Source source = tierListSource(tier, lane, period);
        String json = cachedOnly(source, false);
        return json == null ? null : parsed.parse(source.key(), json, LolalyticsState::parseTierList);
    }

    private Source tierListSource(String tier, String lane, LolalyticsPeriod period) {
        String t = safe(tier, DEFAULT_TIER);
        String l = safe(lane, "all");
        // "v4": enthält PBI und Lane-Anteil; ältere Cache-Einträge ohne diese Felder dürfen nicht weiterverwendet werden.
        String key = "lolalytics/full_v4_" + t + "_" + l + "_" + period.cacheSuffix() + ".json";
        String url = BASE_URL + "?tier=" + t + "&lane=" + l + patchQuery(period);
        return new Source(key, url, notBefore.get(), LolalyticsState::extractTierList);
    }

    /**
     * Statistik eines Champions (Builds, Runen, Spells, Skill-Reihenfolgen).
     *
     * @param slug Pfadname aus {@link LolalyticsFullTierList#slugs()}
     * @param lane eine Lane oder {@code null} für die Hauptlane des Champions
     */
    public LolalyticsBuild build(String slug, String tier, String lane, LolalyticsPeriod period) throws IOException {
        return build(slug, tier, lane, period, false);
    }

    /** @param staleOk ein veralteter Eintrag wird sofort geliefert und im Hintergrund erneuert ({@link #load}) */
    public LolalyticsBuild build(String slug, String tier, String lane, LolalyticsPeriod period, boolean staleOk)
            throws IOException {
        Source source = buildSource(slug, tier, lane, period);
        return parsed.parse(source.key(), load(source, staleOk), LolalyticsState::parseBuild);
    }

    /**
     * Wie {@link #build}, aber ohne Abruf; {@code null}, wenn kein passender Eintrag im Cache liegt.
     *
     * @param staleOk auch einen veralteten Eintrag liefern (und im Hintergrund erneuern), sonst nur einen frischen
     */
    public LolalyticsBuild buildIfCached(String slug, String tier, String lane, LolalyticsPeriod period, boolean staleOk)
            throws IOException {
        Source source = buildSource(slug, tier, lane, period);
        String json = cachedOnly(source, staleOk);
        return json == null ? null : parsed.parse(source.key(), json, LolalyticsState::parseBuild);
    }

    private Source buildSource(String slug, String tier, String lane, LolalyticsPeriod period) {
        requireSlug(slug);
        String t = safe(tier, DEFAULT_TIER);
        String l = lane == null ? null : safe(lane, "all");
        // "v4": enthält Matchups mit Delta-Werten, Schaden, Spieldauer und Itemplätze; ältere Cache-Einträge haben diese Felder nicht.
        String key = "lolalytics/build_v4_" + slug + "_" + t + "_" + (l == null ? "main" : l) + "_" + period.cacheSuffix() + ".json";
        String url = "https://lolalytics.com/lol/" + slug + "/build/?tier=" + t + (l == null ? "" : "&lane=" + l)
                + patchQuery(period);
        return new Source(key, url, notBefore.get(), LolalyticsState::extractBuild);
    }

    /**
     * Team-Werte eines Champions der letzten 30 Tage. Steht nicht in der Seite, sondern kommt wie im Browser von der
     * Datenschnittstelle der Website; es gelten dieselbe Drosselung und derselbe Cache wie für die Seiten.
     */
    public LolalyticsPairs teammates(String slug, String tier, String lane) throws IOException {
        return teammates(slug, tier, lane, false);
    }

    /** @param staleOk ein veralteter Eintrag wird sofort geliefert und im Hintergrund erneuert ({@link #load}) */
    public LolalyticsPairs teammates(String slug, String tier, String lane, boolean staleOk) throws IOException {
        Source source = teamSource(slug, tier, lane);
        return parsed.parse(source.key(), load(source, staleOk), LolalyticsState::parseTeam);
    }

    /** Wie {@link #buildIfCached}, für die Team-Werte. */
    public LolalyticsPairs teammatesIfCached(String slug, String tier, String lane, boolean staleOk) throws IOException {
        Source source = teamSource(slug, tier, lane);
        String json = cachedOnly(source, staleOk);
        return json == null ? null : parsed.parse(source.key(), json, LolalyticsState::parseTeam);
    }

    private static Source teamSource(String slug, String tier, String lane) {
        requireSlug(slug);
        String t = safe(tier, DEFAULT_TIER);
        String l = safe(lane, "all");
        String key = "lolalytics/team_v1_" + slug + "_" + t + "_" + l + "_30d.json";
        String url = TEAM_API_URL + "?ep=build-team&v=1&patch=" + LolalyticsPeriod.LAST_30_DAYS.patchParam() + "&c=" + slug
                + "&lane=" + l + "&tier=" + t + "&queue=ranked&region=all";
        // Bewusst ohne Patch-Grenze: Für die Draft-Synergien gelten 12 h Cache (Vorgabe des Nutzers).
        return new Source(key, url, java.time.Instant.EPOCH, LolalyticsState::extractTeam);
    }

    private static void requireSlug(String slug) {
        if (slug == null || !slug.matches("[a-z0-9]{1,30}")) {
            throw new IllegalArgumentException("Ungültiger Champion-Pfad: " + slug);
        }
    }

    private static String patchQuery(LolalyticsPeriod period) {
        return period.patchParam() == null ? "" : "&patch=" + period.patchParam();
    }

    /** Ein Cache-Eintrag samt Herkunft; {@code notBefore} ist die Patch-Grenze, ab der er als veraltet gilt. */
    private record Source(String key, String url, java.time.Instant notBefore, Extractor extractor) {
    }

    /**
     * Mit {@code staleOk} wird ein vorhandener Eintrag sofort geliefert, auch wenn er älter als {@link #MAX_AGE} oder
     * von vor dem Patchwechsel ist; der Hintergrund-Thread erneuert ihn dann einmal ({@link #refreshIfOld}). Ohne
     * Eintrag wird wie sonst abgerufen. Abrufzahl und Drosselung bleiben gleich, nur wartet niemand auf den Abruf.
     */
    private String load(Source source, boolean staleOk) throws IOException {
        String existing = staleOk ? cachedOnly(source, true) : null;
        return existing != null ? existing : cachedExtract(source);
    }

    /** Nur aus dem Cache. Einträge werden atomar geschrieben, das Lesen eines alten Eintrags braucht keine Sperre. */
    private String cachedOnly(Source source, boolean staleOk) throws IOException {
        if (!staleOk) {
            synchronized (lock(source.key())) {
                return cache.isFresh(FileCache.GLOBAL, source.key(), MAX_AGE, source.notBefore())
                        ? cache.readText(FileCache.GLOBAL, source.key()).orElse(null) : null;
            }
        }
        String existing = cache.readText(FileCache.GLOBAL, source.key()).orElse(null);
        if (existing != null) {
            refreshIfOld(source);
        }
        return existing;
    }

    private Object lock(String key) {
        return locks.computeIfAbsent(key, k -> new Object());
    }

    /**
     * Holt die Seite höchstens einmal gleichzeitig je Eintrag und speichert nur die extrahierten Daten. Unbrauchbare
     * Antworten landen so gar nicht erst im Cache; ein älterer gültiger Eintrag bleibt dann erhalten.
     */
    private String cachedExtract(Source source) throws IOException {
        foreground.incrementAndGet();
        try {
            synchronized (lock(source.key())) {
                return cache.getFreshOrStale(FileCache.GLOBAL, source.key(), MAX_AGE, source.notBefore(), () -> {
                    throttle(false);
                    return source.extractor().extract(http.getText(source.url()));
                }).content();
            }
        } finally {
            foreground.decrementAndGet();
        }
    }

    // ------------------------------------------------------------------ Erneuerung im Hintergrund

    /**
     * Ersetzt den Thread für die Erneuerung im Hintergrund. Tests übergeben {@code Runnable::run}, dann läuft sie sofort
     * im aufrufenden Thread.
     */
    public void setRefreshExecutor(java.util.concurrent.Executor executor) {
        synchronized (this) {
            this.refreshExecutor = Objects.requireNonNull(executor, "executor");
        }
    }

    private synchronized java.util.concurrent.Executor refreshExecutor() {
        if (refreshExecutor == null) {
            refreshExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("lolalytics-refresh").factory());
        }
        return refreshExecutor;
    }

    /**
     * Stellt einen veralteten Eintrag einmal zur Erneuerung an. Schlägt sie fehl (z. B. gesperrt), bleibt der alte
     * Eintrag und es gibt {@link #REFRESH_PAUSE} lang keinen neuen Versuch.
     */
    private void refreshIfOld(Source source) throws IOException {
        String key = source.key();
        if (cache.isFresh(FileCache.GLOBAL, key, MAX_AGE, source.notBefore())) {
            return;
        }
        Long pausedUntil = refreshPausedUntil.get(key);
        if (pausedUntil != null && pausedUntil - System.nanoTime() > 0) {
            return;
        }
        if (!refreshing.add(key)) {
            return;
        }
        refreshExecutor().execute(() -> {
            try {
                // Den Platz in der Drosselung außerhalb der Sperre des Eintrags reservieren: Ein wartender Abruf
                // desselben Eintrags hängt so nie hinter der Erneuerung.
                if (!cache.isFresh(FileCache.GLOBAL, key, MAX_AGE, source.notBefore())) {
                    throttle(true);
                }
                synchronized (lock(key)) {
                    if (!cache.isFresh(FileCache.GLOBAL, key, MAX_AGE, source.notBefore())) {
                        cache.writeText(FileCache.GLOBAL, key, source.extractor().extract(http.getText(source.url())));
                    }
                }
                refreshPausedUntil.remove(key);
            } catch (IOException | RuntimeException e) {
                refreshPausedUntil.put(key, System.nanoTime() + REFRESH_PAUSE.toNanos());
            } finally {
                refreshing.remove(key);
            }
        });
    }

    @FunctionalInterface
    private interface Extractor {
        String extract(String html) throws IOException;
    }

    /**
     * Reserviert den nächsten Platz: mindestens {@link #minInterval} nach dem Start der vorigen Anfrage. Gewartet wird
     * außerhalb der Sperre, damit ein Abruf, auf den jemand wartet, den frei werdenden Platz sofort bekommt.
     *
     * @param background Erneuerung im Hintergrund: reserviert nur, solange kein solcher Abruf läuft, und prüft das beim
     *                   Warten laufend. Die Abstände zwischen zwei Anfragen bleiben dieselben.
     */
    private void throttle(boolean background) throws IOException {
        while (true) {
            long waitMs;
            synchronized (this) {
                waitMs = background && foreground.get() > 0 ? BACKGROUND_POLL_MS
                        : requestedBefore ? minInterval.toMillis() - (System.nanoTime() - lastRequestNanos) / 1_000_000 : 0;
                if (waitMs <= 0) {
                    requestedBefore = true;
                    lastRequestNanos = System.nanoTime();
                    return;
                }
            }
            try {
                Thread.sleep(background ? Math.min(waitMs, BACKGROUND_POLL_MS) : waitMs);
            } catch (InterruptedException e) {
                throw Interrupts.toIo(e, "Abgebrochen");
            }
        }
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
