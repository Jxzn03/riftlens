package com.leaguetool.stats;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Eine Statistik-Zeile: Spiele und Siege zu einem Schlüssel (Champion, Item, Build, Rune …).
 *
 * @param key      Schlüssel, z. B. Item-ID oder Liste von Item-IDs
 * @param games    Anzahl Spiele (Teilnehmer) mit diesem Schlüssel
 * @param wins     davon gewonnen
 * @param pickRate Anteil an der jeweiligen Bezugsgröße (0..1), z. B. an den Spielen des Champions
 * @param <K>      Typ des Schlüssels; muss {@code equals}/{@code hashCode} sinnvoll implementieren
 */
public record StatLine<K>(K key, int games, int wins, double pickRate) implements WinStats {

    public StatLine {
        if (games < 0 || wins < 0 || wins > games) {
            throw new IllegalArgumentException("Ungültige Werte: " + wins + " Siege bei " + games + " Spielen");
        }
    }

    /** Sortierung nach Spielen absteigend, bei Gleichstand nach Wilson-Untergrenze absteigend. */
    public static <K> Comparator<StatLine<K>> byGames() {
        return Comparator.<StatLine<K>>comparingInt(StatLine::games).reversed()
                .thenComparing(Comparator.<StatLine<K>>comparingDouble(StatLine::wilsonLower).reversed());
    }

    /** Sortierung nach Wilson-Untergrenze absteigend, bei Gleichstand nach Spielen absteigend. */
    public static <K> Comparator<StatLine<K>> byWilsonLower() {
        return Comparator.<StatLine<K>>comparingDouble(StatLine::wilsonLower).reversed()
                .thenComparing(Comparator.<StatLine<K>>comparingInt(StatLine::games).reversed());
    }

    /**
     * Alle Zeilen, häufigste zuerst. Die Sortierung ist stabil: bei vollständigem Gleichstand bleibt die
     * Eingabe-Reihenfolge erhalten.
     */
    public static <K> List<StatLine<K>> mostFrequent(Collection<StatLine<K>> lines) {
        return lines.stream().sorted(byGames()).toList();
    }

    /**
     * Zeilen mit mindestens {@code minGames} Spielen, beste Wilson-Untergrenze zuerst; seltenere fallen weg.
     */
    public static <K> List<StatLine<K>> bestByWilson(Collection<StatLine<K>> lines, int minGames) {
        return lines.stream().filter(l -> l.games() >= minGames).sorted(byWilsonLower()).toList();
    }
}
