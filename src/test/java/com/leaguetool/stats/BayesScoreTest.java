package com.leaguetool.stats;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BayesScoreTest {

    private static StatLine<String> line(String key, int games, int wins) {
        return new StatLine<>(key, games, wins, 0);
    }

    @Test
    void smallSampleIsPulledTowardTheListAverage() {
        List<StatLine<String>> lines = List.of(line("big", 10_000, 5_300), line("mid", 5_000, 2_600),
                line("tiny", 30, 24), line("other", 8_000, 4_100));
        BayesScore.Prior prior = BayesScore.estimatePrior(lines);
        double shrunk = BayesScore.posteriorMean(prior, 24, 30);
        assertTrue(shrunk < 0.8, "80 % aus 30 Spielen wird nicht übernommen: " + shrunk);
        assertTrue(shrunk > prior.mean());
    }

    @Test
    void largeSampleBarelyChanges() {
        List<StatLine<String>> lines = List.of(line("a", 100_000, 53_000), line("b", 90_000, 46_800));
        BayesScore.Prior prior = BayesScore.estimatePrior(lines);
        assertEquals(0.53, BayesScore.posteriorMean(prior, 53_000, 100_000), 0.002);
    }

    @Test
    void rankingPrefersTheReliableLine() {
        StatLine<String> lucky = line("lucky", 20, 17);
        StatLine<String> solid = line("solid", 5_000, 2_750);
        StatLine<String> weak = line("weak", 6_000, 3_000);
        List<StatLine<String>> best = BayesScore.best(List.of(lucky, solid, weak), 10);
        assertEquals(List.of(solid, lucky, weak).get(0), best.get(0));
    }

    @Test
    void minGamesFiltersAndEmptyIsSafe() {
        assertEquals(List.of(), BayesScore.best(List.<StatLine<String>>of(), 5));
        assertEquals(1, BayesScore.best(List.of(line("a", 4, 4), line("b", 50, 25)), 5).size());
        assertEquals(0.5, BayesScore.estimatePrior(List.<StatLine<String>>of()).mean());
    }
}
