package com.leaguetool.draft;

import com.leaguetool.model.Role;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Zahlen, aus denen ein Draft bewertet wird.
 *
 * <p>Paarungen liegen je Quelle vor: Die Seite eines bereits gewählten Champions enthält seine Werte gegen und mit
 * allen anderen. Darum reichen die Seiten der gewählten Champions, um jeden möglichen nächsten Pick zu bewerten.
 */
public final class DraftDataset {

    /** Ein Champion auf einer Rolle. */
    public record Pick(int champion, Role role) {
    }

    public record Stat(double wins, double games) {

        public static final Stat NONE = new Stat(0, 0);

        public double winRate(double fallback) {
            return games > 0 ? wins / games : fallback;
        }
    }

    private final double selectedAverage;
    private final double fullAverage;
    private final Map<Pick, Stat> selected;
    private final Map<Pick, Stat> full;
    private final Map<Pick, Map<Pick, Stat>> matchups;
    private final Map<Pick, Map<Pick, Stat>> synergies;
    private final Map<Role, Double> roleGames = new EnumMap<>(Role.class);
    private final Map<Integer, Double> championGames = new HashMap<>();

    /**
     * @param selectedAverage Durchschnitts-Winrate des gewählten Zeitraums
     * @param fullAverage     Durchschnitts-Winrate der letzten 30 Tage
     * @param selected        Werte je Champion und Rolle im gewählten Zeitraum
     * @param full            Werte je Champion und Rolle der letzten 30 Tage
     * @param matchups        Quelle → Gegner → Werte der Quelle gegen diesen Gegner
     * @param synergies       Quelle → Mitspieler → Werte der Quelle mit diesem Mitspieler
     */
    public DraftDataset(double selectedAverage, double fullAverage, Map<Pick, Stat> selected, Map<Pick, Stat> full,
                        Map<Pick, Map<Pick, Stat>> matchups, Map<Pick, Map<Pick, Stat>> synergies) {
        this.selectedAverage = selectedAverage;
        this.fullAverage = fullAverage;
        this.selected = Map.copyOf(selected);
        this.full = Map.copyOf(full);
        this.matchups = Map.copyOf(matchups);
        this.synergies = Map.copyOf(synergies);
        this.full.forEach((pick, stat) -> {
            roleGames.merge(pick.role(), stat.games(), Double::sum);
            championGames.merge(pick.champion(), stat.games(), Double::sum);
        });
    }

    /**
     * Pickrate auf der Rolle in den letzten 30 Tagen (0..1) wie bei Lolalytics: Anteil der Spiele, in denen der Champion
     * diese Rolle spielt. Je Spiel gibt es zwei Picks pro Rolle, die Pickrates einer Rolle ergeben also 200 %.
     */
    public double pickRate(Pick pick) {
        double total = roleGames.getOrDefault(pick.role(), 0.0);
        return total > 0 ? full(pick).games() * 2 / total : 0;
    }

    /** Anteil der Spiele des Champions (letzte 30 Tage), die er auf dieser Rolle spielt (0..1). */
    public double laneShare(Pick pick) {
        double total = championGames.getOrDefault(pick.champion(), 0.0);
        return total > 0 ? full(pick).games() / total : 0;
    }

    public double selectedAverage() {
        return selectedAverage;
    }

    public double fullAverage() {
        return fullAverage;
    }

    public Stat selected(Pick pick) {
        return selected.getOrDefault(pick, Stat.NONE);
    }

    public Stat full(Pick pick) {
        return full.getOrDefault(pick, Stat.NONE);
    }

    public Stat matchup(Pick source, Pick opponent) {
        return matchups.getOrDefault(source, Map.of()).getOrDefault(opponent, Stat.NONE);
    }

    public Stat synergy(Pick source, Pick teammate) {
        return synergies.getOrDefault(source, Map.of()).getOrDefault(teammate, Stat.NONE);
    }

    /** Werte je Champion und Rolle der letzten 30 Tage. */
    Map<Pick, Stat> fullStats() {
        return full;
    }

    /** Werte je Champion und Rolle im gewählten Zeitraum. */
    Map<Pick, Stat> selectedStats() {
        return selected;
    }

    /** Quelle → Gegner → Werte der Quelle gegen diesen Gegner. */
    Map<Pick, Map<Pick, Stat>> matchups() {
        return matchups;
    }

    /** Quelle → Mitspieler → Werte der Quelle mit diesem Mitspieler. */
    Map<Pick, Map<Pick, Stat>> synergies() {
        return synergies;
    }

    /** Alle Champions mit Daten auf mindestens einer Rolle. */
    public Set<Integer> champions() {
        Set<Integer> champions = new HashSet<>();
        selected.keySet().forEach(p -> champions.add(p.champion()));
        full.keySet().forEach(p -> champions.add(p.champion()));
        return champions;
    }
}
