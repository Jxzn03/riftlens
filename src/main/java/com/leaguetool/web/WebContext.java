package com.leaguetool.web;

import com.leaguetool.cache.FileCache;
import com.leaguetool.config.AppSettings;
import com.leaguetool.data.GameData;
import com.leaguetool.data.GameDataService;
import com.leaguetool.data.ddragon.DDragonClient;
import com.leaguetool.data.ddragon.ImageRef;
import com.leaguetool.data.http.HttpFetcher;
import com.leaguetool.data.http.JavaHttpFetcher;
import com.leaguetool.data.http.OfflineAwareFetcher;
import com.leaguetool.data.itempassives.ItemPassiveUpdater;
import com.leaguetool.data.lolalytics.LolalyticsClient;
import com.leaguetool.data.riot.Platform;
import com.leaguetool.data.riot.RateLimiter;
import com.leaguetool.data.riot.RiotApi;
import com.leaguetool.data.riot.RiotApiClient;
import com.leaguetool.data.wiki.WikiClient;
import com.leaguetool.stats.GameItemClassifier;
import com.leaguetool.stats.ItemClassifier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Dienste der Oberfläche: Datenquellen, aktueller {@link GameData}-Snapshot und Bild-Cache.
 */
public final class WebContext implements AutoCloseable {

    private static final long LOAD_TIMEOUT_SECONDS = 120;

    private final AppSettings settings;
    private final FileCache cache;
    private final HttpFetcher http;
    private final String ddragonBase;
    private final ExecutorService background = Executors.newCachedThreadPool(daemon("lt-web-worker"));
    private final LolalyticsClient lolalytics;
    private final ItemPassiveUpdater itemPassives;
    private final RateLimiter riotLimiter = RateLimiter.riotDevKey();
    private final Map<Platform, RiotApiClient> riotClients = new EnumMap<>(Platform.class);
    private final Map<String, CompletableFuture<Path>> downloads = new ConcurrentHashMap<>();

    private volatile GameDataService gameDataService;
    private volatile WikiClient wiki;
    private volatile GameData gameData;
    private volatile int dataVersion;
    private volatile BootstrapCache bootstrapCache;
    private volatile BiConsumer<String, Object> events = (name, data) -> { };

    private CompletableFuture<GameData> loading;
    private long generation;
    private GameData classifierData;
    private ItemClassifier classifier;

    public WebContext(AppSettings settings) {
        this(settings, new JavaHttpFetcher(), DDragonClient.BASE_URL);
    }

    WebContext(AppSettings settings, HttpFetcher fetcher, String ddragonBase) {
        this.settings = settings;
        this.ddragonBase = ddragonBase;
        this.cache = new FileCache(settings.cacheDir());
        this.http = new OfflineAwareFetcher(fetcher, settings::offline);
        this.lolalytics = new LolalyticsClient(http, cache);
        this.itemPassives = new ItemPassiveUpdater(cache);
        rebuildServices();
    }

    void setEvents(BiConsumer<String, Object> events) {
        this.events = events;
    }

    /** Sendet ein Ereignis an alle offenen Fenster. */
    void publish(String name, Object data) {
        events.accept(name, data);
    }

    AppSettings settings() {
        return settings;
    }

    FileCache cache() {
        return cache;
    }

    ItemPassiveUpdater itemPassives() {
        return itemPassives;
    }

    synchronized void rebuildServices() {
        GameData shown = gameData;
        gameDataService = new GameDataService(new DDragonClient(http, cache, settings.locale(), ddragonBase), shown);
        wiki = new WikiClient(http, cache);
    }

    /** Startet das Laden des aktuellen Patches; ein laufender älterer Ladevorgang wird verworfen. */
    public synchronized CompletableFuture<GameData> reload() {
        GameDataService service = gameDataService;
        long gen = ++generation;
        loading = CompletableFuture.supplyAsync(() -> {
            try {
                GameData data = service.loadLatest();
                itemPassives.update(data, wiki().orElse(null));
                return data;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, background).whenComplete((data, error) -> onLoaded(gen, data));
        return loading;
    }

    private void onLoaded(long gen, GameData data) {
        synchronized (this) {
            if (gen != generation || data == null) {
                return;
            }
            gameData = data;
            dataVersion++;
        }
        publish("data-changed", Dto.obj("version", dataVersion));
    }

    /** Wartet auf den laufenden Ladevorgang. Schlägt er fehl, bleibt ein vorhandener Snapshot gültig. */
    GameData awaitData() {
        CompletableFuture<GameData> future;
        synchronized (this) {
            future = loading;
        }
        if (future == null) {
            future = reload();
        }
        try {
            future.get(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "Laden unterbrochen");
        } catch (ExecutionException | TimeoutException e) {
            if (gameData == null) {
                throw new ApiException(503, "Keine Daten: " + message(e)
                        + " – Netzwerk prüfen oder Offline-Modus mit vorhandenem Cache nutzen.");
            }
        }
        GameData data = gameData;
        if (data == null) {
            throw new ApiException(503, "Noch keine Spieldaten geladen");
        }
        return data;
    }

    int dataVersion() {
        return dataVersion;
    }

    /** Serialisierte Startdaten zu genau diesem Snapshot oder {@code null}. */
    byte[] bootstrapJson(GameData data) {
        BootstrapCache cached = bootstrapCache;
        return cached != null && cached.data() == data ? cached.json() : null;
    }

    void setBootstrapJson(GameData data, byte[] bytes) {
        bootstrapCache = new BootstrapCache(data, bytes);
    }

    GameDataService gameDataService() {
        return gameDataService;
    }

    Optional<WikiClient> wiki() {
        return settings.wikiEnabled() ? Optional.of(wiki) : Optional.empty();
    }

    Optional<LolalyticsClient> lolalytics() {
        return settings.lolalyticsEnabled() ? Optional.of(lolalytics) : Optional.empty();
    }

    /** Riot-API-Client der eingestellten Plattform, sofern die Riot-API aktiviert ist. */
    Optional<RiotApi> riotApi() {
        if (!settings.riotEnabled()) {
            return Optional.empty();
        }
        return Optional.of(riotClient(Platform.fromId(settings.platform()).orElse(Platform.EUW1)));
    }

    /** Client für eine Plattform; alle Clients teilen sich einen Rate-Limiter (Dev-Key-Limits). */
    synchronized RiotApiClient riotClient(Platform platform) {
        return riotClients.computeIfAbsent(platform, p -> new RiotApiClient(http, settings::riotApiKey, p, riotLimiter));
    }

    synchronized ItemClassifier itemClassifier(GameData data) {
        if (classifierData != data) {
            classifier = new GameItemClassifier(data.items());
            classifierData = data;
        }
        return classifier;
    }

    /** Bilddatei aus dem Cache; fehlt sie, wird sie (je URL nur einmal gleichzeitig) heruntergeladen. */
    Path image(ImageRef ref) throws IOException {
        Optional<Path> existing = cache.file(ref.cacheScope(), ref.cacheKey());
        if (existing.isPresent()) {
            return existing.get();
        }
        CompletableFuture<Path> future = downloads.computeIfAbsent(ref.url(), url ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return cache.writeBytes(ref.cacheScope(), ref.cacheKey(), http.getBytes(url));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }, background));
        future.whenComplete((path, error) -> downloads.remove(ref.url(), future));
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Unterbrochen", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException(message(e), e);
        }
    }

    /** Verständlichste Meldung einer (ggf. verpackten) Ausnahme. */
    static String message(Throwable error) {
        Throwable t = error;
        while ((t instanceof CompletionException || t instanceof ExecutionException
                || t instanceof UncheckedIOException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null || t.getMessage().isBlank() ? t.getClass().getSimpleName() : t.getMessage();
    }

    @Override
    public void close() {
        background.shutdownNow();
    }

    private static java.util.concurrent.ThreadFactory daemon(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    private record BootstrapCache(GameData data, byte[] json) {
    }
}
