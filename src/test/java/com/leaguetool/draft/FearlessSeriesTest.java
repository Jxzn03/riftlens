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

    @Test
    void gamesCanBeReorderedWithResultsAndLocksFollowing() {
        FearlessSeries series = series(Format.BO5)
                .withGame(draft(0), Result.WIN, "", NOW)
                .withGame(draft(10), Result.LOSS, "", NOW)
                .withGame(draft(20), null, "", NOW);

        FearlessSeries swapped = series.withOrder(List.of(2, 1, 3));
        assertEquals(List.of(1, 2, 3), swapped.games().stream().map(FearlessSeries.Game::number).toList(), "Neu durchnummeriert");
        assertEquals(draft(10), swapped.games().get(0).draft());
        assertEquals(Result.LOSS, swapped.games().get(0).result(), "Ergebnis bleibt beim Spiel");
        assertEquals(1, swapped.locked().get(11), "Champions aus dem früheren Spiel 2 gelten jetzt als Spiel 1");
        assertEquals(10, swapped.lockedBefore(2).size());
        assertTrue(swapped.lockedBefore(2).containsKey(11) && !swapped.lockedBefore(2).containsKey(1));
        assertEquals(series.locked().keySet(), swapped.locked().keySet(), "Gesamtmenge der Sperren unverändert");

        assertThrows(IllegalArgumentException.class, () -> series.withOrder(List.of(1, 2)));
        assertThrows(IllegalArgumentException.class, () -> series.withOrder(List.of(1, 1, 2)));
        assertThrows(IllegalArgumentException.class, () -> series.withOrder(List.of(1, 2, 4)));
    }

    @Test
    void openSeriesCanHaveAChosenNumberOfGames() {
        FearlessSeries open = new FearlessSeries("id", null, Format.OPEN, 2, NOW, null, List.of());
        assertEquals(2, open.maxGames());
        FearlessSeries one = open.withGame(draft(0), null, "", NOW);
        assertFalse(one.decided());
        FearlessSeries two = one.withGame(draft(10), Result.WIN, "", NOW);
        assertTrue(two.decided(), "Nach der gewählten Spielzahl ist die Serie entschieden");
        assertTrue(two.full());
        assertThrows(IllegalStateException.class, () -> two.withAddedGame(draft(20), "", NOW, Map.of()));
        assertEquals(2, two.withLabel("x").endedAt(NOW).withoutGame(1).maxGames(), "Die Spielzahl bleibt bei Änderungen");

        assertEquals(FearlessSeries.MAX_GAMES, new FearlessSeries("id", null, Format.OPEN, 0, NOW, null, List.of()).maxGames());
        assertEquals(FearlessSeries.MAX_GAMES, new FearlessSeries("id", null, Format.OPEN, 11, NOW, null, List.of()).maxGames());
        assertEquals(3, new FearlessSeries("id", null, Format.BO3, 7, NOW, null, List.of()).maxGames(), "Bo3/Bo5 nach Format");
        assertEquals(2, new FearlessSeries("id", null, Format.OPEN, 1, NOW, null, two.games()).maxGames(),
                "Nie weniger als gespielte Spiele");
    }

    @Test
    void numberOfGamesOfAnOpenSeriesCanBeChanged() {
        FearlessSeries open = series(Format.OPEN).withGame(draft(0), null, "", NOW).withGame(draft(10), null, "", NOW);
        assertEquals(4, open.withMaxGames(4).maxGames());
        assertTrue(open.withMaxGames(2).decided(), "So viele wie gespielt: alle Spiele gespielt");
        assertThrows(IllegalArgumentException.class, () -> open.withMaxGames(1), "Nicht unter gespielten Spielen");
        assertThrows(IllegalArgumentException.class, () -> open.withMaxGames(11));
        assertThrows(IllegalArgumentException.class, () -> series(Format.BO3).withMaxGames(2), "Bo3/Bo5 nach Format");
    }

    /** {@code draft} mit Champion {@code to} statt {@code from}. */
    static Draft replaced(Draft draft, int from, int to) {
        java.util.function.UnaryOperator<List<Slot>> swap = team -> team.stream()
                .map(s -> s.champion() == from ? new Slot(to, s.forcedRole()) : s).toList();
        return new Draft(swap.apply(draft.ally()), swap.apply(draft.enemy()), draft.bans());
    }

    @Test
    void gameDraftCanBeReplacedButNotWithChampionsFromOtherGames() {
        FearlessSeries series = series(Format.OPEN).withGame(draft(0), null, "", NOW)
                .withGame(draft(10), Result.LOSS, "16.23.1", NOW).withGame(draft(20), null, "", NOW);
        Draft changed = replaced(draft(10, 99), 11, 31);
        FearlessSeries updated = series.withGameDraft(2, changed, Map.of());

        FearlessSeries.Game game = updated.games().get(1);
        assertEquals(changed, game.draft(), "Champions und Bans ersetzt");
        assertEquals(Result.LOSS, game.result(), "Ergebnis bleibt");
        assertEquals("16.23.1", game.patch());
        assertEquals(2, updated.lockedBefore(3).get(31), "Neuer Champion sperrt ab Spiel 3");
        assertFalse(updated.lockedBefore(3).containsKey(11), "Der ersetzte ist wieder frei");
        assertEquals(3, updated.games().size());

        assertThrows(IllegalArgumentException.class, () -> series.withGameDraft(2, replaced(draft(10), 11, 1), Map.of()),
                "Champion aus Spiel 1");
        assertThrows(IllegalArgumentException.class, () -> series.withGameDraft(2, replaced(draft(10), 11, 21), Map.of()),
                "Champion aus dem späteren Spiel 3");
        assertThrows(IllegalArgumentException.class, () -> series.withGameDraft(4, draft(40), Map.of()));
        Draft partial = new Draft(draft(40).ally(), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> series.withGameDraft(2, partial, Map.of()));
    }}
