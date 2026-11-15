package com.leaguetool.stats;

/**
 * Gemeinsame Kennzahlen für alles, was Spiele und Siege zählt ({@link StatLine}, {@link ChampionReport}).
 */
public interface WinStats {

    /** Anzahl Spiele. */
    int games();

    /** Davon gewonnen. */
    int wins();

    /** Siegquote 0..1 (0 ohne Spiele). */
    default double winRate() {
        return games() == 0 ? 0 : (double) wins() / games();
    }

    /** Untere Wilson-Grenze (95 %). */
    default double wilsonLower() {
        return WilsonScore.lowerBound(wins(), games());
    }

    /** Obere Wilson-Grenze (95 %). */
    default double wilsonUpper() {
        return WilsonScore.upperBound(wins(), games());
    }
}
