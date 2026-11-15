package com.leaguetool.draft;

import com.leaguetool.draft.DraftDataset.Pick;
import com.leaguetool.model.Role;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Bestimmt die Rollen eines Teams, solange sie nicht feststehen: Alle Zuteilungen werden nach der Spielhäufigkeit der
 * Champions auf ihren Rollen gewichtet; die wahrscheinlichste gilt.
 */
public final class RolePredictor {

    /** @param forcedRole fest gewählte Rolle oder {@code null} */
    public record Slot(int champion, Role forcedRole) {
    }

    /**
     * @param roles         Champion → Rolle der wahrscheinlichsten Zuteilung
     * @param probabilities Champion → Wahrscheinlichkeit je Rolle über alle Zuteilungen
     */
    public record Prediction(Map<Integer, Role> roles, Map<Integer, Map<Role, Double>> probabilities) {
    }

    private RolePredictor() {
    }

    /**
     * @param games Spiele eines Champions auf einer Rolle
     * @throws IllegalArgumentException wenn zwei Champions fest dieselbe Rolle haben
     */
    public static Prediction predict(List<Slot> slots, ToDoubleFunction<Pick> games) {
        Map<Role, Integer> fixed = new EnumMap<>(Role.class);
        List<Integer> open = new ArrayList<>();
        Map<Integer, Map<Role, Double>> shares = new HashMap<>();
        for (Slot slot : slots) {
            if (slot.forcedRole() == null) {
                open.add(slot.champion());
                shares.put(slot.champion(), shares(slot.champion(), games));
            } else if (fixed.putIfAbsent(slot.forcedRole(), slot.champion()) != null) {
                throw new IllegalArgumentException("Die Rolle " + slot.forcedRole().label() + " ist doppelt vergeben.");
            }
        }
        Search search = new Search(open, shares);
        search.assign(0, new EnumMap<>(fixed), 1.0);
        return search.result(slots);
    }

    /** Eine erfundene Partie je Rolle: Champions ohne Daten bleiben möglich, und das Produkt wird nie 0. */
    private static Map<Role, Double> shares(int champion, ToDoubleFunction<Pick> games) {
        double total = 0;
        Map<Role, Double> counts = new EnumMap<>(Role.class);
        for (Role role : Role.values()) {
            double count = games.applyAsDouble(new Pick(champion, role)) + 1;
            counts.put(role, count);
            total += count;
        }
        double divisor = total;
        counts.replaceAll((role, count) -> count / divisor);
        return counts;
    }

    private static final class Search {

        private final List<Integer> open;
        private final Map<Integer, Map<Role, Double>> shares;
        private final Map<Integer, Map<Role, Double>> weights = new HashMap<>();
        private double totalWeight;
        private double bestWeight = -1;
        private Map<Role, Integer> best = Map.of();

        Search(List<Integer> open, Map<Integer, Map<Role, Double>> shares) {
            this.open = open;
            this.shares = shares;
        }

        void assign(int index, Map<Role, Integer> team, double weight) {
            if (index == open.size()) {
                record(team, weight);
                return;
            }
            int champion = open.get(index);
            for (Role role : Role.values()) {
                if (!team.containsKey(role)) {
                    team.put(role, champion);
                    assign(index + 1, team, weight * shares.get(champion).get(role));
                    team.remove(role);
                }
            }
        }

        private void record(Map<Role, Integer> team, double weight) {
            totalWeight += weight;
            if (weight > bestWeight) {
                bestWeight = weight;
                best = new EnumMap<>(team);
            }
            team.forEach((role, champion) -> weights.computeIfAbsent(champion, c -> new EnumMap<>(Role.class))
                    .merge(role, weight, Double::sum));
        }

        Prediction result(List<Slot> slots) {
            Map<Integer, Role> roles = new HashMap<>();
            best.forEach((role, champion) -> roles.put(champion, role));
            Map<Integer, Map<Role, Double>> probabilities = new HashMap<>();
            for (Slot slot : slots) {
                Map<Role, Double> normalized = new EnumMap<>(Role.class);
                weights.getOrDefault(slot.champion(), Map.of())
                        .forEach((role, weight) -> normalized.put(role, weight / totalWeight));
                probabilities.put(slot.champion(), normalized);
            }
            return new Prediction(roles, probabilities);
        }
    }
}
