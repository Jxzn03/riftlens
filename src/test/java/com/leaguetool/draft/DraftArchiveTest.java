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
}
