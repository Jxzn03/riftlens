package com.leaguetool.update;

import com.leaguetool.util.AppVersion;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseNotesTest {

    private static final List<ReleaseNotes.Release> NOTES = List.of(
            release("2.1.0"), release("2.0.1"), release("2.0.0"), release("1.3.0"));

    private static ReleaseNotes.Release release(String version) {
        return new ReleaseNotes.Release(version, "2026-11-20", "T", List.of(new ReleaseNotes.Section("new", List.of("x"))));
    }

    @Test
    void bundledNotesAreWellFormedAndNewestFirst() {
        List<ReleaseNotes.Release> all = ReleaseNotes.all();
        assertFalse(all.isEmpty());
        Set<String> versions = new HashSet<>();
        Version previous = null;
        for (ReleaseNotes.Release r : all) {
            Version v = Version.parse(r.version()).orElseThrow();
            assertTrue(versions.add(r.version()), "doppelt: " + r.version());
            assertTrue(previous == null || previous.isNewerThan(v), "Reihenfolge bei " + r.version());
            previous = v;
            assertTrue(r.date().matches("\\d{4}-\\d{2}-\\d{2}"), r.version() + ": Datum " + r.date());
            assertFalse(r.title().isBlank(), r.version() + ": Titel");
            assertFalse(r.sections().isEmpty(), r.version() + ": keine Abschnitte");
            for (ReleaseNotes.Section s : r.sections()) {
                assertTrue(Set.of("new", "improved", "fixed").contains(s.kind()), r.version() + ": Art " + s.kind());
                assertFalse(s.items().isEmpty(), r.version() + ": leerer Abschnitt");
            }
        }
    }

    /** Ein Release ohne Versionshinweise fällt schon in {@code clean verify} auf (auch beim Release-Build). */
    @Test
    void currentAppVersionHasNotes() {
        String current = AppVersion.get();
        if (!current.isEmpty()) {
            assertTrue(ReleaseNotes.all().stream().anyMatch(r -> r.version().equals(current)),
                    "release-notes.json enthält keinen Eintrag für " + current);
        }
    }

    @Test
    void parseDropsInvalidVersionsAndSortsNewestFirst() throws Exception {
        String text = "[{\"version\":\"1.0.0\",\"date\":\"2026-01-01\",\"title\":\"a\",\"sections\":[]},"
                + "{\"version\":\"kaputt\",\"date\":\"2026-01-01\",\"title\":\"b\",\"sections\":[]},"
                + "{\"version\":\"1.10.0\",\"date\":\"2026-01-02\",\"title\":\"c\",\"sections\":[]}]";
        List<ReleaseNotes.Release> parsed = ReleaseNotes.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        assertEquals(List.of("1.10.0", "1.0.0"), parsed.stream().map(ReleaseNotes.Release::version).toList());
    }

    @Test
    void afterAnUpdateAllSkippedVersionsAreUnseen() {
        assertEquals(List.of("2.1.0", "2.0.1"), ReleaseNotes.unseen(NOTES, "2.0.0", "2.1.0", true));
        assertEquals(List.of("2.0.1"), ReleaseNotes.unseen(NOTES, "2.0.0", "2.0.1", true));
    }

    @Test
    void sameVersionOrDowngradeShowsNothing() {
        assertEquals(List.of(), ReleaseNotes.unseen(NOTES, "2.1.0", "2.1.0", true));
        assertEquals(List.of(), ReleaseNotes.unseen(NOTES, "2.1.0", "2.0.1", true));
    }

    @Test
    void withoutStoredVersionOnlyExistingUsersSeeTheCurrentVersion() {
        assertEquals(List.of("2.1.0"), ReleaseNotes.unseen(NOTES, "", "2.1.0", true));
        assertEquals(List.of(), ReleaseNotes.unseen(NOTES, "", "2.1.0", false));
        assertEquals(List.of(), ReleaseNotes.unseen(NOTES, "", "3.0.0", true)); // Version ohne Hinweise
    }

    @Test
    void unknownAppVersionShowsNothing() {
        assertEquals(List.of(), ReleaseNotes.unseen(NOTES, "2.0.0", "", true));
    }
}
