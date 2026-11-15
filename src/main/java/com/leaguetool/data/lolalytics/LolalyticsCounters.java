package com.leaguetool.data.lolalytics;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Counters eines Champions aus Sicht des Gegners: Er hat den Champion gepickt, gesucht sind die besten Picks dagegen.
 *
 * <p>Lolalytics liefert die Matchups aus Sicht des Champions. Jede Zeile wird darum gespiegelt: Winrate des Picks
 * gegen den Champion ({@code 2 × Tier-Durchschnitt − Matchup-Winrate}; beide Seiten eines Matchups ergeben zusammen das
 * Doppelte des Tier-Durchschnitts), Delta 1 aus Sicht des Picks, Delta 2 und Bewertung mit umgekehrtem Vorzeichen.
 * Die Pick-WR ist schon die Sicht des Gegners.
 *
 * @param lane     Lane der Picks, wie in {@link LolalyticsPairs#byLane()}
 * @param minGames Mindestzahl an Spielen je Matchup
 * @param best     beste Picks gegen den Champion (höchste Winrate des Picks), ohne Einfluss der Bewertung
 * @param worst    schlechteste Picks gegen den Champion, ohne Einfluss der Bewertung
 * @param delta    alle Picks mit genug Spielen, stärkster Counter (Bewertung) zuerst
 */
public record LolalyticsCounters(String lane, int minGames, List<Line> best, List<Line> worst, List<Line> delta) {

    static final int MAX_LISTED = 8;
    /**
     * Spiele, bei denen die Bewertung nur noch halb zählt: 0,25 / τ² mit der aus den Daten geschätzten Streuung der
     * Matchup-Effekte τ = 1,89 Prozentpunkte.
     */
    static final double GAMES_DAMPING = 700;
    /** Tier-Durchschnitt, wenn kein Matchup Delta-Werte hat. */
    private static final double DEFAULT_TIER_AVERAGE = 0.5;

    public LolalyticsCounters {
        best = List.copyOf(best);
        worst = List.copyOf(worst);
        delta = List.copyOf(delta);
    }

    /**
     * Ein Pick gegen den Champion, alle Werte aus Sicht des Picks.
     *
     * @param champion Riot-Champion-Key des Picks
     * @param winRate  Winrate des Picks gegen den Champion (0..1)
     * @param delta1   Winrate des Picks minus (1 − Winrate des Champions); {@code null}, wenn Lolalytics es nicht liefert
     * @param delta2   Matchup-Effekt aus Sicht des Picks; {@code null}, wenn Lolalytics es nicht liefert
     * @param rating   eigene Bewertung (Prozentpunkte/100), positiv = Counter; {@code null}, wenn Delta 2 fehlt
     * @param pick     geschätzte Winrate des Picks gegen den Champion inklusive seiner Stärke (0..1); {@code null},
     *                 wenn ein Delta fehlt
     */
    public record Line(int champion, double winRate, int games, Double delta1, Double delta2, Double rating, Double pick) {
    }

    /** @param championWinRate Winrate des Champions, dem die Matchups gehören (0..1) */
    public static LolalyticsCounters of(LolalyticsPairs matchups, String lane, int minGames, double championWinRate) {
        Map<Integer, LolalyticsPairs.Stat> stats = matchups.byLane().getOrDefault(lane, Map.of());
        double tierAverage = tierAverage(stats.values(), championWinRate);
        List<Line> lines = stats.entrySet().stream()
                .filter(e -> e.getValue().games() >= minGames)
                .map(e -> line(e.getKey(), e.getValue(), championWinRate, tierAverage))
                .sorted(Comparator.comparingInt(Line::champion))
                .toList();
        // Bei wenigen Picks teilen sich beide Listen die Zeilen, damit niemand zugleich gut und schlecht ist.
        int bestCount = Math.min(MAX_LISTED, (lines.size() + 1) / 2);
        int worstCount = Math.min(MAX_LISTED, lines.size() / 2);
        Comparator<Line> byWinRate = Comparator.comparingDouble(Line::winRate);
        return new LolalyticsCounters(lane, minGames,
                lines.stream().sorted(byWinRate.reversed()).limit(bestCount).toList(),
                lines.stream().sorted(byWinRate).limit(worstCount).toList(),
                lines.stream().sorted(Comparator.comparing(Line::rating, Comparator.nullsLast(Comparator.reverseOrder()))).toList());
    }

    /**
     * Tier-Durchschnitt aus {@code Delta 1 − Delta 2 = Winrate des Champions + Durchschnitt − 1}; je Champion konstant,
     * darum der Median über alle Matchups mit beiden Deltas.
     */
    static double tierAverage(java.util.Collection<LolalyticsPairs.Stat> stats, double championWinRate) {
        double[] values = stats.stream().filter(s -> s.delta1() != null && s.delta2() != null)
                .mapToDouble(s -> 1 + s.delta1() - s.delta2() - championWinRate).sorted().toArray();
        if (values.length == 0) {
            return DEFAULT_TIER_AVERAGE;
        }
        int mid = values.length / 2;
        return values.length % 2 == 1 ? values[mid] : (values[mid - 1] + values[mid]) / 2;
    }

    private static Line line(int champion, LolalyticsPairs.Stat stat, double championWinRate, double tierAverage) {
        double pickWinRate = 2 * tierAverage - stat.winRate();
        Double delta1 = stat.delta1() == null ? null : pickWinRate - (1 - championWinRate);
        Double delta2 = stat.delta2() == null ? null : -stat.delta2();
        Double rating = delta2 == null ? null : delta2 * stat.games() / (stat.games() + GAMES_DAMPING);
        return new Line(champion, pickWinRate, stat.games(), delta1, delta2, rating, pick(stat, championWinRate));
    }

    /**
     * Anders als die Bewertung behält der Wert die allgemeine Stärke des Picks: Gesucht ist, mit wem man gegen den
     * Champion am häufigsten gewinnt. Die Matchup-Winrate wird dafür zum Erwartungswert hin gedämpft und auf die Sicht
     * des Picks gespiegelt; der Durchschnitt folgt aus {@code Delta 1 − Delta 2 = Winrate + Durchschnitt − 1}.
     */
    private static Double pick(LolalyticsPairs.Stat stat, double championWinRate) {
        if (stat.delta1() == null || stat.delta2() == null) {
            return null;
        }
        double tierAverage = 1 + stat.delta1() - stat.delta2() - championWinRate;
        double dampedWinRate = stat.winRate() - stat.delta2() * GAMES_DAMPING / (stat.games() + GAMES_DAMPING);
        return 2 * tierAverage - dampedWinRate;
    }
}
