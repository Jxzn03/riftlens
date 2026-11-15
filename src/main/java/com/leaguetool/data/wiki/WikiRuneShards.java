package com.leaguetool.data.wiki;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest die Shard-Tabelle der Wiki-Seite „Rune“ (Abschnitt {@code === Shards ===}): je Reihe drei Zellen mit
 * {@code [[File:Rune shard <Name>.png…]]<br />Wert}. Welcher Shard gemeint ist, sagt der Dateiname; der erste
 * Zahlenwert der Zelle ist sein Wert (Health Scaling: Leben je Level).
 */
public final class WikiRuneShards {

    /**
     * @param values Wert je Shard-ID (wie {@code perks.statPerks})
     * @param rows   Shard-IDs je Reihe (Offensive, Flex, Defensive)
     */
    public record Shards(Map<Integer, Double> values, List<List<Integer>> rows) {
        public Shards {
            values = Map.copyOf(values);
            rows = rows.stream().map(List::copyOf).toList();
        }
    }

    /** Dateinamen der Wiki-Icons → Shard-ID. */
    private static final Map<String, Integer> IDS = Map.of(
            "Adaptive Force", 5008, "Attack Speed", 5005, "Cooldown Reduction", 5007, "Ability Haste", 5007,
            "Movement Speed", 5010, "Health Scaling", 5001, "Health", 5011, "Tenacity and Slow Resist", 5013);

    private static final Pattern SECTION = Pattern.compile("(?s)=== *Shards *===(.*?)\\n\\|\\}");
    private static final Pattern ROW = Pattern.compile("(?s)\\|-\\s*\\n!.*?\\n(.*?)(?=\\n\\|-|\\z)");
    private static final Pattern CELL = Pattern.compile("\\[\\[File:Rune shard ([^.|\\]]+)\\.png[^\\]]*]](?:<br\\s*/?>)?([^\\n]*)");
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    private WikiRuneShards() {
    }

    /** @throws IOException wenn die Tabelle fehlt oder nicht drei vollständige Reihen hat */
    public static Shards parse(String wikitext) throws IOException {
        Matcher section = SECTION.matcher(wikitext);
        if (!section.find()) {
            throw new IOException("Wiki-Seite Rune: Shard-Tabelle nicht gefunden");
        }
        Map<Integer, Double> values = new LinkedHashMap<>();
        List<List<Integer>> rows = new ArrayList<>();
        Matcher row = ROW.matcher(section.group(1));
        while (row.find()) {
            List<Integer> ids = new ArrayList<>();
            Matcher cell = CELL.matcher(row.group(1));
            while (cell.find()) {
                Integer id = IDS.get(cell.group(1).trim());
                Matcher number = NUMBER.matcher(cell.group(2));
                if (id == null || !number.find()) {
                    throw new IOException("Wiki-Seite Rune: unbekannter Shard „" + cell.group(1) + "“");
                }
                ids.add(id);
                values.put(id, Double.parseDouble(number.group(1)));
            }
            if (!ids.isEmpty()) {
                rows.add(ids);
            }
        }
        if (rows.size() != 3 || rows.stream().anyMatch(r -> r.size() != 3)) {
            throw new IOException("Wiki-Seite Rune: Shard-Tabelle hat nicht 3 × 3 Einträge");
        }
        return new Shards(values, rows);
    }
}
