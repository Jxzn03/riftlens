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
 */
public record LolalyticsBuild(int games, double winRate, double pickRate, double banRate,
                              List<Entry> starters, List<Entry> coreBuilds, List<Entry> boots,
                              Map<Integer, Entry> runes, List<RunePage> runePages,
                              List<Entry> skillOrders, List<Entry> skillStarts, List<Entry> spells,
                              LolalyticsPairs matchups, Damage damage, List<LengthBucket> gameLength) {

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
    }

    public record Damage(double physical, double magic, double trueDamage) {
    }

    public record LengthBucket(int wins, int games) {
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
