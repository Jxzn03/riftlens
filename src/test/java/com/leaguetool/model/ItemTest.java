package com.leaguetool.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemTest {

    private static Item item(int id, String name, boolean purchasable, boolean inStore, String champion, int recipe) {
        return new Item(id, name, "", "", new ItemGold(400, 400, 160, purchasable), List.of(), Map.of(), List.of(),
                List.of(), Set.of(Item.SUMMONERS_RIFT), inStore, 1, champion, false, id + ".png", recipe);
    }

    private static Item shop(int id, String name) {
        return item(id, name, true, true, "", 0);
    }

    @Test
    void shopItemsAndSupportQuestUpgradesAreOnSummonersRift() {
        assertTrue(shop(3865, "World Atlas").isOnSummonersRift());
        assertTrue(item(3866, "Runic Compass", false, false, "", 3865).isOnSummonersRift());
        assertTrue(item(3042, "Muramana", false, false, "", 3004).isOnSummonersRift());
    }

    @Test
    void modeCopiesAndSpecialItemsAreNotOnSummonersRift() {
        assertFalse(shop(323003, "Archangel's Staff").isOnSummonersRift(), "Modus-Kopie");
        assertFalse(shop(3177, "Guardian's Blade").isOnSummonersRift(), "nur Howling Abyss");
        assertFalse(item(3330, "Scarecrow Effigy", true, true, "FiddleSticks", 0).isOnSummonersRift());
        assertFalse(item(2001, "Recall", false, false, "", 0).isOnSummonersRift());
    }

    @Test
    void summonersRiftListKeepsOneItemPerName() {
        List<Item> items = List.of(shop(1101, "Scorchclaw Pup"), shop(1107, "Scorchclaw Pup"),
                shop(3003, "Archangel's Staff"), shop(323003, "Archangel's Staff"));
        assertEquals(List.of(1101, 3003), Item.summonersRiftItems(items).stream().map(Item::id).toList());
    }

    @Test
    void finalBootsNeedDepthTwo() {
        Item basic = new Item(1001, "Boots", "", "", new ItemGold(300, 300, 210, true), List.of("Boots"), Map.of(),
                List.of(), List.of(), Set.of(Item.SUMMONERS_RIFT), true, 1, "", false, "1001.png", 0);
        Item upgraded = new Item(3020, "Sorcerer's Shoes", "", "", new ItemGold(1100, 800, 770, true), List.of("Boots"),
                Map.of(), List.of(1001), List.of(), Set.of(Item.SUMMONERS_RIFT), true, 2, "", false, "3020.png", 0);
        assertTrue(basic.isBoots());
        assertFalse(basic.isFinalBoots(), "Basis-Boots sind keine fertigen Boots");
        assertTrue(upgraded.isFinalBoots());
        assertFalse(shop(3089, "Rabadon's Deathcap").isFinalBoots());
    }
}
