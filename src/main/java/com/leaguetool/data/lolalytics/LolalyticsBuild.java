package com.leaguetool.data.lolalytics;

import java.util.List;
import java.util.Map;

/**
 * Statistik eines Champions auf einer Lane laut Lolalytics. Raten sind Anteile (0..1).
 *
 * @param starters    Schlüssel wie {@code 1056_2003_2003}
 * @param coreBuilds  meistgebaute drei Items inklusive Boots, Schlüssel wie {@code 3118_3020_4645}
 * @param boots       Schlüssel ist die Item-ID
 * @param runes       Runen-ID → Eintrag als Rune im Hauptbaum
 * @param subRunes    Runen-ID → Eintrag als Rune im Sekundärbaum (ohne Keystones)
 * @param shardRows   je Shard-Reihe (0 Offensive, 1 Flex, 2 Defensive) Shard-ID → Eintrag
 * @param runePages   die meistgespielte und die erfolgreichste Runenseite; Winrate und Spiele sind die des Keystones
 * @param skillOrders Max-Reihenfolge, Schlüssel wie {@code QWE}
 * @param skillStarts erste sechs Skill-Punkte, Schlüssel wie {@code 213114} (1 = Q … 4 = R)
 * @param spells      Schlüssel wie {@code 4_14}
 * @param matchups    Winrates gegen jeden Gegner auf jeder Lane
 * @param damage      durchschnittlicher Schaden pro Spiel
 * @param gameLength  Spiele und Siege je Spieldauer-Abschnitt, von kurz nach lang; leer, wenn die Seite keine liefert
 * @param itemSlots   Items je Itemplatz 1–5 (ohne Boots); Schlüssel ist die Item-ID, leer, wenn die Seite (oder ein älterer
 *                    Cache-Eintrag) keine liefert
 * @param skillPaths  komplette Skillung der ersten 15 Level, Schlüssel wie {@code 132114131343322} (1 = Q … 4 = R);
 *                    leer bei älteren Cache-Einträgen
 */
public record LolalyticsBuild(int games, double winRate, double pickRate, double banRate,
                              List<Entry> starters, List<Entry> coreBuilds, List<Entry> boots,
                              Map<Integer, Entry> runes, Map<Integer, Entry> subRunes,
                              List<Map<Integer, Entry>> shardRows, List<RunePage> runePages,
                              List<Entry> skillOrders, List<Entry> skillStarts, List<Entry> spells,
                              LolalyticsPairs matchups, Damage damage, List<LengthBucket> gameLength, List<ItemSlot> itemSlots,
                              List<Entry> skillPaths) {

    public LolalyticsBuild {
        starters = List.copyOf(starters);
        coreBuilds = List.copyOf(coreBuilds);
        boots = List.copyOf(boots);
        runes = Map.copyOf(runes);
        subRunes = Map.copyOf(subRunes);
        shardRows = shardRows.stream().<Map<Integer, Entry>>map(Map::copyOf).toList();
        runePages = List.copyOf(runePages);
        skillOrders = List.copyOf(skillOrders);
        skillStarts = List.copyOf(skillStarts);
        spells = List.copyOf(spells);
        gameLength = List.copyOf(gameLength);
        itemSlots = List.copyOf(itemSlots);
        skillPaths = List.copyOf(skillPaths);
    }

    /** @param slot Itemplatz laut Lolalytics (Boots zählen dort nicht mit) */
    /** Anzahl der Shard-Reihen (Offensive, Flex, Defensive). */
    public static final int SHARD_ROWS = 3;

    /** Shard-ID → Eintrag in Reihe {@code row}; leer, wenn die Reihe fehlt. */
    public Map<Integer, Entry> shardRow(int row) {
        return row < shardRows.size() ? shardRows.get(row) : Map.of();
    }

    public record ItemSlot(int slot, List<Entry> entries) {

        public ItemSlot {
            entries = List.copyOf(entries);
        }
    }

    public record Damage(double physical, double magic, double trueDamage) {
    }

    public record LengthBucket(int wins, int games) {

        /**
         * Startminute je Spieldauer-Abschnitt in Lolalytics' Reihenfolge ({@code time} 1–7): unter 15, 15–20, 20–25, 25–30,
         * 30–35, 35–40 und ab 40 Minuten. Die Verteilung der Spiele passt dazu (Abschnitt 1 ≈ 1 %, Gipfel bei 25–30).
         */
        public static final List<Integer> START_MINUTES = List.of(0, 15, 20, 25, 30, 35, 40);

        /** Startminuten für {@code buckets} Abschnitte; {@code null}, wenn Lolalytics eine unbekannte Einteilung liefert. */
        public static List<Integer> startMinutes(int buckets) {
            return buckets == START_MINUTES.size() ? START_MINUTES : null;
        }
    }

    /**
     * @param minute durchschnittliche Kaufminute (nur bei Item-Listen, 5. Spalte der Seite); 0, wenn unbekannt
     */
    public record Entry(String key, double winRate, double pickRate, int games, int minute) {

        public Entry(String key, double winRate, double pickRate, int games) {
            this(key, winRate, pickRate, games, 0);
        }
    }

    /** @param mostPlayed die meistgespielte Seite (sonst die erfolgreichste); Winrate und Spiele sind die des Keystones */
    public record RunePage(List<Integer> primary, List<Integer> sub, List<Integer> shards, double winRate, int games,
                           boolean mostPlayed) {

        public RunePage {
            primary = List.copyOf(primary);
            sub = List.copyOf(sub);
            shards = List.copyOf(shards);
        }
    }
}
