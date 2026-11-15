package com.leaguetool.data;

import com.leaguetool.data.ddragon.DDragonClient;
import com.leaguetool.model.Champion;
import com.leaguetool.model.ChampionDetail;
import com.leaguetool.model.Item;
import com.leaguetool.model.RuneTree;
import com.leaguetool.model.SummonerSpell;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Lädt und hält die statischen Spieldaten des aktuellen Patches. Fällt ohne Netz auf den neuesten
 * vollständig gecachten Patch zurück.
 */
public final class GameDataService {

    private final DDragonClient ddragon;
    /** Schlüssel: {@code patch|championId}, damit späte Antworten nie unter einem anderen Patch landen. */
    private final Map<String, ChampionDetail> detailCache = new ConcurrentHashMap<>();
    private volatile GameData current;

    public GameDataService(DDragonClient ddragon) {
        this.ddragon = ddragon;
    }

    /**
     * Neuer Dienst, der bis zum nächsten erfolgreichen Laden den bisherigen Snapshot weiterführt
     * (z. B. nach Änderung der Einstellungen), damit Detailabfragen nicht ins Leere laufen.
     */
    public GameDataService(DDragonClient ddragon, GameData initial) {
        this.ddragon = ddragon;
        this.current = initial;
    }

    public DDragonClient ddragon() {
        return ddragon;
    }

    /** Lädt den aktuellen Patch (oder offline den neuesten gecachten). */
    public GameData loadLatest() throws IOException {
        String patch = ddragon.latestPatch();
        try {
            return load(patch);
        } catch (IOException e) {
            for (String cached : cachedFallbacks(patch)) {
                try {
                    return load(cached, true);
                } catch (IOException ignored) {
                    // nächsten Patch versuchen
                }
            }
            throw e;
        }
    }

    public GameData load(String patch) throws IOException {
        return load(patch, ddragon.isOffline());
    }

    private GameData load(String patch, boolean offline) throws IOException {
        // Die vier Dateien sind unabhängig → parallel laden (wichtig beim ersten Start ohne Cache).
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<List<Champion>> champions = exec.submit(() -> ddragon.champions(patch));
            Future<List<Item>> items = exec.submit(() -> ddragon.items(patch));
            Future<List<RuneTree>> runes = exec.submit(() -> ddragon.runeTrees(patch));
            Future<List<SummonerSpell>> spells = exec.submit(() -> ddragon.summonerSpells(patch));
            GameData data = new GameData(patch, offline,
                    await(champions), await(items), await(runes), await(spells));
            current = data;
            return data;
        }
    }

    private static <T> T await(Future<T> future) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Laden abgebrochen");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IOException(cause);
        }
    }

    /** Zuletzt geladener Snapshot oder {@code null}. */
    public GameData current() {
        return current;
    }

    /** Champion-Details für den aktuellen Snapshot; im Speicher zwischengespeichert. */
    public ChampionDetail championDetail(String championId) throws IOException {
        GameData data = current;
        if (data == null) {
            throw new IllegalStateException("Noch keine Spieldaten geladen");
        }
        return championDetail(data.patch(), championId);
    }

    /** Champion-Details für einen bestimmten Patch; im Speicher zwischengespeichert. */
    public ChampionDetail championDetail(String patch, String championId) throws IOException {
        String key = patch + "|" + championId;
        ChampionDetail cached = detailCache.get(key);
        if (cached != null) {
            return cached;
        }
        ChampionDetail detail = ddragon.championDetail(patch, championId);
        detailCache.put(key, detail);
        return detail;
    }

    /** Andere Patches mit vollständigem Cache, neueste zuerst. */
    private List<String> cachedFallbacks(String failedPatch) throws IOException {
        List<String> result = new ArrayList<>(ddragon.cachedPatchesWithData());
        result.remove(failedPatch);
        return result;
    }
}
