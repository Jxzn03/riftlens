package com.leaguetool.stats;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Items, die man fast nur mit Vorsprung kauft. Ihre Winrate beschreibt die Spiele, in denen sie gekauft werden, nicht
 * das Item: Die Daten kennen den Spielstand beim Kauf nicht, darum lässt sich das nicht herausrechnen. Solche Einträge
 * bleiben sichtbar, stehen aber hinter den übrigen und werden markiert (Nutzerwunsch, z. B. Mejai's als „bestes“
 * zweites Item bei Mid-Champions).
 */
public final class SnowballItems {

    /** Mejai's Soulstealer. Erweiterung nur nach Rückfrage beim Nutzer. */
    public static final Set<Integer> IDS = Set.of(3041);

    private SnowballItems() {
    }

    public static boolean is(int itemId) {
        return IDS.contains(itemId);
    }

    /** Enthält mindestens ein Snowballing-Item. */
    public static boolean anyIn(Collection<Integer> itemIds) {
        return itemIds.stream().anyMatch(SnowballItems::is);
    }

    /** Dieselben Zeilen, Einträge mit Snowballing-Item ans Ende; die Reihenfolge innerhalb beider Gruppen bleibt. */
    public static <K> List<StatLine<K>> last(List<StatLine<K>> lines, Predicate<K> snowball) {
        List<StatLine<K>> normal = new ArrayList<>();
        List<StatLine<K>> marked = new ArrayList<>();
        for (StatLine<K> line : lines) {
            (snowball.test(line.key()) ? marked : normal).add(line);
        }
        normal.addAll(marked);
        return normal;
    }

    /**
     * Wie {@link #last}, aber die Snowballing-Einträge stehen auf den letzten der {@code shown} angezeigten Plätze statt
     * dahinter, damit sie sichtbar bleiben (ausgegraut). Gibt es weniger normale Einträge, folgen sie direkt auf diese.
     */
    public static <K> List<StatLine<K>> lastVisible(List<StatLine<K>> lines, Predicate<K> snowball, int shown) {
        List<StatLine<K>> ordered = last(lines, snowball);
        List<StatLine<K>> marked = ordered.stream().filter(l -> snowball.test(l.key())).limit(shown).toList();
        List<StatLine<K>> normal = ordered.stream().filter(l -> !snowball.test(l.key())).toList();
        int at = Math.max(0, Math.min(normal.size(), shown - marked.size()));
        List<StatLine<K>> result = new ArrayList<>(normal.subList(0, at));
        result.addAll(marked);
        result.addAll(normal.subList(at, normal.size()));
        ordered.stream().filter(l -> snowball.test(l.key()) && !marked.contains(l)).forEach(result::add);
        return result;
    }
}
