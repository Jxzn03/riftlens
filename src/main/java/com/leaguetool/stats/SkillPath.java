package com.leaguetool.stats;

import com.leaguetool.model.AbilitySlot;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Skillung der ersten Level. Lolalytics liefert nur die ersten sechs Skill-Punkte; die folgenden Level ergeben sich aus
 * der Max-Reihenfolge und der Rangbegrenzung im Spiel.
 */
public final class SkillPath {

    public static final int LEVELS = 9;
    private static final int ULTIMATE_LEVEL = 6;
    private static final int MAX_RANK = 5;

    private SkillPath() {
    }

    /**
     * @param start    bekannte erste Skill-Punkte
     * @param maxOrder Reihenfolge, in der die Grundfähigkeiten maximiert werden
     * @return genau {@link #LEVELS} Einträge, oder leer, wenn die Daten dafür nicht reichen
     */
    public static List<AbilitySlot> firstLevels(List<AbilitySlot> start, List<AbilitySlot> maxOrder) {
        if (start.isEmpty() || start.size() > LEVELS || maxOrder.isEmpty()) {
            return List.of();
        }
        List<AbilitySlot> path = new ArrayList<>(start);
        Map<AbilitySlot, Integer> ranks = new EnumMap<>(AbilitySlot.class);
        start.forEach(slot -> ranks.merge(slot, 1, Integer::sum));
        for (int level = start.size() + 1; level <= LEVELS; level++) {
            AbilitySlot next = next(ranks, maxOrder, level);
            if (next == null) {
                return List.of();
            }
            path.add(next);
            ranks.merge(next, 1, Integer::sum);
        }
        return List.copyOf(path);
    }

    private static AbilitySlot next(Map<AbilitySlot, Integer> ranks, List<AbilitySlot> maxOrder, int level) {
        if (level >= ULTIMATE_LEVEL && !ranks.containsKey(AbilitySlot.R)) {
            return AbilitySlot.R;
        }
        // Grundfähigkeiten erreichen Rang n frühestens auf Level 2n − 1.
        int limit = Math.min(MAX_RANK, (level + 1) / 2);
        return maxOrder.stream().filter(slot -> ranks.getOrDefault(slot, 0) < limit).findFirst().orElse(null);
    }
}
