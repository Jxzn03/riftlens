package com.leaguetool.draft;

import com.leaguetool.draft.DraftArchive.Draft;
import com.leaguetool.draft.FearlessSeries.Format;
import com.leaguetool.draft.FearlessSeries.Result;
import com.leaguetool.draft.RolePredictor.Slot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FearlessSeriesTest {

    private static final Instant NOW = Instant.parse("2026-11-16T18:00:00Z");

    /** Champions {@code base+1 … base+10}, die ersten fünf für dein Team; Bans {@code ban…}. */
    static Draft draft(int base, Integer... bans) {
        List<Slot> ally = IntStream.rangeClosed(1, 5).mapToObj(i -> new Slot(base + i, null)).toList();
        List<Slot> enemy = IntStream.rangeClosed(6, 10).mapToObj(i -> new Slot(base + i, null)).toList();
        return new Draft(ally, enemy, List.of(bans));
    }

    private static FearlessSeries series(Format format) {
        return new FearlessSeries("id", null, format, NOW, null, List.of());
    }

    @Test
    void picksOfBothTeamsAccumulateButBansDoNot() {
        FearlessSeries series = series(Format.BO5)
                .withGame(draft(0, 500, 501), Result.WIN, "16.22.1", NOW)
                .withGame(draft(10, 502), null, "16.22.1", NOW);

        Map<Integer, Integer> locked = series.locked();
        assertEquals(20, locked.size());
        assertEquals(1, locked.get(1), "Eigene Picks aus Spiel 1");
        assertEquals(1, locked.get(10), "Gegner-Picks aus Spiel 1 sperren ebenso (Hard Fearless)");
        assertEquals(2, locked.get(20));
        assertFalse(locked.containsKey(500), "Bans werden nicht übertragen");
        assertEquals(3, series.nextGame());
        assertEquals(10, series.lockedBefore(2).size(), "Vor Spiel 2 galten nur die Picks aus Spiel 1");
        assertTrue(series.lockedBefore(1).isEmpty());
    }

    @Test
    void lockedChampionCannotBePickedAgainEvenBySideOrBan() {
        FearlessSeries series = series(Format.BO3).withGame(draft(0, 50), null, "", NOW);
        assertThrows(IllegalArgumentException.class, () -> series.withGame(draft(5), null, "", NOW),
                "Champions 6–10 waren im ersten Spiel beim Gegner");
        // Ein in Spiel 1 nur gebannter Champion ist wieder frei.
        series.withGame(draft(45), null, "", NOW);
    }

    @Test
    void incompleteDraftIsNoGame() {
        Draft partial = new Draft(List.of(new Slot(1, null)), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> series(Format.OPEN).withGame(partial, null, "", NOW));
    }

    @Test
    void bestOfThreeIsDecidedByTwoWinsOrThreeGames() {
        FearlessSeries twoWins = series(Format.BO3).withGame(draft(0), Result.WIN, "", NOW).withGame(draft(10), Result.WIN, "", NOW);
        assertTrue(twoWins.decided());
        assertThrows(IllegalStateException.class, () -> twoWins.withGame(draft(20), null, "", NOW));

        FearlessSeries split = series(Format.BO3).withGame(draft(0), Result.WIN, "", NOW).withGame(draft(10), Result.LOSS, "", NOW);
        assertFalse(split.decided());
        assertTrue(split.withGame(draft(20), null, "", NOW).decided(), "Nach Spiel 3 ist Schluss, auch ohne Ergebnis");
    }

    @Test
    void bestOfFiveNeedsThreeWinsAndOpenOnlyEndsAtTheCap() {
        FearlessSeries bo5 = series(Format.BO5);
        for (int i = 0; i < 2; i++) {
            bo5 = bo5.withGame(draft(i * 10), Result.LOSS, "", NOW);
        }
        assertFalse(bo5.decided());
        assertTrue(bo5.withGame(draft(20), Result.LOSS, "", NOW).decided());

        FearlessSeries open = series(Format.OPEN);
        for (int i = 0; i < FearlessSeries.MAX_GAMES - 1; i++) {
            open = open.withGame(draft(i * 10), Result.WIN, "", NOW);
        }
        assertFalse(open.decided(), "Offen endet nicht durch Siege");
        assertTrue(open.withGame(draft(900), null, "", NOW).decided());
    }

    @Test
    void undoReopensTheSeries() {
        FearlessSeries ended = series(Format.BO3).withGame(draft(0), Result.WIN, "", NOW).endedAt(NOW);
        FearlessSeries reopened = ended.withoutLastGame();
        assertTrue(reopened.active());
        assertTrue(reopened.games().isEmpty());
        assertThrows(IllegalStateException.class, reopened::withoutLastGame);
    }

    @Test
    void labelIsCleanedAndLimited() {
        assertEquals("Scrim Dienstag", FearlessSeries.cleanLabel("  Scrim\n\tDienstag  "));
        assertNull(FearlessSeries.cleanLabel("   "));
        assertNull(FearlessSeries.cleanLabel(null));
        assertEquals("a".repeat(60), FearlessSeries.cleanLabel("a".repeat(60)));
        assertThrows(IllegalArgumentException.class, () -> FearlessSeries.cleanLabel("a".repeat(61)));
        assertEquals("Scrim", series(Format.BO3).withLabel(" Scrim ").label());
    }
}
