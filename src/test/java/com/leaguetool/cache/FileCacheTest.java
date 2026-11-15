package com.leaguetool.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileCacheTest {

    @TempDir
    Path tmp;

    @Test
    void writesAndReadsPerPatch() throws IOException {
        FileCache cache = new FileCache(tmp);
        cache.writeText("16.22.1", "data/en_US/item.json", "{}");
        assertEquals("{}", cache.readText("16.22.1", "data/en_US/item.json").orElseThrow());
        assertTrue(cache.readText("16.21.1", "data/en_US/item.json").isEmpty());
        assertTrue(Files.isRegularFile(tmp.resolve("16.22.1/data/en_US/item.json")));
    }

    @Test
    void entriesFromBeforeAPatchChangeAreStaleButRemainFallback() throws IOException {
        FileCache cache = new FileCache(tmp);
        cache.writeText(FileCache.GLOBAL, "wiki/x.json", "alt");
        Files.setLastModifiedTime(cache.pathFor(FileCache.GLOBAL, "wiki/x.json"),
                java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(3600)));
        java.time.Instant patchChange = java.time.Instant.now().minusSeconds(60);

        assertTrue(cache.isFresh(FileCache.GLOBAL, "wiki/x.json", Duration.ofDays(3)));
        assertFalse(cache.isFresh(FileCache.GLOBAL, "wiki/x.json", Duration.ofDays(3), patchChange));

        FileCache.CacheResult offline = cache.getFreshOrStale(FileCache.GLOBAL, "wiki/x.json", Duration.ofDays(3), patchChange,
                () -> { throw new ConnectException("offline"); });
        assertEquals("alt", offline.content());
        assertTrue(offline.stale(), "ohne Netz bleibt der alte Eintrag nutzbar");

        FileCache.CacheResult online = cache.getFreshOrStale(FileCache.GLOBAL, "wiki/x.json", Duration.ofDays(3), patchChange,
                () -> "neu");
        assertEquals("neu", online.content());
        assertTrue(cache.isFresh(FileCache.GLOBAL, "wiki/x.json", Duration.ofDays(3), patchChange));
    }

    @Test
    void getOrFetchCallsFetcherOnlyOnce() throws IOException {
        FileCache cache = new FileCache(tmp);
        int[] calls = {0};
        FileCache.IoSupplier<String> fetcher = () -> {
            calls[0]++;
            return "value";
        };
        assertEquals("value", cache.getOrFetch("16.22.1", "a.json", fetcher));
        assertEquals("value", cache.getOrFetch("16.22.1", "a.json", fetcher));
        assertEquals(1, calls[0]);
    }

    @Test
    void freshOrStaleFallsBackToStaleEntry() throws IOException {
        FileCache cache = new FileCache(tmp);
        cache.writeText(FileCache.GLOBAL, "v.json", "old");
        // frisch → kein Abruf
        FileCache.CacheResult fresh = cache.getFreshOrStale(FileCache.GLOBAL, "v.json", Duration.ofHours(1),
                () -> {
                    throw new AssertionError("darf nicht aufgerufen werden");
                });
        assertEquals("old", fresh.content());
        assertFalse(fresh.stale());

        // veraltet + Netzfehler → alter Eintrag
        FileCache.CacheResult stale = cache.getFreshOrStale(FileCache.GLOBAL, "v.json", Duration.ZERO,
                () -> {
                    throw new ConnectException("offline");
                });
        assertEquals("old", stale.content());
        assertTrue(stale.stale());

        // veraltet + Netz ok → neuer Inhalt
        FileCache.CacheResult updated = cache.getFreshOrStale(FileCache.GLOBAL, "v.json", Duration.ZERO, () -> "new");
        assertEquals("new", updated.content());
        assertEquals("new", cache.readText(FileCache.GLOBAL, "v.json").orElseThrow());
    }

    @Test
    void freshOrStaleWithoutEntryRethrows() {
        FileCache cache = new FileCache(tmp);
        assertThrows(ConnectException.class, () -> cache.getFreshOrStale(FileCache.GLOBAL, "missing.json",
                Duration.ofHours(1), () -> {
                    throw new ConnectException("offline");
                }));
    }

    @Test
    void listsCachedPatchesNewestFirst() throws IOException {
        FileCache cache = new FileCache(tmp);
        cache.writeText("16.9.1", "x", "1");
        cache.writeText("16.22.1", "x", "1");
        cache.writeText("16.10.1", "x", "1");
        cache.writeText(FileCache.GLOBAL, "x", "1");
        assertEquals(List.of("16.22.1", "16.10.1", "16.9.1"), cache.cachedPatches());
    }

    @Test
    void rejectsPathTraversal() {
        FileCache cache = new FileCache(tmp);
        assertThrows(IllegalArgumentException.class, () -> cache.pathFor("16.22.1", "../../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> cache.pathFor("..", "x"));
        assertTrue(cache.pathFor("16.22.1", "img/champion/Ahri.png").startsWith(tmp.toAbsolutePath().normalize()));
    }
}
