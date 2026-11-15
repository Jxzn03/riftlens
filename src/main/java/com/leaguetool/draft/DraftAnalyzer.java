package com.leaguetool.draft;

import com.leaguetool.draft.DraftDataset.Pick;
import com.leaguetool.draft.DraftDataset.Stat;
import com.leaguetool.model.Role;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bewertet einen Draft und alle möglichen nächsten Picks nach dem Modell von DraftGap (MIT-Lizenz): Winrates werden in
 * Rating-Punkte umgerechnet, Stichproben mit einem Prior geglättet, und Champions, Duos und Matchups addieren sich.
 *
 * <p>Bei Lolalytics liegt die durchschnittliche Winrate des Rang-Bereichs über 50 %. Wie DraftGaps {@code removeRankBias}
 * wird dieser Durchschnitt herausgerechnet: Ein Champion mit Durchschnitts-Winrate zählt 0 Punkte, und die erwartete
 * Winrate eines Paares enthält den Durchschnitt einmal. Ohne diese Korrektur würde ein Team mit mehr Picks systematisch
 * besser aussehen.
 *
 * <p>Wichtigste Erweiterung: Die Glättung ist nicht für alles gleich, sondern folgt je Rollenpaar der gemessenen Streuung
 * echter Effekte ({@link DraftPriors}). Lane-Matchups und das Botlane-Duo zählen damit schon bei wenigen Spielen, Duos
 * über die Map hinweg erst bei sehr vielen. Die übrigen Abweichungen vom Original stehen in {@code docs/draft.md}.
 */
public final class DraftAnalyzer {

    /**
     * Wie stark kleine Stichproben geglättet werden: Je weniger Risiko, desto mehr Spiele braucht ein Wert. Der Faktor
     * vervielfacht die aus den Daten geschätzten Prior-Spiele ({@link DraftPriors}); die Stufen entsprechen DraftGaps
     * 3000/2000/1000/500/250 Spielen.
     */
    public enum Risk {
        VERY_LOW(3), LOW(2), MEDIUM(1), HIGH(0.5), VERY_HIGH(0.25);

        /** DraftGaps Prior-Spiele bei mittlerem Risiko; gilt hier nur noch für den Schritt vom Patch zu 30 Tagen. */
        static final int BASE_PRIOR_GAMES = 1000;

        private final double factor;

        Risk(double factor) {
            this.factor = factor;
        }

        public double factor() {
            return factor;
        }

        /** Feste Glättung wie bei DraftGap, wo keine Schätzung aus den Daten vorliegt. */
        public int priorGames() {
            return (int) Math.round(BASE_PRIOR_GAMES * factor);
        }
    }

    /**
     * @param risk                   Glättung kleiner Stichproben
     * @param ignoreChampionWinRates bewertet nur Duos und Matchups
     * @param minGames               Mindestspiele eines Vorschlags auf seiner Rolle in den letzten 30 Tagen
     */
    public record Config(Risk risk, boolean ignoreChampionWinRates, int minGames) {
    }

    public record ChampionResult(Pick pick, double rating, Stat stats) {
    }

    /** Bei Matchups gelten Rating und Werte aus Sicht von {@code first}. */
    public record PairResult(Pick first, Pick second, double rating, Stat stats) {
    }

    public record Result(List<ChampionResult> champions, List<ChampionResult> opponentChampions, List<PairResult> duos,
                         List<PairResult> opponentDuos, List<PairResult> matchups, double totalRating, double winRate) {

        public double championRating() {
            return champions.stream().mapToDouble(ChampionResult::rating).sum()
                    - opponentChampions.stream().mapToDouble(ChampionResult::rating).sum();
        }

        public double duoRating() {
            return duos.stream().mapToDouble(PairResult::rating).sum()
                    - opponentDuos.stream().mapToDouble(PairResult::rating).sum();
        }

        public double matchupRating() {
            return matchups.stream().mapToDouble(PairResult::rating).sum();
        }
    }

    /** @param delta Änderung der Winrate gegenüber dem Draft ohne diesen Pick */
    public record Suggestion(Pick pick, Result result, double delta) {
    }

    /** DraftGaps Standard von 1000 Spielen pro Woche, bezogen auf die Picks von Emerald+ in 30 Tagen. */
    private static final double DEFAULT_MIN_GAMES_SHARE = 4300.0 / 57_802_290;
    private static final int LOWEST_DEFAULT_MIN_GAMES = 100;

    private final DraftDataset data;
    private final Config config;
    private final DraftPriors priors;

    /** Schätzt die Streuungen aus {@code data}. */
    public DraftAnalyzer(DraftDataset data, Config config) {
        this(data, config, DraftPriors.estimate(data));
    }

    public DraftAnalyzer(DraftDataset data, Config config, DraftPriors priors) {
        this.data = data;
        this.config = config;
        this.priors = priors;
    }

    public DraftPriors priors() {
        return priors;
    }

    /** Standard für die Mindestspiele: wächst mit der Spielmenge, damit auch kleine Rang-Bereiche Vorschläge haben. */
    public static int defaultMinGames(long analysedPicks) {
        return Math.max(LOWEST_DEFAULT_MIN_GAMES, (int) Math.round(analysedPicks * DEFAULT_MIN_GAMES_SHARE));
    }

    /** Rating-Punkte zu einer Winrate; Elo-Skala, 0 entspricht 50 %. */
    public static double rating(double winRate) {
        double bounded = Math.max(0.0001, Math.min(0.9999, winRate));
        return -400 * Math.log10(1 / bounded - 1);
    }

    public static double winRate(double rating) {
        return 1 / (1 + Math.pow(10, -rating / 400));
    }

    public Result analyze(Map<Role, Integer> team, Map<Role, Integer> opponents) {
        List<Pick> own = picks(team);
        List<Pick> other = picks(opponents);
        List<ChampionResult> champions = champions(own);
        List<ChampionResult> opponentChampions = champions(other);
        List<PairResult> duos = duos(own);
        List<PairResult> opponentDuos = duos(other);
        List<PairResult> matchups = new ArrayList<>();
        for (Pick a : own) {
            for (Pick b : other) {
                matchups.add(matchup(a, b));
            }
        }
        double total = sum(champions) - sum(opponentChampions) + sumPairs(duos) - sumPairs(opponentDuos) + sumPairs(matchups);
        return new Result(champions, opponentChampions, duos, opponentDuos, matchups, total, winRate(total));
    }

    /**
     * Alle erlaubten nächsten Picks für {@code team}, der beste zuerst. Jeder Champion wird auf jeder freien Rolle mit
     * genug Spielen als kompletter Draft durchgerechnet.
     *
     * @param excluded gebannte Champions
     */
    public List<Suggestion> suggestions(Map<Role, Integer> team, Map<Role, Integer> opponents, Set<Integer> excluded) {
        double baseline = analyze(team, opponents).winRate();
        List<Suggestion> suggestions = new ArrayList<>();
        for (int champion : data.champions()) {
            if (excluded.contains(champion) || team.containsValue(champion) || opponents.containsValue(champion)) {
                continue;
            }
            for (Role role : Role.values()) {
                Pick pick = new Pick(champion, role);
                // Auch bei Mindestspielen 0 braucht ein Vorschlag echte Spiele auf dieser Rolle.
                if (team.containsKey(role) || data.full(pick).games() < Math.max(1, config.minGames())) {
                    continue;
                }
                Map<Role, Integer> candidate = new EnumMap<>(Role.class);
                candidate.putAll(team);
                candidate.put(role, champion);
                Result result = analyze(candidate, opponents);
                suggestions.add(new Suggestion(pick, result, result.winRate() - baseline));
            }
        }
        suggestions.sort(Comparator.comparingDouble((Suggestion s) -> s.result().winRate()).reversed()
                .thenComparingInt(s -> s.pick().champion())
                .thenComparing(s -> s.pick().role()));
        return suggestions;
    }

    private static List<Pick> picks(Map<Role, Integer> team) {
        return team.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new Pick(e.getValue(), e.getKey()))
                .toList();
    }

    private static double sum(List<ChampionResult> results) {
        return results.stream().mapToDouble(ChampionResult::rating).sum();
    }

    private static double sumPairs(List<PairResult> results) {
        return results.stream().mapToDouble(PairResult::rating).sum();
    }

    private List<ChampionResult> champions(List<Pick> picks) {
        if (config.ignoreChampionWinRates()) {
            return List.of();
        }
        return picks.stream().map(this::champion).toList();
    }

    private ChampionResult champion(Pick pick) {
        Stat stats = data.selected(pick);
        // Der Prior wird selbst zum Durchschnitt gezogen, so stark, wie Champion-Stärken auf dieser Rolle streuen.
        // Sonst wäre er bei 30 Tagen die eigene Winrate und wirkungslos.
        Stat full = data.full(pick);
        double championPrior = priors.championPriorGames(pick.role()) * config.risk().factor();
        double priorWinRate = (full.wins() + championPrior * data.fullAverage()) / (full.games() + championPrior);
        // Sind beide Zeiträume gleich, wären dieselben Spiele sonst doppelt gezählt.
        double patchPrior = config.risk().priorGames();
        double smoothed = stats.equals(full) ? priorWinRate
                : (stats.wins() + patchPrior * priorWinRate) / (stats.games() + patchPrior);
        return new ChampionResult(pick, rating(smoothed) - rating(data.selectedAverage()), stats);
    }

    private double baseRating(Pick pick) {
        return rating(data.full(pick).winRate(data.fullAverage()));
    }

    private List<PairResult> duos(List<Pick> picks) {
        List<PairResult> duos = new ArrayList<>();
        for (int i = 0; i < picks.size(); i++) {
            for (int j = i + 1; j < picks.size(); j++) {
                duos.add(duo(picks.get(i), picks.get(j)));
            }
        }
        return duos;
    }

    private PairResult duo(Pick a, Pick b) {
        double expected = baseRating(a) + baseRating(b) - rating(data.fullAverage());
        Stat ab = data.synergy(a, b);
        Stat ba = data.synergy(b, a);
        double total = 0;
        int sources = 0;
        double priorGames = pairPriorGames(DraftPriors.Kind.DUO, a, b, expected);
        for (Stat stat : List.of(ab, ba)) {
            if (stat.games() > 0) {
                total += excessRating(stat, expected, priorGames);
                sources++;
            }
        }
        double rating = sources == 0 ? 0 : total / sources;
        int divisor = Math.max(1, sources);
        return new PairResult(a, b, rating, new Stat((ab.wins() + ba.wins()) / divisor, (ab.games() + ba.games()) / divisor));
    }

    /** Beide Quellen meinen dasselbe Spiel: Ein Sieg von {@code b} gegen {@code a} ist eine Niederlage von {@code a}. */
    private PairResult matchup(Pick a, Pick b) {
        double average = rating(data.fullAverage());
        double ratingA = baseRating(a);
        double ratingB = baseRating(b);
        Stat ab = data.matchup(a, b);
        Stat ba = data.matchup(b, a);
        double total = 0;
        int sources = 0;
        double expectedA = ratingA - ratingB + average;
        double expectedB = ratingB - ratingA + average;
        if (ab.games() > 0) {
            total += excessRating(ab, expectedA, pairPriorGames(DraftPriors.Kind.MATCHUP, a, b, expectedA));
            sources++;
        }
        if (ba.games() > 0) {
            total -= excessRating(ba, expectedB, pairPriorGames(DraftPriors.Kind.MATCHUP, a, b, expectedB));
            sources++;
        }
        double rating = sources == 0 ? 0 : total / sources;
        int divisor = Math.max(1, sources);
        Stat combined = new Stat((ab.wins() + ba.games() - ba.wins()) / divisor, (ab.games() + ba.games()) / divisor);
        return new PairResult(a, b, rating, combined);
    }

    /** Prior-Spiele einer Paarung: je Rollenpaar aus der Streuung echter Effekte, vervielfacht mit dem Risiko. */
    private double pairPriorGames(DraftPriors.Kind kind, Pick a, Pick b, double expectedRating) {
        return priors.pairPriorGames(kind, a.role(), b.role(), winRate(expectedRating)) * config.risk().factor();
    }

    /** Rating-Punkte über der Erwartung; der Prior zieht kleine Stichproben zur Erwartung, also gegen 0. */
    private static double excessRating(Stat stat, double expectedRating, double priorGames) {
        double smoothed = (stat.wins() + priorGames * winRate(expectedRating)) / (stat.games() + priorGames);
        return rating(smoothed) - expectedRating;
    }
}
