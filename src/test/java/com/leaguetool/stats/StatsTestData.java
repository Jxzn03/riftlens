package com.leaguetool.stats;

import com.leaguetool.data.ddragon.DDragonParser;
import com.leaguetool.model.Item;
import com.leaguetool.model.ItemGold;
import com.leaguetool.testutil.Fixtures;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Items für die Tests der Item-Einordnung. */
final class StatsTestData {

    private StatsTestData() {
    }

    static List<Item> ddragonItems() {
        try {
            return new DDragonParser().parseItems(Fixtures.read("ddragon/item.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Doran's Ring (1056) fehlt in der DDragon-Fixture. */
    static Item doransRing() {
        return item(1056, "Doran's Ring", 400, List.of("Lane", "SpellDamage", "Mana"), List.of(), 1, true);
    }

    static Item item(int id, String name, int total, List<String> tags, List<Integer> into, int depth,
                     boolean purchasable) {
        return new Item(id, name, "", "", new ItemGold(total, total, total * 7 / 10, purchasable), tags,
                Map.of(), List.of(), into, Set.of(Item.SUMMONERS_RIFT), purchasable, depth, "", false,
                id + ".png", 0);
    }
}
