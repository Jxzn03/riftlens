package com.leaguetool.draft;

import com.leaguetool.draft.DraftDataset.Pick;
import com.leaguetool.draft.DraftDataset.Stat;
import com.leaguetool.model.Role;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wie stark echte Effekte je Beziehung streuen (empirisches Bayes) und wie viele Prior-Spiele daraus folgen.
 *
 * <p>DraftGap glättet jede Paarung mit derselben Zahl an Prior-Spielen. Echte Effekte sind aber je Rollenpaar sehr
 * verschieden groß: Ein Lane-Matchup oder das Botlane-Duo entscheidet viel, Top und Mid im selben Team kaum etwas.
 * Hier wird die echte Streuung je Rollenpaar aus den geladenen Paarungen geschätzt (DerSimonian-Laird: beobachtete
 * Streuung minus erwartetes Zufallsrauschen) und mit Standardwerten verrechnet, die aus Emerald+-Daten über 30 Tage
 * stammen. Kleine echte Streuung bedeutet viele Prior-Spiele, also starkes Ziehen zur Erwartung.
 */
public final class DraftPriors {

    public enum Kind { MATCHUP, DUO }

    /** Streuung in Rating-Punkten, {@code samples} Paarungen der Schätzung aus den geladenen Daten. */
    public record Spread(double rating, int samples) {
    }

    /** Standard-Streuung der Champion-Stärke je Rolle (Emerald+: 10 bis 12,6 Punkte). */
    static final double DEFAULT_CHAMPION_SPREAD = 11;
    /** So viele Paarungen wiegt ein Standardwert; erst darüber bestimmen die geladenen Daten das Ergebnis. */
    static final int DEFAULT_PAIR_WEIGHT = 200;
    static final int DEFAULT_CHAMPION_WEIGHT = 40;
    /** Paarungen mit weniger Spielen tragen zur Schätzung kaum bei und verrauschen sie nur. */
    static final int MIN_GAMES_FOR_ESTIMATE = 30;
    static final int MIN_CHAMPION_GAMES_FOR_ESTIMATE = 200;
    private static final double MIN_SPREAD = 1;
    private static final double LOGIT_PER_RATING = Math.log(10) / 400;

    private static final Map<String, Double> DEFAULT_PAIR_SPREADS = defaults();

    private final Map<String, Spread> pairSpreads;
    private final Map<Role, Spread> championSpreads;
    private final double average;

    private DraftPriors(Map<String, Spread> pairSpreads, Map<Role, Spread> championSpreads, double average) {
        this.pairSpreads = Map.copyOf(pairSpreads);
        this.championSpreads = Map.copyOf(championSpreads);
        this.average = average;
    }

    /** Gemessen in Emerald+ über 30 Tage; Botlane-Matchups ohne eigene Messung sind geschätzt. */
    private static Map<String, Double> defaults() {
        Map<String, Double> d = new HashMap<>();
        Role top = Role.TOP, jungle = Role.JUNGLE, mid = Role.MIDDLE, bot = Role.BOTTOM, sup = Role.UTILITY;
        d.put(key(Kind.MATCHUP, top, top), 14.5);
        d.put(key(Kind.MATCHUP, jungle, jungle), 10.0);
        d.put(key(Kind.MATCHUP, mid, mid), 13.0);
        d.put(key(Kind.MATCHUP, bot, bot), 12.0);
        d.put(key(Kind.MATCHUP, sup, sup), 12.0);
        d.put(key(Kind.MATCHUP, bot, sup), 10.0);
        d.put(key(Kind.MATCHUP, top, jungle), 6.7);
        d.put(key(Kind.MATCHUP, top, mid), 7.1);
        d.put(key(Kind.MATCHUP, top, bot), 6.9);
        d.put(key(Kind.MATCHUP, top, sup), 8.5);
        d.put(key(Kind.MATCHUP, jungle, mid), 7.8);
        d.put(key(Kind.MATCHUP, jungle, bot), 8.4);
        d.put(key(Kind.MATCHUP, jungle, sup), 7.5);
        d.put(key(Kind.MATCHUP, mid, bot), 4.8);
        d.put(key(Kind.MATCHUP, mid, sup), 8.7);
        d.put(key(Kind.DUO, bot, sup), 14.3);
        d.put(key(Kind.DUO, jungle, mid), 5.2);
        d.put(key(Kind.DUO, jungle, top), 4.6);
        d.put(key(Kind.DUO, jungle, sup), 4.6);
        d.put(key(Kind.DUO, jungle, bot), 3.9);
        d.put(key(Kind.DUO, mid, sup), 4.2);
        d.put(key(Kind.DUO, mid, bot), 4.5);
        d.put(key(Kind.DUO, top, bot), 3.6);
        d.put(key(Kind.DUO, top, mid), 2.9);
        d.put(key(Kind.DUO, top, sup), 3.2);
        return Map.copyOf(d);
    }

    /** Die Rollen sind ungeordnet: Top gegen Mid ist dieselbe Beziehung wie Mid gegen Top. */
    private static String key(Kind kind, Role a, Role b) {
        Role first = a.ordinal() <= b.ordinal() ? a : b;
        Role second = first == a ? b : a;
        return kind + ":" + first + ":" + second;
    }

    static double defaultSpread(Kind kind, Role a, Role b) {
        return DEFAULT_PAIR_SPREADS.get(key(kind, a, b));
    }

    /** Nur die Standardwerte, ohne geladene Daten. */
    public static DraftPriors defaults(double average) {
        return new DraftPriors(Map.of(), Map.of(), average);
    }

    /** Schätzt die Streuungen aus allen Paarungen und Champion-Werten (30 Tage) des Datensatzes. */
    public static DraftPriors estimate(DraftDataset data) {
        double average = data.fullAverage();
        Map<String, List<double[]>> pairs = new HashMap<>();
        collect(data, data.matchups(), Kind.MATCHUP, pairs);
        collect(data, data.synergies(), Kind.DUO, pairs);
        Map<String, Spread> pairSpreads = new HashMap<>();
        pairs.forEach((key, observations) -> pairSpreads.put(key, pooled(observations,
                DEFAULT_PAIR_SPREADS.get(key), DEFAULT_PAIR_WEIGHT)));

        Map<Role, List<double[]>> champions = new EnumMap<>(Role.class);
        double averageRating = DraftAnalyzer.rating(average);
        data.fullStats().forEach((pick, stat) -> {
            if (stat.games() >= MIN_CHAMPION_GAMES_FOR_ESTIMATE) {
                double rate = stat.wins() / stat.games();
                champions.computeIfAbsent(pick.role(), r -> new ArrayList<>())
                        .add(observation(rate, stat.games(), averageRating));
            }
        });
        Map<Role, Spread> championSpreads = new EnumMap<>(Role.class);
        champions.forEach((role, observations) -> championSpreads.put(role,
                pooled(observations, DEFAULT_CHAMPION_SPREAD, DEFAULT_CHAMPION_WEIGHT)));
        return new DraftPriors(pairSpreads, championSpreads, average);
    }

    private static void collect(DraftDataset data, Map<Pick, Map<Pick, Stat>> source, Kind kind,
                                Map<String, List<double[]>> target) {
        double averageRating = DraftAnalyzer.rating(data.fullAverage());
        source.forEach((pick, others) -> {
            Stat own = data.full(pick);
            if (own.games() <= 0) {
                return;
            }
            double ownRating = DraftAnalyzer.rating(own.wins() / own.games());
            others.forEach((other, stat) -> {
                Stat base = data.full(other);
                if (base.games() <= 0 || stat.games() < MIN_GAMES_FOR_ESTIMATE || pick.role() == other.role() && kind == Kind.DUO) {
                    return;
                }
                // Beide Seiten einer Paarung beschreiben dieselben Spiele: nur einmal zählen.
                if (source.getOrDefault(other, Map.of()).containsKey(pick) && comesFirst(other, pick)) {
                    return;
                }
                double otherRating = DraftAnalyzer.rating(base.wins() / base.games());
                double expected = kind == Kind.MATCHUP
                        ? ownRating - otherRating + averageRating
                        : ownRating + otherRating - averageRating;
                target.computeIfAbsent(key(kind, pick.role(), other.role()), k -> new ArrayList<>())
                        .add(observation(stat.wins() / stat.games(), stat.games(), expected));
            });
        });
    }

    private static boolean comesFirst(Pick a, Pick b) {
        return a.champion() != b.champion() ? a.champion() < b.champion() : a.role().ordinal() < b.role().ordinal();
    }

    /** Abweichung von der Erwartung und ihr Zufallsrauschen, beides in Rating-Punkten². */
    private static double[] observation(double winRate, double games, double expectedRating) {
        double p = DraftAnalyzer.winRate(expectedRating);
        double deviation = DraftAnalyzer.rating(winRate) - expectedRating;
        double noise = 1 / (games * p * (1 - p)) / (LOGIT_PER_RATING * LOGIT_PER_RATING);
        return new double[]{deviation, noise};
    }

    /**
     * DerSimonian-Laird-Schätzung der echten Streuung, verrechnet mit dem Standardwert, der wie
     * {@code defaultWeight} Paarungen zählt.
     */
    static Spread pooled(List<double[]> observations, double defaultSpread, int defaultWeight) {
        int k = observations.size();
        double variance = defaultSpread * defaultSpread;
        if (k >= 2) {
            double sumW = 0;
            double sumWx = 0;
            double sumW2 = 0;
            for (double[] o : observations) {
                double w = 1 / o[1];
                sumW += w;
                sumWx += w * o[0];
                sumW2 += w * w;
            }
            double mean = sumWx / sumW;
            double q = 0;
            for (double[] o : observations) {
                q += (o[0] - mean) * (o[0] - mean) / o[1];
            }
            double estimate = Math.max(0, (q - (k - 1)) / (sumW - sumW2 / sumW));
            variance = (k * estimate + defaultWeight * variance) / (k + defaultWeight);
        }
        return new Spread(Math.max(MIN_SPREAD, Math.sqrt(variance)), k);
    }

    /** Streuung echter Effekte zwischen zwei Picks in Rating-Punkten. */
    public double pairSpread(Kind kind, Role a, Role b) {
        Spread spread = pairSpreads.get(key(kind, a, b));
        return spread != null ? spread.rating() : DEFAULT_PAIR_SPREADS.get(key(kind, a, b));
    }

    /** Streuung der Champion-Stärke auf einer Rolle in Rating-Punkten. */
    public double championSpread(Role role) {
        Spread spread = championSpreads.get(role);
        return spread != null ? spread.rating() : DEFAULT_CHAMPION_SPREAD;
    }

    public double pairPriorGames(Kind kind, Role a, Role b, double expectedWinRate) {
        return priorGames(pairSpread(kind, a, b), expectedWinRate);
    }

    public double championPriorGames(Role role) {
        return priorGames(championSpread(role), average);
    }

    /**
     * Prior-Spiele, bei denen eine Stichprobe halb zählt: Dort ist ihr Zufallsrauschen so groß wie die echte Streuung.
     */
    static double priorGames(double spreadRating, double winRate) {
        double logit = spreadRating * LOGIT_PER_RATING;
        double p = Math.max(0.01, Math.min(0.99, winRate));
        return 1 / (logit * logit * p * (1 - p));
    }

    /** Alle Streuungen für die Anzeige: Schlüssel wie {@code DUO:BOTTOM:UTILITY}. */
    public Map<String, Spread> pairSpreads() {
        Map<String, Spread> all = new HashMap<>();
        DEFAULT_PAIR_SPREADS.forEach((key, value) -> all.put(key, pairSpreads.getOrDefault(key, new Spread(value, 0))));
        return all;
    }
}
