package com.leaguetool.stats;

import java.util.List;

/**
 * Schlüssel einer kompletten Runenseite für die Statistik: vier Primär-Runen (Riot-Reihenfolge, Keystone
 * zuerst), zwei Sekundär-Runen (sortiert, da Riot die Reihenfolge nicht garantiert) und drei Shards
 * (Offensive, Flex, Defensive).
 */
public record RunePageKey(int primaryStyle, int subStyle, List<Integer> primaryPerks, List<Integer> subPerks,
                          List<Integer> shards) {

    public RunePageKey {
        primaryPerks = List.copyOf(primaryPerks);
        subPerks = List.copyOf(subPerks);
        shards = List.copyOf(shards);
    }
}
