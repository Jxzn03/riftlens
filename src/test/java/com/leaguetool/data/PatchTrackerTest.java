package com.leaguetool.data;

import com.leaguetool.cache.FileCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchTrackerTest {

    @TempDir
    Path dir;

    @Test
    void firstRunRemembersThePatchWithoutDiscardingCaches() {
        PatchTracker tracker = new PatchTracker(new FileCache(dir));
        PatchTracker.Change first = tracker.observe("16.22.1", false);
        assertFalse(first.isNew(), "vorhandene Caches einer bestehenden Installation bleiben gültig");
        assertEquals(Instant.EPOCH, tracker.changedAt());
        assertEquals("16.22.1", new PatchTracker(new FileCache(dir)).patch(), "gespeichert");
    }

    @Test
    void onlyTheFirstStartOfANewerPatchCounts() {
        FileCache cache = new FileCache(dir);
        new PatchTracker(cache).observe("16.22.1", false);

        Instant before = Instant.now();
        PatchTracker tracker = new PatchTracker(cache);
        PatchTracker.Change change = tracker.observe("16.23.1", false);
        assertTrue(change.isNew());
        assertEquals("16.22.1", change.previous());
        assertFalse(tracker.changedAt().isBefore(before));

        PatchTracker nextStart = new PatchTracker(cache);
        assertFalse(nextStart.observe("16.23.1", false).isNew(), "zweiter Start mit demselben Patch: kein neuer Abgleich");
        assertEquals(tracker.changedAt(), nextStart.changedAt(), "die Grenze für veraltete Caches bleibt bestehen");
    }

    @Test
    void offlineFallbacksAndOlderPatchesAreNoChange() {
        FileCache cache = new FileCache(dir);
        new PatchTracker(cache).observe("16.22.1", false);
        PatchTracker tracker = new PatchTracker(cache);
        assertFalse(tracker.observe("16.23.1", true).isNew(), "offline aus dem Cache: nicht als Wechsel werten");
        assertFalse(tracker.observe("16.21.1", false).isNew(), "Rückschritt");
        assertEquals("16.22.1", tracker.patch());
    }
}
