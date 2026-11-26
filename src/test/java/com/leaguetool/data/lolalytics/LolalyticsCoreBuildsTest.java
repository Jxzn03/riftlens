package com.leaguetool.data.lolalytics;

import com.leaguetool.stats.StatLine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LolalyticsCoreBuildsTest {

    /** Darius-Beispiel: 3047 Steelcaps, 3111 Mercs, 3009 Swiftness; 3142 Ghostblade, 3742 Dead Man's, 6631 Stridebreaker, 3053 Sterak's. */
    private static final IntPredicate BOOTS = Set.of(3047, 3111, 3009)::contains;

    private static LolalyticsBuild.Entry build(String key, double winRate, double pickRate, int games) {
        return new LolalyticsBuild.Entry(key, winRate, pickRate, games);
    }

    private static StatLine<List<Integer>> line(int games, Integer... ids) {
        return new StatLine<>(List.of(ids), games, games / 2, 0);
    }

    @Test
    void theSameLegendaryPathWithOtherBootsBecomesOneEntryInTheMostPlayedOrder() {
        List<LolalyticsBuild.Entry> merged = LolalyticsCoreBuilds.merge(List.of(
                build("3047_3142_3742", 0.50, 0.06, 600),
                build("3142_3111_3742", 0.56, 0.03, 300),
                build("3142_3009_3742", 0.60, 0.01, 100),
                build("3742_3142_3047", 0.70, 0.01, 50),
                build("3047_6631_3053", 0.54, 0.07, 700)), BOOTS);

        assertEquals(3, merged.size(), "andere Reihenfolge der Items ist ein anderer Pfad");
        LolalyticsBuild.Entry first = merged.get(0);
        assertEquals("3047_3142_3742", first.key(), "häufigste konkrete Reihenfolge samt Boots");
        assertEquals(1000, first.games());
        assertEquals((0.50 * 600 + 0.56 * 300 + 0.60 * 100) / 1000, first.winRate(), 1e-12, "nach Spielen gewichtet");
        assertEquals(0.10, first.pickRate(), 1e-12);
        assertEquals("3047_6631_3053", merged.get(1).key());
    }

    @Test
    void theBestPathOfEachFirstItemComesFirstAndTheBestStaysOnTop() {
        List<StatLine<List<Integer>>> best = List.of(
                line(1000, 3047, 3142, 3742),
                line(900, 3142, 3047, 3053),
                line(800, 3047, 6631, 3053),
                line(700, 3142, 3111, 6610));

        List<StatLine<List<Integer>>> ordered = LolalyticsCoreBuilds.byFirstItem(best, BOOTS);

        assertEquals(List.of(best.get(0), best.get(2), best.get(1), best.get(3)), ordered);
    }

    @Test
    void displayKeepsTheMostPlayedPathAndSortsTheChosenRowsByTheRanking() {
        // Annie-Fall: Rangliste A1, A2 (meistgespielt), B, C, D; die Vielfalt wählt A1, B, C, D.
        StatLine<List<Integer>> a1 = line(30_000, 3047, 3142, 3742);
        StatLine<List<Integer>> a2 = line(31_000, 3047, 3142, 3053);
        StatLine<List<Integer>> b = line(1_400, 3047, 6631, 3053);
        StatLine<List<Integer>> c = line(1_350, 3047, 3078, 3053);
        StatLine<List<Integer>> d = line(1_300, 3047, 3161, 3053);
        List<StatLine<List<Integer>>> best = List.of(a1, a2, b, c, d);

        List<StatLine<List<Integer>>> shown = LolalyticsCoreBuilds.forDisplay(best, a2.key(), BOOTS, 4);

        assertEquals(List.of(a1, a2, b, c), shown.subList(0, 4), "Meistgespielt statt des letzten, alles in Ranglisten-Reihenfolge");
        assertEquals(d, shown.get(4));
    }

    @Test
    void firstAndSecondItemAreSummedFromTheCoreBuildsWithoutBoots() {
        List<LolalyticsBuild.Entry> builds = List.of(
                build("3047_3142_3742", 0.50, 0.06, 600),
                build("3142_3111_3053", 0.60, 0.04, 400),
                build("6631_3047_3053", 0.55, 0.02, 200));

        List<LolalyticsBuild.Entry> first = LolalyticsCoreBuilds.itemAt(builds, BOOTS, 0, 10_000);
        assertEquals("3142", first.get(0).key());
        assertEquals(1000, first.get(0).games());
        assertEquals(0.54, first.get(0).winRate(), 1e-12);
        assertEquals(0.10, first.get(0).pickRate(), 1e-12, "Anteil an allen Spielen des Champions");
        assertEquals("6631", first.get(1).key());

        List<LolalyticsBuild.Entry> second = LolalyticsCoreBuilds.itemAt(builds, BOOTS, 1, 10_000);
        assertEquals(List.of("3742", "3053"), second.stream().map(LolalyticsBuild.Entry::key).toList());
        assertEquals(600, second.get(1).games(), "Sterak's als zweites Item nach Ghostblade und nach Stridebreaker");
    }
}
