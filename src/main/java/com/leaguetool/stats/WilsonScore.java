package com.leaguetool.stats;

/**
 * Wilson-Konfidenzintervall für eine Siegquote. Bei kleinen Stichproben ist die untere Grenze ein
 * vorsichtigeres Ranking-Kriterium als die rohe Winrate (2/2 Siege landen nicht vor 550/1000).
 */
public final class WilsonScore {

    /** z-Wert für ein 95-%-Intervall. */
    public static final double DEFAULT_Z = 1.96;

    private WilsonScore() {
    }

    /** Untere Grenze mit z = {@value #DEFAULT_Z}; 0 bei {@code games = 0}. */
    public static double lowerBound(int wins, int games) {
        return lowerBound(wins, games, DEFAULT_Z);
    }

    /** Obere Grenze mit z = {@value #DEFAULT_Z}; 0 bei {@code games = 0}. */
    public static double upperBound(int wins, int games) {
        return upperBound(wins, games, DEFAULT_Z);
    }

    /** Untere Grenze des Wilson-Intervalls; 0 bei {@code games = 0}. */
    public static double lowerBound(int wins, int games, double z) {
        return bound(wins, games, z, -1);
    }

    /** Obere Grenze des Wilson-Intervalls; 0 bei {@code games = 0}. */
    public static double upperBound(int wins, int games, double z) {
        return bound(wins, games, z, +1);
    }

    private static double bound(int wins, int games, double z, int sign) {
        if (wins < 0 || games < 0 || wins > games) {
            throw new IllegalArgumentException("Ungültige Werte: " + wins + " Siege bei " + games + " Spielen");
        }
        if (z < 0 || Double.isNaN(z)) {
            throw new IllegalArgumentException("z muss ≥ 0 sein: " + z);
        }
        if (games == 0) {
            return 0;
        }
        double n = games;
        double p = wins / n;
        double z2 = z * z;
        double center = p + z2 / (2 * n);
        double margin = z * Math.sqrt(p * (1 - p) / n + z2 / (4 * n * n));
        double value = (center + sign * margin) / (1 + z2 / n);
        return Math.min(1, Math.max(0, value));
    }
}
