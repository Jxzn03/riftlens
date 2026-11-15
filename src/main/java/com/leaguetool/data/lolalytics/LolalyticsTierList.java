package com.leaguetool.data.lolalytics;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Geparste Tierliste von Lolalytics.
 *
 * @param patch      Patch in Kurzform, z. B. "16.22" (oder {@code null})
 * @param avgWinRate Durchschnittliche Winrate des Filters (0..1) oder NaN
 * @param champions  Zeilen
 */
public record LolalyticsTierList(String patch, double avgWinRate, List<LolalyticsChampion> champions) {

    public LolalyticsTierList {
        champions = List.copyOf(champions);
    }

    /** Normalisiert Namen für den Abgleich: nur Kleinbuchstaben und Ziffern. */
    public static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Zeilen nach Slug und nach normalisiertem Namen indiziert. */
    public Map<String, LolalyticsChampion> index() {
        return champions.stream().collect(Collectors.toMap(c -> normalize(c.slug()), Function.identity(),
                (a, b) -> a));
    }

    /** Sucht die Zeile zu einem der Namen (z. B. Data-Dragon-ID, Name, Name vor "&"). */
    public java.util.Optional<LolalyticsChampion> find(String... names) {
        Map<String, LolalyticsChampion> idx = index();
        for (String n : names) {
            LolalyticsChampion c = idx.get(normalize(n));
            if (c != null) {
                return java.util.Optional.of(c);
            }
        }
        for (String n : names) {
            String key = normalize(n);
            for (LolalyticsChampion c : champions) {
                if (normalize(c.name()).equals(key)) {
                    return java.util.Optional.of(c);
                }
            }
        }
        return java.util.Optional.empty();
    }
}
