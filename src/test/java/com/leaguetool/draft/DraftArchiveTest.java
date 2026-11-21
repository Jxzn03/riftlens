package com.leaguetool.draft;

import com.leaguetool.draft.DraftArchive.Draft;
import com.leaguetool.draft.DraftArchive.Outcome;
import com.leaguetool.draft.RolePredictor.Slot;
import com.leaguetool.model.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DraftArchiveTest {

    private static final int LIMIT = 10;

    @TempDir
    Path tmp;

    private Path file() {
        return tmp.resolve("draft-archive.json");
    }

    private DraftArchive archive() {
        return new DraftArchive(file(), Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC));
    }

    /** Die ersten fünf Champions sind das eigene Team, die übrigen fünf der Gegner. */
    private static Draft draft(int... champions) {
        List<Slot> ally = IntStream.range(0, 5).mapToObj(i -> new Slot(champions[i], null)).toList();
        List<Slot> enemy = IntStream.range(5, 10).mapToObj(i -> new Slot(champions[i], null)).toList();
        return new Draft(ally, enemy, List.of());
    }

    private static Draft numbered(int base) {
        return draft(base + 1, base + 2, base + 3, base + 4, base + 5, base + 6, base + 7, base + 8, base + 9, base + 10);
    }

    @Test
    void completeDraftIsStoredLocallyWithRolesAndBans() throws IOException {
        Draft draft = new Draft(
                List.of(new Slot(1, Role.TOP), new Slot(2, null), new Slot(3, Role.MIDDLE), new Slot(4, null), new Slot(5, null)),
                List.of(new Slot(6, null), new Slot(7, null), new Slot(8, null), new Slot(9, null), new Slot(10, null)),
                List.of(11, 12));

        Outcome outcome = archive().record(draft, "16.22.1", LIMIT);

        assertTrue(outcome.saved());
        List<DraftArchive.Entry> reloaded = archive().entries(LIMIT);
        assertEquals(1, reloaded.size());
        assertEquals(draft, reloaded.get(0).draft(), "Rollen, Reihenfolge und Bans bleiben erhalten");
        assertEquals("16.22.1", reloaded.get(0).patch());
        assertEquals(Instant.parse("2026-11-14T12:00:00Z"), reloaded.get(0).savedAt());
        String stored = Files.readString(file());
        assertFalse(stored.contains("name") || stored.contains("puuid"), "Nur Champion-Keys, Rollen und Bans");
    }

    @Test
    void incompleteDraftIsRejected() {
        Draft partial = new Draft(List.of(new Slot(1, null)), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> archive().record(partial, "16.22.1", LIMIT));
        assertFalse(Files.exists(file()));
    }

    @Test
    void variantsWithFewerThanThreeChangesAreNotStored() throws IOException {
        DraftArchive archive = archive();
        Outcome original = archive.record(numbered(0), "16.22.1", LIMIT);

        Outcome oneChange = archive.record(draft(99, 2, 3, 4, 5, 6, 7, 8, 9, 10), "16.22.1", LIMIT);
        Outcome twoChanges = archive.record(draft(99, 98, 3, 4, 5, 6, 7, 8, 9, 10), "16.22.1", LIMIT);

        assertFalse(oneChange.saved());
        assertEquals(1, oneChange.changes());
        assertEquals(original.entry().id(), oneChange.entry().id(), "Antwort nennt den ähnlichen Eintrag");
        assertFalse(twoChanges.saved());
        assertEquals(2, twoChanges.changes());
        assertEquals(1, archive.entries(LIMIT).size());
        assertEquals(numbered(0), archive.entries(LIMIT).get(0).draft(), "Das Original bleibt unverändert");
    }

    @Test
    void threeChangesMakeANewDraft() throws IOException {
        DraftArchive archive = archive();
        archive.record(numbered(0), "16.22.1", LIMIT);

        Outcome changed = archive.record(draft(99, 98, 3, 4, 5, 6, 7, 8, 9, 97), "16.22.1", LIMIT);

        assertTrue(changed.saved());
        assertEquals(2, archive.entries(LIMIT).size());
        assertEquals(changed.entry().id(), archive.entries(LIMIT).get(0).id(), "Der neueste steht vorn");
    }

    @Test
    void rolesOrderAndSwappedSidesDoNotCountAsChanges() {
        Draft original = numbered(0);
        Draft reordered = new Draft(
                List.of(new Slot(5, Role.TOP), new Slot(4, null), new Slot(3, null), new Slot(2, null), new Slot(1, Role.UTILITY)),
                original.enemy(), List.of(42));
        Draft swapped = new Draft(original.enemy(), original.ally(), List.of());

        assertEquals(0, DraftArchive.changes(original, reordered));
        assertEquals(0, DraftArchive.changes(original, swapped));
        assertEquals(2, DraftArchive.changes(original, draft(6, 2, 3, 4, 5, 1, 7, 8, 9, 10)),
                "Ein Champion von Seite zu Seite getauscht sind zwei Änderungen");
    }

    @Test
    void oldestDraftsFallOutBeyondTheLimit() throws IOException {
        DraftArchive archive = archive();
        List<Draft> drafts = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            drafts.add(numbered(i * 20));
            assertTrue(archive.record(drafts.get(i), "16.22.1", LIMIT).saved());
        }

        List<DraftArchive.Entry> entries = archive().entries(100);

        assertEquals(LIMIT, entries.size());
        assertEquals(drafts.get(11), entries.get(0).draft());
        assertEquals(drafts.get(2), entries.get(LIMIT - 1).draft());
    }

    @Test
    void smallerLimitHidesOlderDraftsAndComparesOnlyWithShownOnes() throws IOException {
        DraftArchive archive = archive();
        archive.record(numbered(0), "16.22.1", 25);
        for (int i = 1; i <= 10; i++) {
            archive.record(numbered(i * 20), "16.22.1", 25);
        }
        assertEquals(10, archive.entries(10).size());
        assertEquals(11, archive.entries(25).size(), "Bis zum nächsten neuen Draft bleibt der älteste gespeichert");

        Outcome again = archive.record(draft(1, 2, 3, 4, 5, 6, 7, 8, 9, 99), "16.22.1", 10);

        assertTrue(again.saved(), "Der ausgeblendete Draft verhindert keinen neuen");
        assertEquals(10, archive().entries(100).size(), "Mit dem neuen Draft fallen die ausgeblendeten weg");
    }

    @Test
    void deleteRemovesOneEntry() throws IOException {
        DraftArchive archive = archive();
        String first = archive.record(numbered(0), "16.22.1", LIMIT).entry().id();
        archive.record(numbered(20), "16.22.1", LIMIT);

        assertTrue(archive.delete(first));
        assertFalse(archive.delete(first));
        assertEquals(List.of(numbered(20)), archive().entries(LIMIT).stream().map(DraftArchive.Entry::draft).toList());
    }

    @Test
    void unreadableFileIsKeptAsideInsteadOfOverwritten() throws IOException {
        Files.writeString(file(), "{ kaputt");
        DraftArchive archive = archive();

        assertTrue(archive.entries(LIMIT).isEmpty());
        archive.record(numbered(0), "16.22.1", LIMIT);

        assertEquals("{ kaputt", Files.readString(tmp.resolve("draft-archive.json.bad")));
        assertEquals(1, archive().entries(LIMIT).size());
    }

    @Test
    void validJsonOfTheWrongShapeIsKeptAsideToo() throws IOException {
        for (String content : List.of("[]", "\"x\"", "null", "")) {
            Files.deleteIfExists(tmp.resolve("draft-archive.json.bad"));
            Files.writeString(file(), content);
            DraftArchive archive = archive();
            assertTrue(archive.entries(LIMIT).isEmpty());
            archive.record(numbered(0), "16.22.1", LIMIT);
            assertEquals(content, Files.readString(tmp.resolve("draft-archive.json.bad")));
        }
    }

    @Test
    void damagedEntriesAreSkipped() throws IOException {
        Files.writeString(file(), """
                {"version":1,"entries":[
                  {"id":"a","savedAt":"2026-11-14T12:00:00Z","patch":"16.22.1","ally":[{"champion":1,"role":"TOP"}],"enemy":[],"bans":[]},
                  {"id":"b","savedAt":"2026-11-14T12:00:00Z","patch":"16.22.1",
                   "ally":[{"champion":1,"role":null},{"champion":2},{"champion":3},{"champion":4},{"champion":5}],
                   "enemy":[{"champion":6},{"champion":7},{"champion":8},{"champion":9},{"champion":10}],"bans":[11]}
                ]}""");

        List<DraftArchive.Entry> entries = archive().entries(LIMIT);

        assertEquals(1, entries.size());
        assertEquals("b", entries.get(0).id());
        assertNull(entries.get(0).draft().ally().get(0).forcedRole());
    }

    /** Uhr, die sich von Hand vorstellen lässt. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-14T12:00:00Z");

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

    @Test
    void deletedEntryGoesToTrashAndCanBeRestoredInItsPlace() throws IOException {
        MovableClock clock = new MovableClock();
        DraftArchive archive = new DraftArchive(file(), clock);
        String older = archive.record(numbered(0), "16.22.1", LIMIT).entry().id();
        clock.advance(java.time.Duration.ofMinutes(5));
        String newer = archive.record(numbered(20), "16.22.1", LIMIT).entry().id();

        assertTrue(archive.delete(older));
        assertEquals(1, archive.entries(LIMIT).size());
        List<DraftArchive.TrashEntry> trash = new DraftArchive(file(), clock).trash();
        assertEquals(1, trash.size(), "Papierkorb steht in der Datei");
        assertEquals(older, trash.get(0).entry().id());
        assertEquals(clock.instant().plus(DraftArchive.TRASH_RETENTION), trash.get(0).expiresAt());

        assertTrue(archive.restore(older));
        assertFalse(archive.restore(older), "Nur einmal wiederherstellbar");
        assertEquals(List.of(newer, older), archive.entries(LIMIT).stream().map(DraftArchive.Entry::id).toList(),
                "Wieder nach Speicherzeitpunkt einsortiert");
        assertTrue(archive.trash().isEmpty());
    }

    @Test
    void trashIsPurgedAfterSevenDays() throws IOException {
        MovableClock clock = new MovableClock();
        DraftArchive archive = new DraftArchive(file(), clock);
        String id = archive.record(numbered(0), "16.22.1", LIMIT).entry().id();
        archive.delete(id);

        clock.advance(DraftArchive.TRASH_RETENTION.minusMinutes(1));
        assertEquals(1, new DraftArchive(file(), clock).trash().size(), "Kurz vor Ablauf noch da");
        clock.advance(java.time.Duration.ofMinutes(1));
        assertTrue(new DraftArchive(file(), clock).trash().isEmpty(), "Nach 7 Tagen weg");
        assertFalse(Files.readString(file()).contains(id), "Auch aus der Datei entfernt");
        assertFalse(new DraftArchive(file(), clock).restore(id));
    }

    @Test
    void trashCanBePurgedSinglyOrEmptiedAndSurvivesNewDrafts() throws IOException {
        DraftArchive archive = archive();
        String a = archive.record(numbered(0), "", LIMIT).entry().id();
        String b = archive.record(numbered(20), "", LIMIT).entry().id();
        archive.delete(a);
        archive.delete(b);

        archive.record(numbered(40), "", LIMIT);
        assertEquals(2, archive.trash().size(), "Neue Drafts lassen den Papierkorb in Ruhe");
        assertEquals(b, archive.trash().get(0).entry().id(), "Zuletzt gelöschter zuerst");
        assertTrue(archive.purge(b));
        assertFalse(archive.purge(b));
        assertEquals(1, archive.emptyTrash());
        assertTrue(new DraftArchive(file()).trash().isEmpty());
    }

    @Test
    void draftInTrashDoesNotBlockTheSameDraftAsNew() throws IOException {
        DraftArchive archive = archive();
        String id = archive.record(numbered(0), "", LIMIT).entry().id();
        archive.delete(id);
        assertTrue(archive.record(numbered(0), "", LIMIT).saved(), "Der Papierkorb zählt nicht für die Varianten-Regel");
    }

    @Test
    void matchingDraftsMoveToTrashAndBackTogether() throws IOException {
        DraftArchive archive = archive();
        String game = archive.record(numbered(0), "", LIMIT).entry().id();
        String other = archive.record(numbered(20), "", LIMIT).entry().id();
        Draft swapped = draft(6, 7, 8, 9, 10, 1, 2, 3, 4, 5);

        assertEquals(1, archive.deleteMatching(List.of(swapped, numbered(40))), "Auch mit vertauschten Seiten, nur exakt gleiche");
        assertEquals(List.of(other), archive.entries(LIMIT).stream().map(DraftArchive.Entry::id).toList());
        assertEquals(game, archive.trash().get(0).entry().id());
        assertEquals(0, archive.deleteMatching(List.of(numbered(40))));

        assertEquals(1, archive.restoreMatching(List.of(numbered(0))));
        assertEquals(2, archive.entries(LIMIT).size());
        assertTrue(archive.trash().isEmpty());
    }

    @Test
    void rolesOfAStoredDraftCanBeOverwrittenButNotItsChampions() throws IOException {
        DraftArchive archive = archive();
        Draft original = new Draft(numbered(0).ally(), numbered(0).enemy(), List.of(20));
        String id = archive.record(original, "16.22.1", LIMIT).entry().id();

        // Zeitpunkt, Patch und Bans bleiben, die Reihenfolge kommt vom Brett.
        List<Slot> ally = List.of(new Slot(5, Role.UTILITY), new Slot(1, Role.TOP), new Slot(2, null), new Slot(3, null), new Slot(4, null));
        DraftArchive.Entry updated = archive.updateRoles(id, ally, numbered(0).enemy());
        assertEquals(ally, updated.draft().ally(), "Reihenfolge und Rollen wie auf dem Brett");
        assertEquals(numbered(0).enemy(), updated.draft().enemy());
        assertEquals(List.of(20), updated.draft().bans());

        DraftArchive.Entry reloaded = archive().entries(LIMIT).get(0);
        assertEquals(id, reloaded.id());
        assertEquals("16.22.1", reloaded.patch());
        assertEquals(ally, reloaded.draft().ally(), "Übersteht einen Neustart");
        assertEquals(1, archive().entries(LIMIT).size(), "Kein neuer Eintrag");

        List<Slot> otherChampion = List.of(new Slot(99, null), new Slot(2, null), new Slot(3, null), new Slot(4, null), new Slot(5, null));
        assertThrows(IllegalArgumentException.class, () -> archive.updateRoles(id, otherChampion, numbered(0).enemy()));
        assertThrows(IllegalArgumentException.class, () -> archive.updateRoles(id, numbered(0).enemy(), numbered(0).ally()),
                "Vertauschte Seiten sind keine Rollenänderung");
        List<Slot> doubleRole = List.of(new Slot(1, Role.TOP), new Slot(2, Role.TOP), new Slot(3, null), new Slot(4, null), new Slot(5, null));
        assertThrows(IllegalArgumentException.class, () -> archive.updateRoles(id, doubleRole, numbered(0).enemy()));
        assertNull(archive.updateRoles("unbekannt", ally, numbered(0).enemy()));
    }

    @Test
    void rolesOfAChangedSeriesGameReachTheSameDraftEvenWithSwappedSides() throws IOException {
        DraftArchive archive = archive();
        archive.record(numbered(0), "", LIMIT);
        archive.record(numbered(20), "", LIMIT);

        // Das Spiel hat die Seiten vertauscht: dort ist 6..10 das eigene Team.
        List<Slot> gameAlly = List.of(new Slot(6, Role.TOP), new Slot(7, null), new Slot(8, null), new Slot(9, null), new Slot(10, null));
        Draft game = new Draft(gameAlly, numbered(0).ally(), List.of());
        assertEquals(1, archive.updateRolesMatching(game));
        DraftArchive.Entry entry = archive().entries(LIMIT).stream()
                .filter(e -> e.draft().ally().get(0).champion() == 1).findFirst().orElseThrow();
        assertEquals(Role.TOP, entry.draft().enemy().get(0).forcedRole(), "Rolle landet auf der richtigen Seite");
        assertEquals(0, archive.updateRolesMatching(game), "Schon gleich: nichts zu schreiben");
        assertEquals(0, archive.updateRolesMatching(numbered(40)));
    }

    @Test
    void missingSeriesGameIsReconstructedOnlyWhenNoEqualDraftExistsAnywhere() throws IOException {
        DraftArchive archive = archive();
        archive.record(numbered(0), "", LIMIT);
        Instant played = Instant.parse("2026-11-10T20:00:00Z");

        assertNull(archive.reconstruct(numbered(0), played, "16.22.1"), "Gleicher Eintrag vorhanden");
        String id = archive.entries(LIMIT).get(0).id();
        archive.delete(id);
        assertNull(archive.reconstruct(numbered(0), played, "16.22.1"), "Liegt im Papierkorb: dort wiederherstellen");
        archive.purge(id);

        DraftArchive.Entry rebuilt = archive.reconstruct(numbered(0), played, "16.22.1");
        assertEquals(played, rebuilt.savedAt(), "Zeitpunkt des Spiels");
        assertEquals("16.22.1", rebuilt.patch());
        archive.record(numbered(20), "", LIMIT);
        assertEquals(List.of(numbered(20), numbered(0)), archive().entries(LIMIT).stream().map(DraftArchive.Entry::draft).toList(),
                "Nach Zeitpunkt einsortiert");
        assertThrows(IllegalArgumentException.class, () -> archive.reconstruct(new Draft(List.of(), List.of(), List.of()), played, ""));
    }

    @Test
    void shownAutoRolesAreStoredOnlyForOpenChampionsAndFollowEveryChange() throws IOException {
        DraftArchive archive = archive();
        Draft draft = new Draft(
                List.of(new Slot(1, Role.TOP), new Slot(2, null), new Slot(3, null), new Slot(4, null), new Slot(5, null)),
                numbered(0).enemy(), List.of());
        // Für Champion 1 (fest) wird die Auto-Rolle verworfen, 99 steht nicht im Draft.
        java.util.Map<Integer, Role> shown = java.util.Map.of(1, Role.JUNGLE, 2, Role.JUNGLE, 6, Role.TOP, 99, Role.MIDDLE);
        String id = archive.record(draft, "16.22.1", shown, LIMIT).entry().id();

        DraftArchive.Entry reloaded = archive().entries(LIMIT).get(0);
        assertEquals(java.util.Map.of(2, Role.JUNGLE, 6, Role.TOP), reloaded.shownRoles(), "Nur Champions ohne feste Rolle");
        assertEquals(draft, reloaded.draft(), "Gespeicherte Reihenfolge unverändert");
        assertTrue(Files.readString(file()).contains("shownRoles"));

        // Alte Dateien ohne das Feld bleiben lesbar.
        archive.record(numbered(20), "", LIMIT);
        assertTrue(archive().entries(LIMIT).stream().anyMatch(e -> e.shownRoles().isEmpty()));

        // Rollen überschreiben: die jetzt angezeigten Auto-Rollen ersetzen die gespeicherten.
        List<Slot> ally = List.of(new Slot(1, Role.TOP), new Slot(2, Role.JUNGLE), new Slot(3, null), new Slot(4, null), new Slot(5, null));
        DraftArchive.Entry updated = archive.updateRoles(id, ally, draft.enemy(), java.util.Map.of(3, Role.MIDDLE, 2, Role.TOP));
        assertEquals(java.util.Map.of(3, Role.MIDDLE), updated.shownRoles(), "2 ist jetzt fest, seine Auto-Rolle fällt weg");
        assertTrue(archive.updateRoles(id, ally, draft.enemy()).shownRoles().isEmpty(), "Ohne Angabe: keine veralteten Rollen");
    }

    @Test
    void matchingDraftIsReplacedWithItsIdAndDate() throws IOException {
        DraftArchive archive = archive();
        archive.record(numbered(0), "16.22.1", LIMIT);
        archive.record(numbered(20), "", LIMIT);
        String id = archive.entries(LIMIT).stream().filter(e -> e.draft().ally().get(0).champion() == 1).findFirst().orElseThrow().id();

        Draft changed = draft(31, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertEquals(1, archive.replaceMatching(numbered(0), changed, java.util.Map.of()));
        DraftArchive.Entry entry = archive().entries(LIMIT).stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
        assertEquals(changed, entry.draft());
        assertEquals("16.22.1", entry.patch(), "Patch und ID bleiben");
        assertEquals(2, archive().entries(LIMIT).size());
        assertEquals(0, archive.replaceMatching(numbered(0), changed, java.util.Map.of()), "Alter Stand nicht mehr da");
    }

    @Test
    void resultIsSetByHandAndSurvivesRoleChangesAndRestarts() throws IOException {
        DraftArchive archive = archive();
        String id = archive.record(numbered(0), "16.22.1", LIMIT).entry().id();
        assertNull(archive.entries(LIMIT).get(0).result(), "Neu ohne Ergebnis");
        assertFalse(Files.readString(file()).contains("result"), "Ohne Ergebnis kein Feld (alte Dateien bleiben gleich)");

        DraftArchive.Entry won = archive.updateResult(id, FearlessSeries.Result.WIN);
        assertEquals(FearlessSeries.Result.WIN, won.result());
        assertEquals(numbered(0), won.draft(), "Sonst bleibt alles gleich");
        assertEquals(FearlessSeries.Result.WIN, archive().entries(LIMIT).get(0).result(), "Übersteht einen Neustart");

        List<Slot> ally = List.of(new Slot(1, Role.TOP), new Slot(2, null), new Slot(3, null), new Slot(4, null), new Slot(5, null));
        assertEquals(FearlessSeries.Result.WIN, archive.updateRoles(id, ally, numbered(0).enemy()).result(), "Rollen ändern lässt das Ergebnis");

        assertNull(archive.updateResult(id, null).result(), "Zurück auf offen");
        assertNull(archive().entries(LIMIT).get(0).result());
        assertNull(archive.updateResult("unbekannt", FearlessSeries.Result.LOSS));
    }

    @Test
    void replacedDraftWithSwappedSidesFlipsItsResult() throws IOException {
        DraftArchive archive = archive();
        String id = archive.record(numbered(0), "", LIMIT).entry().id();
        archive.updateResult(id, FearlessSeries.Result.WIN);

        // Das Spiel sieht 6..10 als eigenes Team; nach dem Ersetzen gilt dessen Sicht, das Ergebnis dreht sich mit.
        Draft before = draft(6, 7, 8, 9, 10, 1, 2, 3, 4, 5);
        Draft after = draft(6, 7, 8, 9, 10, 31, 2, 3, 4, 5);
        assertEquals(1, archive.replaceMatching(before, after, java.util.Map.of()));
        assertEquals(FearlessSeries.Result.LOSS, archive().entries(LIMIT).get(0).result());
    }
}
