package com.leaguetool.data.lolalytics;

import com.leaguetool.stats.StatLine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Core-Builds nach Legendary-Pfad: Builds mit denselben Items (ohne Boots) in derselben Reihenfolge werden zu einem
 * Eintrag zusammengefasst, egal welche Boots wann gekauft wurden. Spiele und Pickrate werden addiert, die Winrate nach
 * Spielen gewichtet; angezeigt wird die häufigste konkrete Reihenfolge samt Boots.
 *
 * <p>Warum: Lolalytics trennt die Builds nach Boots und Kaufreihenfolge. Die Boots-Wahl hängt vor allem vom Gegner ab
 * (eigene Box) und zerlegt denselben Pfad in viele kleine Varianten, die dann die Rangliste füllen (z. B. Darius:
 * dreimal Ghostblade › Dead Man's Plate mit anderen Boots). Auf disjunkten Testspielen (lokaler Cache, Emerald+ gegen
 * die Spiele darunter und Platin) ist der beste Pfad gleich gut wie die beste Variante, deckt aber 53 % statt 35 % der
 * Spiele ab. Details: {@code docs/statistik-ranglisten.md}.
 */
public final class LolalyticsCoreBuilds {

    private LolalyticsCoreBuilds() {
    }

    /**
     * @param isBoots erkennt Boots-IDs
     * @return ein Eintrag je Legendary-Pfad, häufigste zuerst; Schlüssel in der häufigsten konkreten Reihenfolge
     */
    public static List<LolalyticsBuild.Entry> merge(List<LolalyticsBuild.Entry> builds, IntPredicate isBoots) {
        Map<List<Integer>, Totals> byPath = new LinkedHashMap<>();
        for (LolalyticsBuild.Entry e : builds) {
            List<Integer> ids = ids(e.key());
            if (ids != null) {
                byPath.computeIfAbsent(legendaries(ids, isBoots), k -> new Totals()).add(e);
            }
        }
        return byPath.values().stream().map(Totals::entry)
                .sorted(Comparator.comparingInt(LolalyticsBuild.Entry::games).reversed())
                .toList();
    }

    /**
     * Ordnet die besten Pfade so, dass zuerst der beste Pfad je erstem Item steht (in der Reihenfolge der Rangliste),
     * danach die übrigen. So zeigt der Core-Build verschiedene Starts (z. B. Ghostblade, Trinity Force, Stridebreaker)
     * statt Varianten desselben ersten Items. Der erste Eintrag bleibt der beste.
     */
    public static <K extends List<Integer>> List<StatLine<K>> byFirstItem(List<StatLine<K>> best, IntPredicate isBoots) {
        List<StatLine<K>> firsts = new ArrayList<>();
        List<StatLine<K>> rest = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (StatLine<K> line : best) {
            List<Integer> path = legendaries(line.key(), isBoots);
            if (!path.isEmpty() && seen.add(path.get(0))) {
                firsts.add(line);
            } else {
                rest.add(line);
            }
        }
        firsts.addAll(rest);
        return firsts;
    }

    /**
     * Reihenfolge für die Anzeige von {@code rows} Zeilen: Die Auswahl folgt {@link #byFirstItem} (verschiedene Starts),
     * der meistgespielte Pfad ist dabei, wenn er über der Mindest-Stichprobe liegt (statt der letzten Auswahl). Die
     * ausgewählten Zeilen stehen dann in der Reihenfolge der Rangliste ({@code best}), damit kein seltener Pfad mit
     * schwächerer Bewertung über einem besseren steht (z. B. Annie: Meistgespielt nicht hinter 1-%-Pfaden). Danach folgen
     * die übrigen in Auswahl-Reihenfolge.
     *
     * @param mostPlayed Schlüssel des meistgespielten Pfads oder {@code null}
     */
    public static <K extends List<Integer>> List<StatLine<K>> forDisplay(List<StatLine<K>> best, K mostPlayed,
                                                                       IntPredicate isBoots, int rows) {
        List<StatLine<K>> diverse = byFirstItem(best, isBoots);
        List<StatLine<K>> chosen = new ArrayList<>(diverse.subList(0, Math.min(rows, diverse.size())));
        StatLine<K> top = mostPlayed == null ? null
                : best.stream().filter(l -> l.key().equals(mostPlayed)).findFirst().orElse(null);
        if (top != null && !chosen.contains(top) && !chosen.isEmpty()) {
            chosen.set(chosen.size() - 1, top);
        }
        chosen.sort(java.util.Comparator.comparingInt(best::indexOf));
        List<StatLine<K>> ordered = new ArrayList<>(chosen);
        diverse.stream().filter(l -> !chosen.contains(l)).forEach(ordered::add);
        return ordered;
    }

    /**
     * Erstes ({@code index} 0) oder zweites ({@code index} 1) Item ohne Boots, aus den Core-Builds zusammengezählt.
     * Ersatz, wenn die Seite keine Liste für den Itemplatz liefert; zählt nur Spiele mit drei fertigen Items.
     *
     * @param championGames Spiele des Champions, Basis der Pickrate
     */
    public static List<LolalyticsBuild.Entry> itemAt(List<LolalyticsBuild.Entry> builds, IntPredicate isBoots, int index,
                                                     int championGames) {
        Map<Integer, double[]> byItem = new LinkedHashMap<>();   // Spiele, Siege
        for (LolalyticsBuild.Entry e : builds) {
            List<Integer> ids = ids(e.key());
            List<Integer> path = ids == null ? List.of() : legendaries(ids, isBoots);
            if (path.size() > index) {
                double[] t = byItem.computeIfAbsent(path.get(index), k -> new double[2]);
                t[0] += e.games();
                t[1] += e.games() * e.winRate();
            }
        }
        return byItem.entrySet().stream()
                .map(en -> new LolalyticsBuild.Entry(String.valueOf(en.getKey()), en.getValue()[1] / en.getValue()[0],
                        championGames == 0 ? 0 : en.getValue()[0] / championGames, (int) en.getValue()[0]))
                .sorted(Comparator.comparingInt(LolalyticsBuild.Entry::games).reversed())
                .toList();
    }

    /** Items ohne Boots in Kaufreihenfolge. */
    static List<Integer> legendaries(List<Integer> ids, IntPredicate isBoots) {
        return ids.stream().filter(id -> !isBoots.test(id)).toList();
    }

    /** {@code 3047_6692_6610} → IDs; {@code null} bei unbekanntem Format. */
    private static List<Integer> ids(String key) {
        try {
            return Arrays.stream(key.split("_")).map(Integer::valueOf).toList();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final class Totals {
        private LolalyticsBuild.Entry mostPlayed;
        private int games;
        private double wins;
        private double pickRate;

        void add(LolalyticsBuild.Entry e) {
            if (mostPlayed == null || e.games() > mostPlayed.games()) {
                mostPlayed = e;
            }
            games += e.games();
            wins += e.winRate() * e.games();
            pickRate += e.pickRate();
        }

        LolalyticsBuild.Entry entry() {
            double winRate = games == 0 ? mostPlayed.winRate() : wins / games;
            return new LolalyticsBuild.Entry(mostPlayed.key(), winRate, pickRate, games);
        }
    }
}
