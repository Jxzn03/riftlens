package com.leaguetool.draft;

import com.leaguetool.draft.DraftAnalyzer.Config;
import com.leaguetool.draft.DraftAnalyzer.Result;
import com.leaguetool.draft.DraftAnalyzer.Risk;
import com.leaguetool.draft.DraftAnalyzer.Suggestion;
import com.leaguetool.draft.DraftDataset.Pick;
import com.leaguetool.draft.DraftDataset.Stat;
import com.leaguetool.model.Role;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DraftAnalyzerTest {

    private static final double AVERAGE = 0.52;
    private static final Config CONFIG = new Config(Risk.MEDIUM, false, 1000);
    private static final int A = 1;
    private static final int B = 2;
    private static final int E = 9;
    private static final Pick A_MID = new Pick(A, Role.MIDDLE);
    private static final Pick B_MID = new Pick(B, Role.MIDDLE);
    private static final Pick B_TOP = new Pick(B, Role.TOP);
    private static final Pick E_MID = new Pick(E, Role.MIDDLE);

    private final Map<Pick, Stat> base = new HashMap<>();
    private final Map<Pick, Map<Pick, Stat>> matchups = new HashMap<>();
    private final Map<Pick, Map<Pick, Stat>> synergies = new HashMap<>();

    private static Stat stat(double winRate, double games) {
        return new Stat(winRate * games, games);
    }

    private DraftDataset dataset() {
        return new DraftDataset(AVERAGE, AVERAGE, base, base, matchups, synergies);
    }

    private DraftAnalyzer analyzer() {
        return new DraftAnalyzer(dataset(), CONFIG);
    }

    @Test
    void ratingAndWinRateAreInverse() {
        assertEquals(0, DraftAnalyzer.rating(0.5), 1e-9);
        assertEquals(0.57, DraftAnalyzer.winRate(DraftAnalyzer.rating(0.57)), 1e-9);
    }

    @Test
    void emptyDraftIsEven() {
        assertEquals(0.5, analyzer().analyze(Map.of(), Map.of()).winRate(), 1e-9);
    }

    @Test
    void championWithAverageWinRateIsWorthNothing() {
        base.put(A_MID, stat(AVERAGE, 50_000));
        assertEquals(0.5, analyzer().analyze(Map.of(Role.MIDDLE, A), Map.of()).winRate(), 1e-9);
    }

    @Test
    void onlyTheSelectedPeriodMovesTheChampionRating() {
        base.put(A_MID, stat(0.55, 1_000_000));
        Map<Pick, Stat> patch = Map.of(A_MID, stat(0.50, 1_000_000));
        DraftDataset data = new DraftDataset(AVERAGE, AVERAGE, patch, base, Map.of(), Map.of());
        Result result = new DraftAnalyzer(data, CONFIG).analyze(Map.of(Role.MIDDLE, A), Map.of());
        assertTrue(result.winRate() < 0.5, "Der Patch-Wert liegt unter dem Durchschnitt");
    }

    @Test
    void smallChampionSamplesAreShrunkEvenWhenBothPeriodsAreTheSame() {
        base.put(A_MID, stat(0.75, 30));
        double winRate = analyzer().analyze(Map.of(Role.MIDDLE, A), Map.of()).winRate();
        assertTrue(winRate < 0.51, "30 Spiele dürfen kaum zählen: " + winRate);
    }

    @Test
    void riskChangesTheChampionRating() {
        base.put(A_MID, stat(0.56, 2_000));
        double cautious = new DraftAnalyzer(dataset(), new Config(Risk.VERY_LOW, false, 0)).analyze(Map.of(Role.MIDDLE, A), Map.of()).winRate();
        double bold = new DraftAnalyzer(dataset(), new Config(Risk.VERY_HIGH, false, 0)).analyze(Map.of(Role.MIDDLE, A), Map.of()).winRate();
        assertTrue(bold > cautious, bold + " > " + cautious);
    }

    @Test
    void largeChampionSamplesKeepTheirWinRate() {
        base.put(A_MID, stat(0.56, 1_000_000));
        double expected = DraftAnalyzer.winRate(DraftAnalyzer.rating(0.56) - DraftAnalyzer.rating(AVERAGE));
        assertEquals(expected, analyzer().analyze(Map.of(Role.MIDDLE, A), Map.of()).winRate(), 1e-3);
    }

    @Test
    void defaultMinGamesFollowsTheSampleSize() {
        assertEquals(4300, DraftAnalyzer.defaultMinGames(57_802_290L));
        assertEquals(1075, DraftAnalyzer.defaultMinGames(57_802_290L / 4));
        assertEquals(100, DraftAnalyzer.defaultMinGames(1_000L), "Untergrenze für sehr kleine Rang-Bereiche");
    }

    @Test
    void championsWithoutGamesAreNeverSuggested() {
        base.put(A_MID, stat(0.40, 5_000));
        List<Pick> picks = new DraftAnalyzer(dataset(), new Config(Risk.MEDIUM, false, 0)).suggestions(Map.of(), Map.of(), Set.of())
                .stream().map(Suggestion::pick).toList();
        assertEquals(List.of(A_MID), picks, "A hat nur auf Mid Spiele");
    }

    @Test
    void pickRateAndLaneShareFollowLolalytics() {
        base.put(A_MID, stat(0.5, 9_000));
        base.put(new Pick(A, Role.TOP), stat(0.5, 1_000));
        base.put(B_MID, stat(0.5, 1_000));
        DraftDataset data = dataset();
        // 10.000 Mid-Picks = 5.000 Spiele; die Pickrates einer Rolle ergeben 200 %.
        assertEquals(1.8, data.pickRate(A_MID), 1e-9);
        assertEquals(0.2, data.pickRate(B_MID), 1e-9);
        assertEquals(0.9, data.laneShare(A_MID), 1e-9);
        assertEquals(1.0, data.laneShare(B_MID), 1e-9);
        assertEquals(0, data.pickRate(E_MID), 1e-9);
    }

    @Test
    void offRolePicksAreOnlySuggestedWhenRarePicksAreAllowed() {
        // Wie Tahm Kench ADC: viele Spiele und hohe Winrate, aber nur 2 % seiner Spiele auf dieser Rolle.
        base.put(B_TOP, stat(0.50, 980_000));
        base.put(B_MID, stat(0.60, 20_000));
        base.put(A_MID, stat(0.53, 100_000));
        for (int champion = 10; champion < 100; champion++) {
            base.put(new Pick(champion, Role.MIDDLE), stat(0.5, 10_000));
        }
        Config common = new Config(Risk.MEDIUM, false, 0, DraftAnalyzer.COMMON_MIN_PICK_RATE, DraftAnalyzer.COMMON_MIN_LANE_SHARE);
        List<Pick> commonPicks = new DraftAnalyzer(dataset(), common).suggestions(Map.of(Role.TOP, E), Map.of(), Set.of())
                .stream().map(Suggestion::pick).toList();
        assertFalse(commonPicks.contains(B_MID), "Off-Role-Pick ausgefiltert");
        assertEquals(A_MID, commonPicks.get(0));

        List<Pick> allPicks = new DraftAnalyzer(dataset(), new Config(Risk.MEDIUM, false, 0))
                .suggestions(Map.of(Role.TOP, E), Map.of(), Set.of()).stream().map(Suggestion::pick).toList();
        assertTrue(allPicks.contains(B_MID), "Bisheriges Verhalten zeigt ihn");
    }

    @Test
    void secondaryRolesNeedTenPercentOfTheChampionsGames() {
        // Wie Malzahar Top: genug Spiele und Pickrate, aber nur 7 % seiner Spiele auf dieser Rolle.
        base.put(B_TOP, stat(0.50, 930_000));
        base.put(B_MID, stat(0.60, 70_000));
        for (int champion = 10; champion < 100; champion++) {
            base.put(new Pick(champion, Role.MIDDLE), stat(0.5, 10_000));
        }
        Config common = new Config(Risk.MEDIUM, false, 0, DraftAnalyzer.COMMON_MIN_PICK_RATE, DraftAnalyzer.COMMON_MIN_LANE_SHARE);
        DraftAnalyzer analyzer = new DraftAnalyzer(dataset(), common);
        assertTrue(dataset().pickRate(B_MID) > DraftAnalyzer.COMMON_MIN_PICK_RATE);
        assertFalse(analyzer.suggestions(Map.of(Role.TOP, E), Map.of(), Set.of()).stream().anyMatch(s -> s.pick().equals(B_MID)));
    }

    @Test
    void autoPickRateRemovesRarelyPlayedChampions() {
        base.put(A_MID, stat(0.53, 100_000));
        base.put(B_MID, stat(0.60, 1_000));
        for (int champion = 10; champion < 100; champion++) {
            base.put(new Pick(champion, Role.MIDDLE), stat(0.5, 10_000));
        }
        Config common = new Config(Risk.MEDIUM, false, 0, DraftAnalyzer.COMMON_MIN_PICK_RATE, DraftAnalyzer.COMMON_MIN_LANE_SHARE);
        List<Pick> picks = new DraftAnalyzer(dataset(), common).suggestions(Map.of(), Map.of(), Set.of())
                .stream().map(Suggestion::pick).toList();
        assertFalse(picks.contains(B_MID), "0,2 % Pickrate liegt unter der Schwelle");
        assertTrue(picks.contains(A_MID));
    }

    @Test
    void expectedMatchupHasNoEffect() {
        base.put(A_MID, stat(0.54, 50_000));
        base.put(E_MID, stat(0.52, 50_000));
        double expected = DraftAnalyzer.winRate(DraftAnalyzer.rating(0.54) - DraftAnalyzer.rating(0.52) + DraftAnalyzer.rating(AVERAGE));
        matchups.put(A_MID, Map.of(E_MID, stat(expected, 5_000)));
        Result result = analyzer().analyze(Map.of(Role.MIDDLE, A), Map.of(Role.MIDDLE, E));
        assertEquals(0, result.matchupRating(), 1e-6);
    }

    @Test
    void matchupFromTheOpponentsDataCountsWithOppositeSign() {
        base.put(A_MID, stat(AVERAGE, 50_000));
        base.put(E_MID, stat(AVERAGE, 50_000));
        matchups.put(E_MID, Map.of(A_MID, stat(0.60, 10_000)));
        Result seen = analyzer().analyze(Map.of(Role.MIDDLE, A), Map.of(Role.MIDDLE, E));
        assertTrue(seen.matchupRating() < -20, "Der Gegner gewinnt diese Paarung deutlich: " + seen.matchupRating());

        matchups.clear();
        matchups.put(A_MID, Map.of(E_MID, stat(0.60, 10_000)));
        Result own = analyzer().analyze(Map.of(Role.MIDDLE, A), Map.of(Role.MIDDLE, E));
        assertEquals(-seen.matchupRating(), own.matchupRating(), 1e-9);
    }

    @Test
    void bothMatchupSourcesAreAveraged() {
        base.put(A_MID, stat(AVERAGE, 50_000));
        base.put(E_MID, stat(AVERAGE, 50_000));
        matchups.put(A_MID, Map.of(E_MID, stat(0.60, 10_000)));
        double single = analyzer().analyze(Map.of(Role.MIDDLE, A), Map.of(Role.MIDDLE, E)).matchupRating();
        matchups.put(E_MID, Map.of(A_MID, stat(AVERAGE, 10_000)));
        double both = analyzer().analyze(Map.of(Role.MIDDLE, A), Map.of(Role.MIDDLE, E)).matchupRating();
        assertEquals(single / 2, both, 1e-9, "Die zweite Quelle sieht keinen Vorsprung und halbiert das Mittel");
    }

    @Test
    void smallSamplesAreShrunk() {
        base.put(A_MID, stat(AVERAGE, 50_000));
        base.put(E_MID, stat(AVERAGE, 50_000));
        matchups.put(A_MID, Map.of(E_MID, stat(0.80, 20)));
        double small = analyzer().analyze(Map.of(Role.MIDDLE, A), Map.of(Role.MIDDLE, E)).matchupRating();
        matchups.put(A_MID, Map.of(E_MID, stat(0.60, 20_000)));
        double large = analyzer().analyze(Map.of(Role.MIDDLE, A), Map.of(Role.MIDDLE, E)).matchupRating();
        assertTrue(small < large, "20 Spiele mit 80 % zählen weniger als 20.000 mit 60 %");
    }

    @Test
    void synergyAddsToTheTeam() {
        base.put(A_MID, stat(AVERAGE, 50_000));
        base.put(B_TOP, stat(AVERAGE, 50_000));
        synergies.put(A_MID, Map.of(B_TOP, stat(0.60, 10_000)));
        Result result = analyzer().analyze(Map.of(Role.MIDDLE, A, Role.TOP, B), Map.of());
        assertTrue(result.duoRating() > 20, "Duo: " + result.duoRating());
        assertTrue(result.winRate() > 0.5);
    }

    @Test
    void botlaneDuoCountsMoreThanAnOffLaneDuoWithTheSameData() {
        Pick bot = new Pick(3, Role.BOTTOM);
        Pick sup = new Pick(4, Role.UTILITY);
        Pick top = new Pick(5, Role.TOP);
        for (Pick p : List.of(A_MID, bot, sup, top)) {
            base.put(p, stat(AVERAGE, 50_000));
        }
        synergies.put(bot, Map.of(sup, stat(0.57, 3_000)));
        synergies.put(A_MID, Map.of(top, stat(0.57, 3_000)));
        double botlane = analyzer().analyze(Map.of(Role.BOTTOM, 3, Role.UTILITY, 4), Map.of()).duoRating();
        double offLane = analyzer().analyze(Map.of(Role.MIDDLE, A, Role.TOP, 5), Map.of()).duoRating();
        assertTrue(botlane > 4 * offLane, "Botlane " + botlane + " gegenüber Top+Mid " + offLane);
        assertTrue(offLane > 0);
    }

    @Test
    void riskScalesThePairPriors() {
        base.put(A_MID, stat(AVERAGE, 50_000));
        base.put(E_MID, stat(AVERAGE, 50_000));
        matchups.put(A_MID, Map.of(E_MID, stat(0.58, 1_500)));
        double cautious = new DraftAnalyzer(dataset(), new Config(Risk.VERY_LOW, false, 0))
                .analyze(Map.of(Role.MIDDLE, A), Map.of(Role.MIDDLE, E)).matchupRating();
        double bold = new DraftAnalyzer(dataset(), new Config(Risk.VERY_HIGH, false, 0))
                .analyze(Map.of(Role.MIDDLE, A), Map.of(Role.MIDDLE, E)).matchupRating();
        assertTrue(bold > cautious && cautious > 0, bold + " > " + cautious);
    }

    @Test
    void ignoringChampionWinRatesKeepsOnlyPairs() {
        base.put(A_MID, stat(0.60, 50_000));
        Result result = new DraftAnalyzer(dataset(), new Config(Risk.MEDIUM, true, 0)).analyze(Map.of(Role.MIDDLE, A), Map.of());
        assertTrue(result.champions().isEmpty());
        assertEquals(0.5, result.winRate(), 1e-9);
    }

    @Test
    void bestCounterPickRanksFirst() {
        base.put(A_MID, stat(AVERAGE, 50_000));
        base.put(B_MID, stat(0.54, 50_000));
        base.put(E_MID, stat(AVERAGE, 50_000));
        // Die Seite des Gegners enthält alle möglichen Picks: A schlägt ihn deutlich, B verliert gegen ihn.
        matchups.put(E_MID, Map.of(A_MID, stat(0.42, 20_000), B_MID, stat(0.58, 20_000)));

        List<Suggestion> suggestions = analyzer().suggestions(Map.of(), Map.of(Role.MIDDLE, E), Set.of());

        assertEquals(A_MID, suggestions.get(0).pick());
        assertEquals(B_MID, suggestions.get(1).pick());
        assertTrue(suggestions.get(0).delta() > 0);
    }

    @Test
    void suggestionsSkipBansPicksFilledRolesAndRareChampions() {
        base.put(A_MID, stat(AVERAGE, 50_000));
        base.put(new Pick(A, Role.TOP), stat(AVERAGE, 500));
        base.put(B_MID, stat(AVERAGE, 50_000));
        base.put(B_TOP, stat(AVERAGE, 50_000));
        base.put(E_MID, stat(AVERAGE, 50_000));
        base.put(new Pick(7, Role.TOP), stat(AVERAGE, 50_000));
        base.put(new Pick(8, Role.TOP), stat(AVERAGE, 50_000));

        List<Pick> picks = analyzer().suggestions(Map.of(Role.MIDDLE, A), Map.of(Role.JUNGLE, E), Set.of(7)).stream()
                .map(Suggestion::pick).toList();

        assertFalse(picks.contains(A_MID), "bereits gewählt");
        assertFalse(picks.contains(E_MID), "vom Gegner gewählt");
        assertFalse(picks.contains(B_MID), "Rolle ist besetzt");
        assertFalse(picks.contains(new Pick(7, Role.TOP)), "gebannt");
        assertEquals(List.of(B_TOP, new Pick(8, Role.TOP)), picks, "A auf Top hat zu wenige Spiele");
    }
}
