package com.leaguetool.stats;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Empirisches Bayes-Ranking (Beta-Binomial) für Zeilen einer Rangliste.
 *
 * <p>Wilson bewertet jede Zeile einzeln gegen eine neutrale Annahme von 50 %. Hier wird der Prior stattdessen aus
 * allen Zeilen derselben Rangliste geschätzt (Mittelwert und Streuung der Winraten): Die Winrate jeder Zeile wird
 * zum Durchschnitt der Rangliste gezogen, und zwar umso stärker, je weniger Spiele sie hat. Gerankt wird nach einem
 * vorsichtigen unteren Quantil der Posterior-Verteilung. Bei Millionen Spielen verschwindet der Prior, bei wenigen
 * Spielen verhindert er Ausreißer, ohne wie Wilson alle Zeilen gleichmäßig gegen 50 % zu bewerten.
 */
public final class BayesScore {

    /** z-Wert des unteren Quantils (≈ 10 %): vorsichtig, aber nicht so streng wie das 95-%-Intervall von Wilson. */
    public static final double QUANTILE_Z = 1.2816;

    private static final double MIN_PRIOR_STRENGTH = 20;
    private static final double MAX_PRIOR_STRENGTH = 20_000;

    private BayesScore() {
    }

    /** Beta-Prior der Rangliste: {@code alpha}, {@code beta}. */
    public record Prior(double alpha, double beta) {
        public double mean() {
            return alpha / (alpha + beta);
        }

        public double strength() {
            return alpha + beta;
        }
    }

    /**
     * Schätzt den Prior per Momentenmethode. Die beobachtete Streuung der Winraten besteht aus echtem Unterschied
     * der Zeilen und Zufallsrauschen; das erwartete Rauschen wird abgezogen. Bleibt kaum echte Streuung übrig,
     * greift die Obergrenze der Prior-Stärke (die Zeilen sind dann praktisch gleich gut).
     */
    public static Prior estimatePrior(Collection<? extends WinStats> lines) {
        double totalGames = 0;
        double totalWins = 0;
        int count = 0;
        for (WinStats l : lines) {
            if (l.games() > 0) {
                totalGames += l.games();
                totalWins += l.wins();
                count++;
            }
        }
        if (count == 0) {
            return new Prior(1, 1);
        }
        double mean = clamp(totalWins / totalGames, 1e-4, 1 - 1e-4);
        double observed = 0;
        for (WinStats l : lines) {
            if (l.games() > 0) {
                double p = (double) l.wins() / l.games();
                observed += l.games() * (p - mean) * (p - mean);
            }
        }
        observed /= totalGames;
        double noise = mean * (1 - mean) * count / totalGames;   // Σ n·(1/n) = Anzahl Zeilen
        double trueVariance = observed - noise;
        double strength = trueVariance <= 0 ? MAX_PRIOR_STRENGTH : mean * (1 - mean) / trueVariance - 1;
        strength = clamp(strength, MIN_PRIOR_STRENGTH, MAX_PRIOR_STRENGTH);
        return new Prior(mean * strength, (1 - mean) * strength);
    }

    /** Posterior-Mittelwert der Winrate. */
    public static double posteriorMean(Prior prior, int wins, int games) {
        return (prior.alpha() + wins) / (prior.strength() + games);
    }

    /** Unteres Quantil der Posterior-Verteilung (Normalnäherung der Beta-Verteilung). */
    public static double lowerBound(Prior prior, int wins, int games) {
        double a = prior.alpha() + wins;
        double b = prior.beta() + (games - wins);
        double n = a + b;
        double mean = a / n;
        double sd = Math.sqrt(a * b / (n * n * (n + 1)));
        return mean - QUANTILE_Z * sd;
    }

    /** Wie {@link StatLine#bestByWilson}, aber nach Bayes-Untergrenze; Prior aus allen übergebenen Zeilen. */
    public static <K> List<StatLine<K>> best(Collection<StatLine<K>> lines, int minGames) {
        Prior prior = estimatePrior(lines);
        return lines.stream().filter(l -> l.games() >= minGames)
                .sorted(Comparator.<StatLine<K>>comparingDouble(l -> lowerBound(prior, l.wins(), l.games())).reversed()
                        .thenComparing(Comparator.<StatLine<K>>comparingInt(StatLine::games).reversed()))
                .toList();
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
