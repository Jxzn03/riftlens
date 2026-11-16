package com.leaguetool.draft;

import com.leaguetool.draft.FearlessSeries.Format;
import com.leaguetool.draft.FearlessSeries.Game;
import com.leaguetool.draft.FearlessSeries.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.leaguetool.draft.FearlessSeriesTest.draft;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FearlessStoreTest {

    private static final int LIMIT = 10;

    @TempDir
    Path tmp;

    private Path file() {
        return tmp.resolve("fearless-series.json");
    }

    private FearlessStore store() {
        return new FearlessStore(file(), Clock.fixed(Instant.parse("2026-11-16T18:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void runningSeriesSurvivesARestartWithLabelGamesAndResults() throws IOException {
        FearlessStore store = store();
        store.start(Format.BO5, "Scrim Dienstag", LIMIT);
        store.addGame(draft(0, 90), Result.WIN, "16.22.1", LIMIT);

        FearlessSeries reloaded = store().active().orElseThrow();
        assertEquals("Scrim Dienstag", reloaded.label());
        assertEquals(Format.BO5, reloaded.format());
        assertEquals(1, reloaded.games().size());
        Game game = reloaded.games().get(0);
        assertEquals(Result.WIN, game.result());
        assertEquals("16.22.1", game.patch());
        assertEquals(draft(0, 90), game.draft());
        assertEquals(10, reloaded.locked().size());
        String stored = Files.readString(file());
        assertFalse(stored.contains("puuid") || stored.contains("gameName"), "Nur Champion-Keys, Rollen, Bans, Bezeichnung");
    }

    @Test
    void onlyOneSeriesRunsAtATime() throws IOException {
        FearlessStore store = store();
        store.start(Format.BO3, null, LIMIT);
        assertThrows(IllegalStateException.class, () -> store.start(Format.BO5, null, LIMIT));
    }

    @Test
    void addingAGameNeedsARunningSeries() {
        assertThrows(IllegalStateException.class, () -> store().addGame(draft(0), null, "", LIMIT));
        assertFalse(Files.exists(file()));
    }

    @Test
    void decidedSeriesEndsAutomaticallyAndUndoReopensIt() throws IOException {
        FearlessStore store = store();
        store.start(Format.BO3, null, LIMIT);
        store.addGame(draft(0), Result.WIN, "", LIMIT);
        FearlessSeries done = store.addGame(draft(10), Result.WIN, "", LIMIT);

        assertFalse(done.active());
        assertTrue(store.active().isEmpty());
        assertEquals(1, store.finished(LIMIT).size());

        Game undone = store.undoLastGame(LIMIT);
        assertEquals(2, undone.number());
        assertEquals(draft(10), undone.draft());
        FearlessSeries reopened = store.active().orElseThrow();
        assertEquals(1, reopened.games().size());
        assertTrue(store.finished(LIMIT).isEmpty(), "Die wieder geöffnete Serie läuft und ist nicht mehr beendet");
    }

    @Test
    void endingWithoutGamesDiscardsTheSeries() throws IOException {
        FearlessStore store = store();
        store.start(Format.OPEN, "leer", LIMIT);
        assertTrue(store.end(LIMIT));
        assertTrue(store.active().isEmpty());
        assertTrue(store.finished(LIMIT).isEmpty());
        assertFalse(store.end(LIMIT), "Keine laufende Serie mehr");
    }

    @Test
    void finishedSeriesCanBeRenamedAndDeleted() throws IOException {
        FearlessStore store = store();
        FearlessSeries series = store.start(Format.OPEN, null, LIMIT);
        store.addGame(draft(0), null, "", LIMIT);
        store.end(LIMIT);

        assertTrue(store.rename(series.id(), "  Pro: Spiel nachgestellt ", LIMIT));
        assertEquals("Pro: Spiel nachgestellt", store().finished(LIMIT).get(0).label());
        assertTrue(store.rename(series.id(), "", LIMIT));
        assertNull(store.finished(LIMIT).get(0).label(), "Leere Bezeichnung entfernt sie");
        assertThrows(IllegalArgumentException.class, () -> store.rename(series.id(), "x".repeat(61), LIMIT));
        assertFalse(store.rename("unbekannt", "x", LIMIT));

        assertTrue(store.delete(series.id(), LIMIT));
        assertFalse(store.delete(series.id(), LIMIT));
        assertTrue(store().finished(LIMIT).isEmpty());
    }

    @Test
    void finishedSeriesAreLimited() throws IOException {
        FearlessStore store = store();
        for (int i = 0; i < 4; i++) {
            store.start(Format.OPEN, "Serie " + i, 3);
            store.addGame(draft(0), null, "", 3);
            store.end(3);
        }
        assertEquals(3, store().finished(10).size());
        assertEquals("Serie 3", store().finished(10).get(0).label(), "Neueste zuerst");
    }

    @Test
    void unreadableFileIsMovedAsideOnNextWrite() throws IOException {
        Files.writeString(file(), "{kaputt");
        FearlessStore store = store();
        assertTrue(store.active().isEmpty());
        store.start(Format.BO3, null, LIMIT);
        assertTrue(Files.exists(tmp.resolve("fearless-series.json.bad")));
        assertTrue(store().active().isPresent());
    }
}
