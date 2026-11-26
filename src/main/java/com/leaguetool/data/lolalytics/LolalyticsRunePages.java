package com.leaguetool.data.lolalytics;

import com.leaguetool.model.Rune;
import com.leaguetool.stats.StatLine;
import com.leaguetool.stats.StatRanking;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/**
 * Schätzt Winrate und Spiele einer kompletten Runenseite. Lolalytics liefert für die beiden Seiten (meistgespielt,
 * erfolgreichste) nur Winrate und Spiele des Keystones, sodass Seiten mit gleichem Keystone sonst gleich aussehen.
 * Die Schätzung nimmt je Rune (Keystone, drei Reihen, zwei Sekundär-Runen) und je Shard (drei Reihen) die Abweichung der
 * Winrate von der des Champions in Log-Odds und schlägt den gewichteten Mittelwert auf die Champion-Winrate auf
 * (Keystone 1,5, Runen 1, Shards je 1/3). Der Mittelwert statt der Summe, weil die Runen einer Seite meist zusammen
 * gewählt werden: Eine Summe zählte denselben Effekt mehrfach. Die Spielzahl ist die kleinste der Runen und Shards
 * (Obergrenze für die Seite).
 */
public final class LolalyticsRunePages {

    /** Kleinster Abstand von 0 und 1, damit Log-Odds endlich bleiben. */
    private static final double EPSILON = 1e-4;
    /**
     * Gewichte im Mittelwert (Nutzerwunsch): Der Keystone prägt die Spielweise am stärksten, die drei Shards zusammen
     * zählen so viel wie eine Rune. Anteile bei voller Seite: Keystone 20 %, fünf Runen 67 %, drei Shards 13 %.
     */
    static final double KEYSTONE_WEIGHT = 1.5;
    static final double RUNE_WEIGHT = 1.0;
    static final double SHARD_WEIGHT = 1.0 / 3;

    /** {@link Swap#shardSlot} einer Rune. */
    public static final int NO_SHARD = -1;

    private LolalyticsRunePages() {
    }

    /** Winrate und Spiele einer Rune (oder eines Shards) an ihrer Stelle der Seite, mit Gewicht in der Schätzung. */
    public record RuneStat(int id, double winRate, int games, double weight) {
    }

    /**
     * @param estimated {@code false}, wenn eine Rune ohne Werte ist; dann gelten Winrate und Spiele des Keystones laut
     *                  Lolalytics
     * @param runes     Werte der Runen mit Daten, in Seitenreihenfolge (Hauptbaum, Sekundärbaum, Shards)
     */
    public record Estimate(double winRate, int games, boolean estimated, List<RuneStat> runes) {

        public Estimate {
            runes = List.copyOf(runes);
        }
    }

    /**
     * Empfohlene Variante der meistgespielten Seite.
     *
     * @param swaps   ersetzte Runen und Shards, in Seitenreihenfolge
     * @param delta    geschätzte Winrate der Variante minus die der meistgespielten Seite (Anteil, nicht Punkte)
     */
    public record Recommendation(LolalyticsBuild.RunePage page, List<Swap> swaps, double delta) {

        public Recommendation {
            swaps = List.copyOf(swaps);
        }
    }

    /**
     * Neue Wahl {@code id} statt {@code replaces} in derselben Reihe, mit den Winrates beider an dieser Stelle.
     *
     * @param shardSlot Stelle in der Shard-Liste (0 Offensive, 1 Flex, 2 Defensive) oder {@link #NO_SHARD} bei einer Rune;
     *                  nötig, weil derselbe Shard in zwei Reihen stehen kann (5008, 5001)
     */
    public record Swap(int id, int replaces, double winRate, double replacedWinRate, int shardSlot) {
    }

    public static Estimate of(LolalyticsBuild build, LolalyticsBuild.RunePage page) {
        List<RuneStat> runes = new ArrayList<>();
        boolean complete = !page.primary().isEmpty() && !page.sub().isEmpty();
        complete &= collect(page.primary(), build.runes(), runes, KEYSTONE_WEIGHT, RUNE_WEIGHT);
        complete &= collect(page.sub(), build.subRunes(), runes, RUNE_WEIGHT, RUNE_WEIGHT);
        for (int row = 0; row < Math.min(page.shards().size(), LolalyticsBuild.SHARD_ROWS); row++) {
            // Fehlt ein Shard in den Daten, bleibt die Schätzung aus den übrigen Werten gültig.
            collect(List.of(page.shards().get(row)), build.shardRow(row), runes, SHARD_WEIGHT, SHARD_WEIGHT);
        }
        if (!complete || build.winRate() <= 0 || build.winRate() >= 1) {
            return new Estimate(page.winRate(), page.games(), false, runes);
        }
        double base = logit(build.winRate());
        double weights = runes.stream().mapToDouble(RuneStat::weight).sum();
        double shift = runes.stream().mapToDouble(r -> r.weight() * (logit(r.winRate()) - base)).sum() / weights;
        int games = runes.stream().mapToInt(RuneStat::games).min().orElse(page.games());
        return new Estimate(1 / (1 + Math.exp(-(base + shift))), games, true, runes);
    }

    /**
     * Variante der meistgespielten Seite: Keystone und Bäume bleiben, je Reihe (Hauptbaum Reihe 1–3, die beiden Reihen
     * im Sekundärbaum) und je Shard-Reihe wird nur ersetzt, wenn eine andere Wahl derselben Reihe in der Rangliste
     * (Bayes-Untergrenze ab {@code minGames}) vorn liegt. Leer, wenn
     * nichts ersetzt wird, die Variante einer gelieferten Seite gleicht oder eine Rune unbekannt ist.
     *
     * @param runeInfo Rune zu einer ID (Baum und Reihe)
     */
    public static Optional<Recommendation> recommend(LolalyticsBuild build, IntFunction<Optional<Rune>> runeInfo,
                                                     int minGames) {
        Optional<LolalyticsBuild.RunePage> base = build.runePages().stream()
                .filter(LolalyticsBuild.RunePage::mostPlayed).findFirst();
        if (base.isEmpty() || base.get().primary().isEmpty()) {
            return Optional.empty();
        }
        LolalyticsBuild.RunePage page = base.get();
        List<Swap> swaps = new ArrayList<>();
        List<Integer> primary = new ArrayList<>(page.primary());
        List<Integer> sub = new ArrayList<>(page.sub());
        for (int i = 1; i < primary.size(); i++) {
            Optional<Integer> better = betterInRow(primary.get(i), build.runes(), runeInfo, minGames);
            if (better.isEmpty() && runeInfo.apply(primary.get(i)).isEmpty()) {
                return Optional.empty();
            }
            int index = i;
            better.ifPresent(id -> swaps.add(swap(build.runes(), id, primary.set(index, id), NO_SHARD)));
        }
        for (int i = 0; i < sub.size(); i++) {
            Optional<Integer> better = betterInRow(sub.get(i), build.subRunes(), runeInfo, minGames);
            if (better.isEmpty() && runeInfo.apply(sub.get(i)).isEmpty()) {
                return Optional.empty();
            }
            int index = i;
            better.ifPresent(id -> swaps.add(swap(build.subRunes(), id, sub.set(index, id), NO_SHARD)));
        }
        List<Integer> shards = new ArrayList<>(page.shards());
        for (int row = 0; row < Math.min(shards.size(), LolalyticsBuild.SHARD_ROWS); row++) {
            Map<Integer, LolalyticsBuild.Entry> stats = build.shardRow(row);
            int current = shards.get(row);
            int slot = row;
            best(stats, id -> true, minGames).filter(id -> id != current)
                    .ifPresent(id -> swaps.add(swap(stats, id, shards.set(slot, id), slot)));
        }
        LolalyticsBuild.RunePage variant = new LolalyticsBuild.RunePage(primary, sub, shards, page.winRate(),
                page.games(), false);
        if (swaps.isEmpty() || build.runePages().stream().anyMatch(p -> samePage(p, variant))) {
            return Optional.empty();
        }
        double delta = of(build, variant).winRate() - of(build, page).winRate();
        return Optional.of(new Recommendation(variant, swaps, delta));
    }

    private static Swap swap(Map<Integer, LolalyticsBuild.Entry> stats, int id, int replaces, int shardSlot) {
        LolalyticsBuild.Entry before = stats.get(replaces);
        return new Swap(id, replaces, stats.get(id).winRate(), before == null ? Double.NaN : before.winRate(), shardSlot);
    }

    /** Beste andere Rune derselben Reihe desselben Baums, falls sie in der Rangliste vor {@code current} liegt. */
    private static Optional<Integer> betterInRow(int current, Map<Integer, LolalyticsBuild.Entry> stats,
                                                 IntFunction<Optional<Rune>> runeInfo, int minGames) {
        Optional<Rune> rune = runeInfo.apply(current);
        if (rune.isEmpty()) {
            return Optional.empty();
        }
        int tree = rune.get().treeId();
        int slot = rune.get().slot();
        IntPredicate sameRow = id -> runeInfo.apply(id).filter(r -> r.treeId() == tree && r.slot() == slot).isPresent();
        return best(stats, sameRow, minGames).filter(id -> id != current);
    }

    private static Optional<Integer> best(Map<Integer, LolalyticsBuild.Entry> stats, IntPredicate include, int minGames) {
        List<StatLine<Integer>> lines = new ArrayList<>();
        stats.forEach((id, e) -> {
            if (include.test(id) && e.games() > 0) {
                lines.add(new StatLine<>(id, e.games(), (int) Math.min(e.games(), Math.round(e.games() * e.winRate())),
                        e.pickRate()));
            }
        });
        return StatRanking.of(lines, minGames).topBest().map(StatLine::key);
    }

    /** Gleiche Seite; die Reihenfolge der Sekundär-Runen zählt nicht (Riot garantiert sie nicht). */
    private static boolean samePage(LolalyticsBuild.RunePage a, LolalyticsBuild.RunePage b) {
        return a.primary().equals(b.primary()) && a.shards().equals(b.shards())
                && new HashSet<>(a.sub()).equals(new HashSet<>(b.sub()));
    }

    /** @return {@code false}, wenn eine Rune keine Werte hat */
    /** @param firstWeight Gewicht des ersten Eintrags (Keystone im Hauptbaum), {@code weight} das der übrigen */
    private static boolean collect(List<Integer> ids, Map<Integer, LolalyticsBuild.Entry> stats, List<RuneStat> out,
                                   double firstWeight, double weight) {
        boolean complete = true;
        for (int i = 0; i < ids.size(); i++) {
            int id = ids.get(i);
            LolalyticsBuild.Entry e = stats.get(id);
            if (e == null || e.games() <= 0) {
                complete = false;
            } else {
                out.add(new RuneStat(id, e.winRate(), e.games(), i == 0 ? firstWeight : weight));
            }
        }
        return complete;
    }

    private static double logit(double p) {
        double clamped = Math.min(1 - EPSILON, Math.max(EPSILON, p));
        return Math.log(clamped / (1 - clamped));
    }
}
