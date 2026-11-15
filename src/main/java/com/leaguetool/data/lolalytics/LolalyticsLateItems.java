package com.leaguetool.data.lolalytics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fasst die Listen mehrerer Itemplätze je Item zusammen: Die Spiele werden addiert, die Winrate wird nach Spielen
 * gewichtet. Ein Item steht in einem Spiel auf höchstens einem Platz, die Summe zählt also jedes Spiel einmal und die
 * gewichtete Winrate ist die Winrate genau dieser Spiele.
 */
public final class LolalyticsLateItems {

    private LolalyticsLateItems() {
    }

    /** @param slots Spiele je Itemplatz, nur Plätze mit Spielen, in der Reihenfolge der Plätze */
    public record Line(int item, double winRate, double pickRate, int games, List<SlotGames> slots) {

        public Line {
            slots = List.copyOf(slots);
        }
    }

    public record SlotGames(int slot, int games) {
    }

    /**
     * @param excluded      Items, die nicht erscheinen sollen (schon im Core-Build)
     * @param minGames      Mindestzahl an Spielen über alle Plätze zusammen
     * @param limit         höchstens so viele Zeilen
     * @param championGames Spiele des Champions, Basis der Pickrate
     * @return häufigste Items zuerst
     */
    public static List<Line> of(List<LolalyticsBuild.ItemSlot> slots, Collection<Integer> excluded, int minGames, int limit,
                                int championGames) {
        Map<Integer, Totals> byItem = new LinkedHashMap<>();
        for (LolalyticsBuild.ItemSlot slot : slots) {
            for (LolalyticsBuild.Entry entry : slot.entries()) {
                if (entry.key().matches("\\d+") && entry.games() > 0 && !excluded.contains(Integer.valueOf(entry.key()))) {
                    byItem.computeIfAbsent(Integer.valueOf(entry.key()), id -> new Totals()).add(slot.slot(), entry);
                }
            }
        }
        return byItem.entrySet().stream()
                .filter(e -> e.getValue().games >= minGames)
                .map(e -> e.getValue().line(e.getKey(), championGames))
                .sorted(Comparator.comparingInt(Line::games).reversed().thenComparingInt(Line::item))
                .limit(limit)
                .toList();
    }

    private static final class Totals {
        private int games;
        private double wins;
        private final List<SlotGames> perSlot = new ArrayList<>();

        void add(int slot, LolalyticsBuild.Entry entry) {
            games += entry.games();
            wins += entry.winRate() * entry.games();
            perSlot.add(new SlotGames(slot, entry.games()));
        }

        Line line(int item, int championGames) {
            return new Line(item, wins / games, championGames == 0 ? 0 : (double) games / championGames, games, perSlot);
        }
    }
}
