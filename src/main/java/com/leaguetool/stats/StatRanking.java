package com.leaguetool.stats;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Dieselben Statistik-Zeilen in zwei Sortierungen.
 *
 * @param mostFrequent alle Zeilen, häufigste zuerst ({@link StatLine#mostFrequent})
 * @param best         nur Zeilen mit mindestens {@code minGames} Spielen, höchste Wilson-Untergrenze zuerst
 *                     ({@link StatLine#bestByWilson})
 * @param minGames     Mindest-Stichprobe für {@code best}
 */
public record StatRanking<K>(List<StatLine<K>> mostFrequent, List<StatLine<K>> best, int minGames) {

    public StatRanking {
        mostFrequent = List.copyOf(mostFrequent);
        best = List.copyOf(best);
    }

    public static <K> StatRanking<K> of(Collection<StatLine<K>> lines, int minGames) {
        return new StatRanking<>(StatLine.mostFrequent(lines), StatLine.bestByWilson(lines, minGames), minGames);
    }

    public boolean isEmpty() {
        return mostFrequent.isEmpty();
    }

    /** Häufigste Zeile. */
    public Optional<StatLine<K>> top() {
        return mostFrequent.isEmpty() ? Optional.empty() : Optional.of(mostFrequent.get(0));
    }

    /** Beste Zeile nach Wilson (mit Mindest-Stichprobe). */
    public Optional<StatLine<K>> topBest() {
        return best.isEmpty() ? Optional.empty() : Optional.of(best.get(0));
    }
}
