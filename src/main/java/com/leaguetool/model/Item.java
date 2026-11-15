package com.leaguetool.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Item aus {@code item.json}.
 *
 * @param descriptionHtml  Beschreibung im Data-Dragon-Markup ({@code <stats>}, {@code <passive>} ...)
 * @param stats            Data-Dragon-Stat-Schlüssel ({@code FlatMagicDamageMod} ...) mit Wert; unvollständig
 *                         (Ability Haste, Lethality usw. stehen nur in der Beschreibung)
 * @param maps             Map-IDs, auf denen das Item verfügbar ist (11 = Summoner's Rift)
 * @param inStore          im Shop sichtbar (weder {@code inStore=false} noch {@code hideFromAll})
 * @param requiredChampion nur für diesen Champion (leer = alle)
 * @param specialRecipe    ID des Items, aus dem dieses im Spiel entsteht (z. B. Muramana aus Manamune, 0 = keins)
 */
public record Item(
        int id,
        String name,
        String plaintext,
        String descriptionHtml,
        ItemGold gold,
        List<String> tags,
        Map<String, Double> stats,
        List<Integer> from,
        List<Integer> into,
        Set<Integer> maps,
        boolean inStore,
        int depth,
        String requiredChampion,
        boolean consumed,
        String imageFile,
        int specialRecipe) {

    public static final int SUMMONERS_RIFT = 11;

    /** Ab dieser ID stehen Modus-Kopien normaler Items (z. B. 323003 zu 3003), die Data Dragon ebenfalls für Map 11 führt. */
    private static final int MODE_VARIANT_ID_START = 100_000;

    /** Guardian's-Items und Cappa Juice: nur auf der Howling Abyss, obwohl Data Dragon sie auch für Map 11 führt. */
    private static final Set<Integer> HOWLING_ABYSS_ONLY = Set.of(2051, 2141, 3112, 3177, 3184);

    public Item {
        tags = List.copyOf(tags);
        stats = Map.copyOf(stats);
        from = List.copyOf(from);
        into = List.copyOf(into);
        maps = Set.copyOf(maps);
        requiredChampion = requiredChampion == null ? "" : requiredChampion;
    }

    public boolean isBoots() {
        return tags.contains("Boots");
    }

    public boolean isTrinket() {
        return tags.contains("Trinket");
    }

    public boolean isConsumable() {
        return consumed || tags.contains("Consumable");
    }

    /** Im normalen Summoner's-Rift-Shop kaufbar (keine Champion-/Modus-Sonderitems). */
    public boolean isPurchasableOnSummonersRift() {
        return maps.contains(SUMMONERS_RIFT) && gold.purchasable() && inStore && requiredChampion.isEmpty();
    }

    /**
     * Gehört zum normalen Item-Bestand von Summoner's Rift: im Shop kaufbar oder im Spiel aus einem Item entstanden
     * (z. B. Runic Compass, Bounty of Worlds, Muramana). Modus-Kopien und Champion-Sonderitems zählen nicht.
     */
    public boolean isOnSummonersRift() {
        return id < MODE_VARIANT_ID_START && !HOWLING_ABYSS_ONLY.contains(id) && maps.contains(SUMMONERS_RIFT)
                && requiredChampion.isEmpty() && (isPurchasableOnSummonersRift() || specialRecipe > 0);
    }

    /** Die Summoner's-Rift-Items aus {@code items}; bei gleichem Namen bleibt nur das erste (z. B. Jungle-Pets). */
    public static List<Item> summonersRiftItems(List<Item> items) {
        Set<String> names = new HashSet<>();
        List<Item> result = new ArrayList<>();
        for (Item item : items) {
            if (item.isOnSummonersRift() && names.add(item.name())) {
                result.add(item);
            }
        }
        return result;
    }

    /** Fertiges Item: wird in nichts weiter gebaut, ist kein Verbrauchsgegenstand/Trinket und kostet Gold. */
    public boolean isCompleted() {
        return into.isEmpty() && !isConsumable() && !isTrinket() && gold.total() > 0;
    }
}
