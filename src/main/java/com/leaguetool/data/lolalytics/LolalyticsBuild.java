package com.leaguetool.data.lolalytics;

import java.util.List;
import java.util.Map;

/**
 * Statistik eines Champions auf einer Lane laut Lolalytics. Raten sind Anteile (0..1).
 *
 * @param starters    Schlüssel wie {@code 1056_2003_2003}
 * @param coreBuilds  meistgebaute drei Items inklusive Boots, Schlüssel wie {@code 3118_3020_4645}
 * @param boots       Schlüssel ist die Item-ID
 * @param runes       Runen-ID → Eintrag (Position in der Hauptreihe)
 * @param runePages   die meistgespielte und die erfolgreichste Runenseite
 * @param skillOrders Max-Reihenfolge, Schlüssel wie {@code QWE}
 * @param skillStarts erste sechs Skill-Punkte, Schlüssel wie {@code 213114} (1 = Q … 4 = R)
 * @param spells      Schlüssel wie {@code 4_14}
 * @param matchups    Winrates gegen jeden Gegner auf jeder Lane
 * @param damage      durchschnittlicher Schaden pro Spiel
 * @param gameLength  Spiele und Siege je Spieldauer-Abschnitt, von kurz nach lang; leer, wenn die Seite keine liefert
 * @param itemSlots   Items je Itemplatz nach dem Core-Build; Schlüssel ist die Item-ID, leer, wenn die Seite keine liefert
 */
public record LolalyticsBuild(int games, double winRate, double pickRate, double banRate,
                              List<Entry> starters, List<Entry> coreBuilds, List<Entry> boots,
                              Map<Integer, Entry> runes, List<RunePage> runePages,
                              List<Entry> skillOrders, List<Entry> skillStarts, List<Entry> spells,
                              LolalyticsPairs matchups, Damage damage, List<LengthBucket> gameLength, List<ItemSlot> itemSlots) {

    public LolalyticsBuild {
        starters = List.copyOf(starters);
        coreBuilds = List.copyOf(coreBuilds);
        boots = List.copyOf(boots);
        runes = Map.copyOf(runes);
        runePages = List.copyOf(runePages);
        skillOrders = List.copyOf(skillOrders);
        skillStarts = List.copyOf(skillStarts);
        spells = List.copyOf(spells);
        gameLength = List.copyOf(gameLength);
        itemSlots = List.copyOf(itemSlots);
    }

    /** @param slot Itemplatz laut Lolalytics (Boots zählen dort nicht mit) */
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

    public record Entry(String key, double winRate, double pickRate, int games) {
    }

    public record RunePage(List<Integer> primary, List<Integer> sub, List<Integer> shards, double winRate, int games) {

        public RunePage {
            primary = List.copyOf(primary);
            sub = List.copyOf(sub);
            shards = List.copyOf(shards);
        }
    }
}
