package com.leaguetool.data.lolalytics;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Matchups eines Champions gegen die Gegner einer Lane.
 *
 * @param lane     Lane der Gegner, wie in {@link LolalyticsPairs#byLane()}
 * @param minGames Mindestzahl an Spielen je Matchup
 * @param strong   beste Winrates, ohne Einfluss der Bewertung
 * @param weak     schlechteste Winrates, ohne Einfluss der Bewertung
 * @param delta    alle Matchups mit genug Spielen, beste Bewertung zuerst
 */
public record LolalyticsCounters(String lane, int minGames, List<Line> strong, List<Line> weak, List<Line> delta) {

    static final int MAX_LISTED = 8;
    static final double DELTA1_WEIGHT = 1;
    static final double DELTA2_WEIGHT = 2;
    /** Spiele, bei denen die Bewertung nur noch halb zählt. */
    static final double GAMES_DAMPING = 500;

    public LolalyticsCounters {
        strong = List.copyOf(strong);
        weak = List.copyOf(weak);
        delta = List.copyOf(delta);
    }

    /**
     * @param champion Riot-Champion-Key des Gegners
     * @param rating   eigene Bewertung (Prozentpunkte/100); {@code null}, wenn ein Delta-Wert fehlt
     */
    public record Line(int champion, double winRate, int games, Double delta1, Double delta2, Double rating) {
    }

    public static LolalyticsCounters of(LolalyticsPairs matchups, String lane, int minGames) {
        List<Line> lines = matchups.byLane().getOrDefault(lane, Map.of()).entrySet().stream()
                .filter(e -> e.getValue().games() >= minGames)
                .map(e -> line(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt(Line::champion))
                .toList();
        // Bei wenigen Gegnern teilen sich beide Listen die Zeilen, damit niemand zugleich Strong und Weak ist.
        int strongCount = Math.min(MAX_LISTED, (lines.size() + 1) / 2);
        int weakCount = Math.min(MAX_LISTED, lines.size() / 2);
        Comparator<Line> byWinRate = Comparator.comparingDouble(Line::winRate);
        return new LolalyticsCounters(lane, minGames,
                lines.stream().sorted(byWinRate.reversed()).limit(strongCount).toList(),
                lines.stream().sorted(byWinRate).limit(weakCount).toList(),
                lines.stream().sorted(Comparator.comparing(Line::rating, Comparator.nullsLast(Comparator.reverseOrder()))).toList());
    }

    private static Line line(int champion, LolalyticsPairs.Stat stat) {
        return new Line(champion, stat.winRate(), stat.games(), stat.delta1(), stat.delta2(), rating(stat));
    }

    /** Delta 2 rechnet die allgemeine Stärke beider Champions heraus und zählt deshalb doppelt. */
    private static Double rating(LolalyticsPairs.Stat stat) {
        if (stat.delta1() == null || stat.delta2() == null) {
            return null;
        }
        double weighted = (DELTA1_WEIGHT * stat.delta1() + DELTA2_WEIGHT * stat.delta2()) / (DELTA1_WEIGHT + DELTA2_WEIGHT);
        return weighted * stat.games() / (stat.games() + GAMES_DAMPING);
    }
}
