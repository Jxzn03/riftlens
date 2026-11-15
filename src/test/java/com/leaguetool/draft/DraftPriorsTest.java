package com.leaguetool.draft;

import com.leaguetool.draft.DraftDataset.Pick;
import com.leaguetool.draft.DraftDataset.Stat;
import com.leaguetool.draft.DraftPriors.Kind;
import com.leaguetool.model.Role;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DraftPriorsTest {

    private static final double AVERAGE = 0.52;

    @Test
    void priorGamesFollowTheSpread() {
        // 13 Punkte Streuung bei 50 % entsprechen gut 700 Spielen, wie die Bewertung im Counters-Tab.
        assertEquals(714, DraftPriors.priorGames(13, 0.5), 1);
        assertEquals(4 * DraftPriors.priorGames(13, 0.5), DraftPriors.priorGames(6.5, 0.5), 1e-6);
    }

    @Test
    void laneMatchupsAndBotlaneDuoCountFarMoreThanOffLaneDuos() {
        DraftPriors priors = DraftPriors.defaults(AVERAGE);
        double lane = priors.pairPriorGames(Kind.MATCHUP, Role.MIDDLE, Role.MIDDLE, 0.5);
        double botlane = priors.pairPriorGames(Kind.DUO, Role.BOTTOM, Role.UTILITY, 0.5);
        double topMid = priors.pairPriorGames(Kind.DUO, Role.TOP, Role.MIDDLE, 0.5);
        assertTrue(lane < 1000 && botlane < 1000, lane + " / " + botlane);
        assertTrue(topMid > 10 * lane, "Top und Mid im selben Team wirken kaum aufeinander: " + topMid);
    }

    @Test
    void rolesAreUnordered() {
        DraftPriors priors = DraftPriors.defaults(AVERAGE);
        assertEquals(priors.pairSpread(Kind.DUO, Role.UTILITY, Role.BOTTOM), priors.pairSpread(Kind.DUO, Role.BOTTOM, Role.UTILITY));
        assertEquals(priors.pairSpread(Kind.MATCHUP, Role.TOP, Role.JUNGLE), priors.pairSpread(Kind.MATCHUP, Role.JUNGLE, Role.TOP));
    }

    @Test
    void pooledEstimateFindsTheTrueSpreadAndSubtractsNoise() {
        Random random = new Random(7);
        List<double[]> observations = new ArrayList<>();
        double noise = 25;
        for (int i = 0; i < 20_000; i++) {
            double effect = random.nextGaussian() * 10;
            observations.add(new double[]{effect + random.nextGaussian() * noise, noise * noise});
        }
        DraftPriors.Spread spread = DraftPriors.pooled(observations, 3, DraftPriors.DEFAULT_PAIR_WEIGHT);
        assertEquals(10, spread.rating(), 0.6, "Nur die echte Streuung, nicht das Rauschen");
        assertEquals(20_000, spread.samples());
    }

    @Test
    void fewObservationsStayCloseToTheDefault() {
        List<double[]> observations = List.of(new double[]{40, 1}, new double[]{-40, 1});
        DraftPriors.Spread spread = DraftPriors.pooled(observations, 5, DraftPriors.DEFAULT_PAIR_WEIGHT);
        assertTrue(spread.rating() < 8, "Zwei Ausreißer verschieben den Standard kaum: " + spread.rating());
    }

    @Test
    void estimateUsesTheLoadedPairs() {
        Map<Pick, Stat> full = new HashMap<>();
        Map<Pick, Map<Pick, Stat>> synergies = new HashMap<>();
        Pick source = new Pick(1, Role.TOP);
        full.put(source, new Stat(AVERAGE * 100_000, 100_000));
        Map<Pick, Stat> mates = new HashMap<>();
        // 600 Mitspieler auf Mid mit großem Unterschied: ±6 Prozentpunkte bei je 50.000 Spielen.
        for (int i = 0; i < 600; i++) {
            Pick mate = new Pick(100 + i, Role.MIDDLE);
            full.put(mate, new Stat(AVERAGE * 100_000, 100_000));
            double rate = AVERAGE + (i % 2 == 0 ? 0.06 : -0.06);
            mates.put(mate, new Stat(rate * 50_000, 50_000));
        }
        synergies.put(source, mates);
        DraftDataset data = new DraftDataset(AVERAGE, AVERAGE, full, full, Map.of(), synergies);

        DraftPriors priors = DraftPriors.estimate(data);

        double measured = priors.pairSpread(Kind.DUO, Role.MIDDLE, Role.TOP);
        assertTrue(measured > 3 * DraftPriors.defaultSpread(Kind.DUO, Role.TOP, Role.MIDDLE),
                "Die geladenen Daten zeigen große Duo-Effekte: " + measured);
        assertEquals(DraftPriors.defaultSpread(Kind.DUO, Role.BOTTOM, Role.UTILITY),
                priors.pairSpread(Kind.DUO, Role.BOTTOM, Role.UTILITY), 1e-9, "ohne Daten gilt der Standard");
    }
}
