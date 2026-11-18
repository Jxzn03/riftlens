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
import java.util.List;

import static com.leaguetool.draft.FearlessSeriesTest.draft;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

        assertTrue(store.rename(series.id(), "  Pro: Spiel nachgestellt "));
        assertEquals("Pro: Spiel nachgestellt", store().finished(LIMIT).get(0).label());
        assertTrue(store.rename(series.id(), ""));
        assertNull(store.finished(LIMIT).get(0).label(), "Leere Bezeichnung entfernt sie");
        assertThrows(IllegalArgumentException.class, () -> store.rename(series.id(), "x".repeat(61)));
        assertFalse(store.rename("unbekannt", "x"));

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

    @Test
    void validJsonOfTheWrongShapeIsMovedAsideToo() throws IOException {
        for (String content : java.util.List.of("[]", "\"x\"", "null", "")) {
            Files.deleteIfExists(tmp.resolve("fearless-series.json.bad"));
            Files.writeString(file(), content);
            FearlessStore store = store();
            assertTrue(store.active().isEmpty());
            store.start(Format.BO3, null, LIMIT);
            assertEquals(content, Files.readString(tmp.resolve("fearless-series.json.bad")));
        }
    }

    /** Uhr, die sich von Hand vorstellen lässt. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-16T18:00:00Z");

        void advance(java.time.Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static FearlessSeries finishedSeries(FearlessStore store, String label) throws IOException {
        FearlessSeries series = store.start(Format.OPEN, label, LIMIT);
        store.addGame(draft(0), null, "", LIMIT);
        store.end(LIMIT);
        return series;
    }

    @Test
    void deletedSeriesGoesToTrashAndCanBeRestoredInItsPlace() throws IOException {
        MovableClock clock = new MovableClock();
        FearlessStore store = new FearlessStore(file(), clock);
        FearlessSeries older = finishedSeries(store, "alt");
        clock.advance(java.time.Duration.ofHours(1));
        FearlessSeries newer = finishedSeries(store, "neu");

        assertTrue(store.delete(older.id(), LIMIT));
        assertEquals(1, store.finished(LIMIT).size());
        FearlessStore reloaded = new FearlessStore(file(), clock);
        assertEquals(1, reloaded.trash().size(), "Papierkorb steht in der Datei");
        assertEquals("alt", reloaded.trash().get(0).series().label());
        assertEquals(1, reloaded.trash().get(0).series().games().size(), "Mit allen Spielen");

        assertTrue(store.restore(older.id()));
        assertFalse(store.restore(older.id()));
        assertEquals(java.util.List.of(newer.id(), older.id()), store.finished(LIMIT).stream().map(FearlessSeries::id).toList(),
                "Nach Ende einsortiert");
        assertTrue(store.trash().isEmpty());
    }

    @Test
    void seriesTrashIsPurgedAfterSevenDays() throws IOException {
        MovableClock clock = new MovableClock();
        FearlessStore store = new FearlessStore(file(), clock);
        FearlessSeries series = finishedSeries(store, "weg");
        store.delete(series.id(), LIMIT);

        clock.advance(DraftArchive.TRASH_RETENTION.minusMinutes(1));
        assertEquals(1, new FearlessStore(file(), clock).trash().size());
        clock.advance(java.time.Duration.ofMinutes(1));
        assertTrue(new FearlessStore(file(), clock).trash().isEmpty());
        assertFalse(Files.readString(file()).contains(series.id()), "Auch aus der Datei entfernt");
    }

    @Test
    void seriesTrashCanBePurgedOrEmptiedAndSurvivesNewSeries() throws IOException {
        FearlessStore store = store();
        FearlessSeries a = finishedSeries(store, "a");
        FearlessSeries b = finishedSeries(store, "b");
        store.delete(a.id(), LIMIT);
        store.delete(b.id(), LIMIT);

        finishedSeries(store, "c");
        assertEquals(2, store.trash().size(), "Neue Serien lassen den Papierkorb in Ruhe");
        assertEquals("b", store.trash().get(0).series().label(), "Zuletzt gelöschte zuerst");
        assertTrue(store.purge(b.id()));
        assertFalse(store.purge(b.id()));
        assertEquals(1, store.emptyTrash());
        assertTrue(store().trash().isEmpty());
    }

    @Test
    void trashedSeriesIsNotReopenedByUndo() throws IOException {
        FearlessStore store = store();
        FearlessSeries series = finishedSeries(store, "x");
        store.delete(series.id(), LIMIT);
        assertThrows(IllegalStateException.class, () -> store.undoLastGame(LIMIT), "Nur beendete, nicht gelöschte Serien");
    }

    @Test
    void gamesOfRunningAndFinishedSeriesCanBeReordered() throws IOException {
        FearlessStore store = store();
        FearlessSeries done = store.start(Format.OPEN, "fertig", LIMIT);
        store.addGame(draft(0), Result.WIN, "", LIMIT);
        store.addGame(draft(10), Result.LOSS, "", LIMIT);
        store.end(LIMIT);
        FearlessSeries running = store.start(Format.BO5, "läuft", LIMIT);
        store.addGame(draft(40), null, "", LIMIT);
        store.addGame(draft(50), null, "", LIMIT);

        assertTrue(store.reorder(done.id(), java.util.List.of(2, 1)));
        assertTrue(store.reorder(running.id(), java.util.List.of(2, 1)));
        assertEquals(Result.LOSS, store().finished(LIMIT).get(0).games().get(0).result(), "Gespeichert, Ergebnis wandert mit");
        assertEquals(draft(50), store().active().orElseThrow().games().get(0).draft());
        assertThrows(IllegalArgumentException.class, () -> store.reorder(running.id(), java.util.List.of(1)));
        assertFalse(store.reorder("unbekannt", java.util.List.of(1, 2)));
    }

    @Test
    void shownRolesAreStoredAndSurviveReorder() throws IOException {
        FearlessStore store = store();
        FearlessSeries series = store.start(Format.BO3, null, LIMIT);
        java.util.Map<Integer, com.leaguetool.model.Role> shown = java.util.Map.of(6, com.leaguetool.model.Role.TOP);
        store.addGame(draft(0), null, "", shown, LIMIT);
        store.addGame(draft(10), null, "", LIMIT);

        assertEquals(shown, store().active().orElseThrow().games().get(0).shownRoles(), "Gespeichert");
        assertNull(store().active().orElseThrow().games().get(0).draft().enemy().get(0).forcedRole(), "Keine feste Rolle");
        store.reorder(series.id(), java.util.List.of(2, 1));
        assertEquals(shown, store().active().orElseThrow().games().get(1).shownRoles(), "Wandert mit dem Spiel");
    }

    @Test
    void removedGameLeavesTheSeriesWithoutTrash() throws IOException {
        FearlessStore store = store();
        String id = store.start(Format.BO5, "Scrim", LIMIT).id();
        store.addGame(draft(0), Result.WIN, "", LIMIT);
        store.addGame(draft(10), Result.LOSS, "", LIMIT);
        store.addGame(draft(20), null, "", LIMIT);

        assertEquals(draft(0), store.removeGame(id, 1).draft());
        FearlessSeries rest = store().active().orElseThrow();
        assertEquals(List.of(draft(10), draft(20)), rest.games().stream().map(Game::draft).toList(), "Rücken nach, übersteht einen Neustart");
        assertEquals(List.of(1, 2), rest.games().stream().map(Game::number).toList(), "Neu durchnummeriert");
        assertEquals(Result.LOSS, rest.games().get(0).result(), "Ergebnisse wandern mit");
        assertFalse(rest.locked().containsKey(1), "Sperren des entfernten Spiels fallen weg");
        assertTrue(rest.lockedBefore(1).isEmpty(), "Das neue Spiel 1 hat keine Sperren mehr");
        assertTrue(store().trash().isEmpty(), "Kein Papierkorb");
        assertFalse(Files.readString(file()).contains("trashedGames"));
    }

    @Test
    void removedGameCanBeAddedBackToAFinishedSeriesUntilItIsFull() throws IOException {
        FearlessStore store = store();
        String id = store.start(Format.BO3, null, LIMIT).id();
        store.addGame(draft(0), Result.WIN, "", LIMIT);
        store.addGame(draft(10), Result.LOSS, "", LIMIT);
        store.addGame(draft(20), Result.WIN, "", LIMIT);
        assertTrue(store.active().isEmpty(), "Entschieden und beendet");
        store.removeGame(id, 1);

        Instant played = Instant.parse("2026-11-16T17:00:00Z");
        FearlessSeries back = store.addGameTo(id, draft(0), "16.22.1", java.util.Map.of(), played);
        assertEquals(draft(0), back.games().get(2).draft(), "Hinten angehängt, die Pfeile ordnen es ein");
        assertEquals(played, back.games().get(2).playedAt());
        assertEquals("16.22.1", back.games().get(2).patch());
        assertNull(back.games().get(2).result(), "Ohne Ergebnis");
        assertFalse(back.active(), "Beendete Serie bleibt beendet");
        assertEquals(3, store().finished(LIMIT).get(0).games().size(), "Übersteht einen Neustart");
        assertThrows(IllegalStateException.class,
                () -> store.addGameTo(id, draft(30), "", java.util.Map.of(), played), "Voll mit 3 von 3 Spielen");
        assertNull(store.addGameTo("unbekannt", draft(30), "", java.util.Map.of(), played));
    }

    @Test
    void addedGameChecksLocksAndEndsARunningSeriesWhenFull() throws IOException {
        FearlessStore store = store();
        String id = store.start(Format.BO3, null, LIMIT).id();
        store.addGame(draft(0), null, "", LIMIT);
        store.addGame(draft(10), null, "", LIMIT);
        Instant played = Instant.parse("2026-11-16T17:00:00Z");
        assertThrows(IllegalArgumentException.class,
                () -> store.addGameTo(id, draft(0), "", java.util.Map.of(), played), "Champions schon in der Serie");

        FearlessSeries full = store.addGameTo(id, draft(20), "", java.util.Map.of(), played);
        assertFalse(full.active(), "Drittes Spiel im Best-of-3: voll, also beendet");
        assertTrue(store().active().isEmpty());
    }

    @Test
    void onlyGameOfAFinishedSeriesIsNotRemovedAlone() throws IOException {
        FearlessStore store = store();
        String id = store.start(Format.BO3, null, LIMIT).id();
        store.addGame(draft(0), null, "", LIMIT);
        store.end(LIMIT);
        assertThrows(IllegalStateException.class, () -> store.removeGame(id, 1));
        assertThrows(IllegalArgumentException.class, () -> store.removeGame(id, 2));
        assertNull(store.removeGame("unbekannt", 1));
        assertEquals(1, store.finished(LIMIT).get(0).games().size());
    }

    @Test
    void singleGamesTrashedByOlderVersionsAreDropped() throws IOException {
        FearlessStore store = store();
        String id = store.start(Format.BO3, null, LIMIT).id();
        store.addGame(draft(0), null, "", LIMIT);
        String old = Files.readString(file()).replace("\"trash\" : [ ]", """
                "trash" : [ ], "trashedGames" : [ { "id" : "x", "seriesId" : "%s", "seriesLabel" : null, "format" : "BO3",
                  "deletedAt" : "2026-11-16T17:00:00Z", "game" : { "number" : 2 } } ]""".formatted(id));
        assertTrue(old.contains("trashedGames"), "Testdatei vorbereitet");
        Files.writeString(file(), old);

        FearlessStore reloaded = store();
        assertEquals(1, reloaded.active().orElseThrow().games().size(), "Serie bleibt lesbar");
        assertTrue(reloaded.trash().isEmpty());
        reloaded.addGame(draft(10), null, "", LIMIT);
        assertFalse(Files.readString(file()).contains("trashedGames"), "Beim nächsten Schreiben verworfen");
    }

    @Test
    void rolesOfAGameCanBeOverwrittenLater() throws IOException {
        FearlessStore store = store();
        String id = store.start(Format.BO5, null, LIMIT).id();
        store.addGame(draft(0), Result.WIN, "", java.util.Map.of(1, com.leaguetool.model.Role.JUNGLE), LIMIT);
        store.addGame(draft(10), null, "", LIMIT);

        java.util.List<com.leaguetool.draft.RolePredictor.Slot> ally = new java.util.ArrayList<>(draft(0).ally());
        ally.set(0, new com.leaguetool.draft.RolePredictor.Slot(1, com.leaguetool.model.Role.TOP));
        assertEquals(ally, store.updateGameRoles(id, 1, ally, draft(0).enemy()).draft().ally());

        Game game = store().active().orElseThrow().games().get(0);
        assertEquals(com.leaguetool.model.Role.TOP, game.draft().ally().get(0).forcedRole());
        assertEquals(Result.WIN, game.result(), "Ergebnis bleibt");
        assertTrue(game.shownRoles().isEmpty(), "Angezeigte Auto-Rolle fällt für den nun festen Champion weg");
        assertEquals(20, store().active().orElseThrow().locked().size(), "Sperren unverändert");
        assertThrows(IllegalArgumentException.class, () -> store.updateGameRoles(id, 1, draft(10).ally(), draft(0).enemy()));
        assertThrows(IllegalArgumentException.class, () -> store.updateGameRoles(id, 3, ally, draft(0).enemy()));
        assertNull(store.updateGameRoles("unbekannt", 1, ally, draft(0).enemy()));
    }

    @Test
    void rolesOfAnArchivedDraftReachTheSameSeriesGame() throws IOException {
        FearlessStore store = store();
        store.start(Format.BO5, null, LIMIT);
        store.addGame(draft(0), null, "", LIMIT);
        store.addGame(draft(10), null, "", LIMIT);

        // Der Archiv-Draft hat die Seiten vertauscht: dort ist 6..10 das eigene Team.
        java.util.List<com.leaguetool.draft.RolePredictor.Slot> archivedAlly = new java.util.ArrayList<>(draft(0).enemy());
        archivedAlly.set(0, new com.leaguetool.draft.RolePredictor.Slot(6, com.leaguetool.model.Role.TOP));
        DraftArchive.Draft archived = new DraftArchive.Draft(archivedAlly, draft(0).ally(), java.util.List.of());
        assertEquals(1, store.updateRolesMatching(archived));
        Game game = store().active().orElseThrow().games().get(0);
        assertEquals(com.leaguetool.model.Role.TOP, game.draft().enemy().get(0).forcedRole(), "Rolle auf der richtigen Seite");
        assertEquals(draft(10), store().active().orElseThrow().games().get(1).draft(), "Andere Spiele unberührt");
        assertEquals(0, store.updateRolesMatching(archived), "Schon gleich: nichts zu schreiben");
    }

    @Test
    void correctedResultReopensAFinishedSeriesThatIsNoLongerDecided() throws IOException {
        FearlessStore store = store();
        String id = store.start(Format.BO3, null, LIMIT).id();
        store.addGame(draft(0), Result.WIN, "", LIMIT);
        store.addGame(draft(10), Result.WIN, "", LIMIT);
        assertTrue(store.active().isEmpty(), "2:0 im Best-of-3: entschieden");

        FearlessStore.ResultChange change = store.updateGameResult(id, 2, Result.LOSS);
        assertTrue(change.reopened());
        FearlessSeries running = store().active().orElseThrow();
        assertEquals(1, running.wins());
        assertEquals(1, running.losses());
        assertTrue(store.finished(LIMIT).isEmpty());
        store.addGame(draft(20), Result.WIN, "", LIMIT);
        assertTrue(store.active().isEmpty(), "Spiel 3 entscheidet");
        assertEquals(3, store.finished(LIMIT).get(0).games().size());

        assertThrows(IllegalArgumentException.class, () -> store.updateGameResult(id, 4, Result.WIN));
        assertNull(store.updateGameResult("unbekannt", 1, Result.WIN));
    }

    @Test
    void correctedResultEndsARunningSeriesOrStaysFinishedWhileAnotherRuns() throws IOException {
        FearlessStore store = store();
        String first = store.start(Format.BO3, null, LIMIT).id();
        store.addGame(draft(0), Result.WIN, "", LIMIT);
        store.addGame(draft(10), Result.LOSS, "", LIMIT);
        FearlessStore.ResultChange decided = store.updateGameResult(first, 2, Result.WIN);
        assertFalse(decided.series().active(), "Jetzt 2:0: entschieden und beendet");
        assertTrue(store.active().isEmpty());

        store.start(Format.BO5, null, LIMIT);
        FearlessStore.ResultChange blocked = store.updateGameResult(first, 2, null);
        assertTrue(blocked.blocked(), "Andere Serie läuft schon");
        assertFalse(blocked.reopened());
        assertNull(store().finished(LIMIT).get(0).games().get(1).result());
        assertNotEquals(first, store.active().orElseThrow().id());
    }

    @Test
    void chosenNumberOfGamesSurvivesARestartAndEndsTheSeries() throws IOException {
        FearlessStore store = store();
        store.start(Format.OPEN, 2, "Scrim", LIMIT);
        store.addGame(draft(0), null, "", LIMIT);
        assertEquals(2, store().active().orElseThrow().maxGames());
        FearlessSeries done = store.addGame(draft(10), null, "", LIMIT);
        assertFalse(done.active(), "Nach der gewählten Spielzahl endet die Serie");
        assertEquals(2, store().finished(LIMIT).get(0).maxGames());
    }

    @Test
    void changingTheNumberOfGamesEndsAFullSeriesAndReopensItWithMoreRoom() throws IOException {
        FearlessStore store = store();
        String id = store.start(Format.OPEN, 5, null, LIMIT).id();
        store.addGame(draft(0), null, "", LIMIT);
        FearlessSeries more = store.setMaxGames(id, 3).series();
        assertTrue(more.active());
        assertEquals(3, store().active().orElseThrow().maxGames(), "Gespeichert");

        FearlessSeries done = store.setMaxGames(id, 1).series();
        assertFalse(done.active(), "Mit 1 von 1 Spiel endet die Serie");
        assertTrue(store.active().isEmpty());
        assertEquals(id, store.finished(LIMIT).get(0).id());

        FearlessStore.ResultChange reopened = store.setMaxGames(id, 4);
        assertTrue(reopened.reopened(), "Die volle Serie läuft mit mehr Platz wieder");
        assertEquals(id, store().active().orElseThrow().id());
        assertEquals(4, store.active().orElseThrow().maxGames());
        assertTrue(store.finished(LIMIT).isEmpty());
        store.addGame(draft(10), null, "", LIMIT);
        assertEquals(2, store.active().orElseThrow().games().size(), "Weitere Spiele wieder über „Spiel abschließen“");
        assertNull(store.setMaxGames("unbekannt", 4));
    }

    @Test
    void manuallyEndedOrBlockedSeriesStaysEndedWhenTheNumberOfGamesGrows() throws IOException {
        FearlessStore store = store();
        String early = store.start(Format.OPEN, 4, "vorzeitig", LIMIT).id();
        store.addGame(draft(0), null, "", LIMIT);
        store.end(LIMIT);
        FearlessStore.ResultChange change = store.setMaxGames(early, 6);
        assertFalse(change.reopened() || change.blocked(), "Von Hand beendet, nicht voll: bleibt beendet");
        assertTrue(store.active().isEmpty());

        String full = store.start(Format.OPEN, 1, "voll", LIMIT).id();
        store.addGame(draft(20), null, "", LIMIT);
        store.start(Format.BO3, "läuft", LIMIT);
        FearlessStore.ResultChange blocked = store.setMaxGames(full, 3);
        assertTrue(blocked.blocked(), "Eine andere Serie läuft schon");
        assertFalse(blocked.series().active());
        assertEquals(3, store().series(full).orElseThrow().maxGames(), "Die Zahl ist trotzdem gespeichert");
    }

    @Test
    void gamesOfOlderFilesAreHandedOutOnceForTheDraftArchive() throws IOException {
        Files.writeString(file(), """
                {"version":1,"active":{"id":"a","label":null,"format":"BO3","startedAt":"2026-11-16T18:00:00Z","endedAt":null,
                 "games":[{"number":1,"playedAt":"2026-11-16T18:40:00Z","patch":"","result":null,
                 "ally":[{"champion":1},{"champion":2},{"champion":3},{"champion":4},{"champion":5}],
                 "enemy":[{"champion":6},{"champion":7},{"champion":8},{"champion":9},{"champion":10}],"bans":[]}]},
                 "finished":[]}""");
        FearlessStore store = store();
        assertEquals(1, store.takeArchiveBackfill().size());
        assertTrue(store.takeArchiveBackfill().isEmpty(), "Nur einmal");
        assertTrue(store().takeArchiveBackfill().isEmpty(), "Die Datei ist jetzt auf dem neuen Stand");
        assertTrue(Files.readString(file()).contains("\"version\" : 2"));

        Files.delete(file());
        FearlessStore fresh = store();
        fresh.start(Format.BO3, null, LIMIT);
        assertTrue(store().takeArchiveBackfill().isEmpty(), "Neue Dateien brauchen nichts nachzutragen");
    }

    @Test
    void runningSeriesCanBeDeleted() throws IOException {
        FearlessStore store = store();
        String empty = store.start(Format.OPEN, "verklickt", LIMIT).id();
        assertTrue(store.delete(empty, LIMIT));
        assertTrue(store().active().isEmpty());
        assertTrue(store.trash().isEmpty(), "Ohne Spiel verworfen, nicht im Papierkorb");
        assertTrue(store.finished(LIMIT).isEmpty());

        String played = store.start(Format.BO3, "mit Spiel", LIMIT).id();
        store.addGame(draft(0), null, "", LIMIT);
        assertTrue(store.delete(played, LIMIT));
        assertTrue(store.active().isEmpty());
        FearlessSeries trashed = store().trash().get(0).series();
        assertFalse(trashed.active(), "Im Papierkorb beendet");
        assertTrue(store.restore(played));
        assertEquals(played, store().finished(LIMIT).get(0).id(), "Kommt als beendete Serie zurück");
    }

    @Test
    void gameDraftIsReplacedAndSurvivesARestart() throws IOException {
        FearlessStore store = store();
        String id = store.start(Format.OPEN, 4, "Scrim", LIMIT).id();
        store.addGame(draft(0), Result.WIN, "", LIMIT);
        store.addGame(draft(10), null, "", LIMIT);

        Game before = store.replaceGameDraft(id, 1, FearlessSeriesTest.replaced(draft(0), 1, 31), java.util.Map.of());
        assertEquals(draft(0), before.draft(), "Liefert das Spiel vor der Änderung");
        FearlessSeries reloaded = store().active().orElseThrow();
        assertEquals(31, reloaded.games().get(0).draft().ally().get(0).champion());
        assertEquals(Result.WIN, reloaded.games().get(0).result());
        assertTrue(reloaded.active(), "Die Serie läuft weiter, es ist kein neues Spiel");
        assertEquals(3, reloaded.nextGame());
        assertNull(store.replaceGameDraft("unbekannt", 1, draft(40), java.util.Map.of()));
    }}
