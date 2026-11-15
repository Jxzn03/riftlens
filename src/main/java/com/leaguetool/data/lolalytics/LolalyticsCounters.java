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
    /**
     * Spiele, bei denen die Bewertung nur noch halb zählt: 0,25 / τ² mit der aus den Daten geschätzten Streuung der
     * Matchup-Effekte τ = 1,89 Prozentpunkte.
     */
    static final double GAMES_DAMPING = 700;

    public LolalyticsCounters {
        strong = List.copyOf(strong);
        weak = List.copyOf(weak);
        delta = List.copyOf(delta);
    }

    /**
     * @param champion Riot-Champion-Key des Gegners
     * @param rating   eigene Bewertung (Prozentpunkte/100); {@code null}, wenn Delta 2 fehlt
     * @param pick     geschätzte Winrate dieses Gegners gegen den Champion (0..1); {@code null}, wenn ein Delta fehlt
     */
    public record Line(int champion, double winRate, int games, Double delta1, Double delta2, Double rating, Double pick) {
    }

    /** @param championWinRate Winrate des Champions, dem die Matchups gehören (0..1) */
    public static LolalyticsCounters of(LolalyticsPairs matchups, String lane, int minGames, double championWinRate) {
        List<Line> lines = matchups.byLane().getOrDefault(lane, Map.of()).entrySet().stream()
                .filter(e -> e.getValue().games() >= minGames)
                .map(e -> line(e.getKey(), e.getValue(), championWinRate))
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

    private static Line line(int champion, LolalyticsPairs.Stat stat, double championWinRate) {
        return new Line(champion, stat.winRate(), stat.games(), stat.delta1(), stat.delta2(), rating(stat),
                pick(stat, championWinRate));
    }

    /**
     * Anders als die Bewertung behält der Wert die allgemeine Stärke des Gegners: Gesucht ist, mit wem man gegen den
     * Champion am häufigsten gewinnt. Die Matchup-Winrate wird dafür zum Erwartungswert hin gedämpft und auf die Sicht
     * des Gegners gespiegelt. Beide Seiten eines Matchups ergeben zusammen das Doppelte des Tier-Durchschnitts, nicht
     * 100 %; der Durchschnitt folgt aus {@code Delta 1 − Delta 2 = Winrate + Durchschnitt − 1}.
     */
    private static Double pick(LolalyticsPairs.Stat stat, double championWinRate) {
        if (stat.delta1() == null || stat.delta2() == null) {
            return null;
        }
        double tierAverage = 1 + stat.delta1() - stat.delta2() - championWinRate;
        double dampedWinRate = stat.winRate() - stat.delta2() * GAMES_DAMPING / (stat.games() + GAMES_DAMPING);
        return 2 * tierAverage - dampedWinRate;
    }

    /**
     * Posterior-Mittel des Matchup-Effekts. Delta 1 fließt nicht ein: Es unterscheidet sich je Champion nur um einen
     * festen Wert von Delta 2 und würde die Reihenfolge nicht ändern, die Werte aber verschieben.
     */
    private static Double rating(LolalyticsPairs.Stat stat) {
        return stat.delta2() == null ? null : stat.delta2() * stat.games() / (stat.games() + GAMES_DAMPING);
    }
}
