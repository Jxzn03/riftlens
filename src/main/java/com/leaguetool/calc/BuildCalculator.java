package com.leaguetool.calc;

import com.leaguetool.model.Item;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Summiert Werte und Kosten von bis zu sechs Items. */
public final class BuildCalculator {

    /** Maximale Anzahl Items in einem Build (Inventar ohne Trinket). */
    public static final int MAX_ITEMS = 6;

    private BuildCalculator() {
    }

    /**
     * Summe der Item-Werte (über {@link ItemStatParser}) und der Gesamtkosten, dazu die immer aktiven
     * Passiven aus {@link ItemPassives}. Doppelte Items zählen doppelt; gleichnamige Passiven sind einzigartig
     * und zählen je Wert nur mit dem stärksten, verschieden benannte addieren sich. Andere Passiven und
     * Effekte werden nicht berücksichtigt.
     *
     * @throws IllegalArgumentException bei mehr als {@link #MAX_ITEMS} Items
     */
    public static BuildTotals totals(List<Item> items) {
        Objects.requireNonNull(items, "items");
        if (items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException(
                    "Höchstens " + MAX_ITEMS + " Items erlaubt, erhalten: " + items.size());
        }
        StatBlock stats = StatBlock.EMPTY;
        int gold = 0;
        List<String> unparsed = new ArrayList<>();
        Map<String, ItemPassive> strongest = new HashMap<>();
        for (Item item : items) {
            Objects.requireNonNull(item, "item");
            ParsedItemStats parsed = ItemStatParser.parse(item);
            stats = stats.plus(parsed.stats());
            for (ItemPassive passive : ItemPassives.of(item.id())) {
                strongest.merge(passive.passive() + "|" + passive.type(), passive,
                        (a, b) -> a.value() >= b.value() ? a : b);
            }
            gold += item.gold().total();
            for (String u : parsed.unparsed()) {
                unparsed.add(item.name() + ": " + u);
            }
        }
        for (ItemPassive passive : strongest.values()) {
            stats = stats.plus(StatBlock.of(passive.type(), passive.value()));
        }
        return new BuildTotals(stats, gold, unparsed, items.size());
    }
}
