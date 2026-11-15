package com.leaguetool.stats;

import com.leaguetool.model.AbilitySlot;

import java.util.List;

/**
 * Alle Auswertungen zu einem Champion für eine Detailansicht. Jede {@link StatRanking} enthält die häufigsten
 * Einträge und die besten nach Wilson-Untergrenze (mit Mindest-Stichprobe {@code minGames}). Pick-Raten in den
 * Rankings beziehen sich auf die Spiele des Champions ({@code games}).
 *
 * @param games         Spiele des Champions im Filter
 * @param wins           davon gewonnen
 * @param pickRate       Pickrate des Champions
 * @param banRate        Banrate des Champions
 * @param starters       Startitems (sortierte Liste)
 * @param boots          erste fertige Boots
 * @param coreBuilds     die ersten drei verschiedenen legendären Items (Kaufreihenfolge)
 * @param keystones      Keystones
 * @param runePages      komplette Runenseiten
 * @param primaryTrees   Primärbäume
 * @param secondaryTrees Sekundärbäume
 * @param shards         Shards {@code [offense, flex, defense]}
 * @param skillOrders    Max-Reihenfolge Q/W/E
 * @param skillStarts    erste drei Skill-Punkte
 * @param spellCombos    Summoner-Spell-Paare (sortiert)
 */
public record ChampionReport(int championId, int minGames, int games, int wins,
                             double pickRate, double banRate,
                             StatRanking<List<Integer>> starters,
                             StatRanking<Integer> boots,
                             StatRanking<List<Integer>> coreBuilds,
                             StatRanking<Integer> core1,
                             StatRanking<Integer> core2,
                             StatRanking<Integer> core3,
                             StatRanking<Integer> keystones,
                             StatRanking<RunePageKey> runePages,
                             StatRanking<Integer> primaryTrees,
                             StatRanking<Integer> secondaryTrees,
                             StatRanking<List<Integer>> shards,
                             StatRanking<List<AbilitySlot>> skillOrders,
                             StatRanking<List<AbilitySlot>> skillStarts,
                             StatRanking<List<Integer>> spellCombos) implements WinStats {
}
