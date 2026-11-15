package com.leaguetool.stats;

import com.leaguetool.model.Item;
import com.leaguetool.model.ItemGold;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.leaguetool.stats.StatsTestData.ddragonItems;
import static com.leaguetool.stats.StatsTestData.item;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameItemClassifierTest {

    private final GameItemClassifier classifier = new GameItemClassifier(ddragonItems());

    @Test
    void boots() {
        assertTrue(classifier.isBoots(1001));
        assertFalse(classifier.isFinalBoots(1001), "Basis-Boots sind keine fertigen Boots");
        assertTrue(classifier.isBoots(3020));
        assertTrue(classifier.isFinalBoots(3020));
        assertFalse(classifier.isBoots(3089));
        assertFalse(classifier.isFinalBoots(3089));
    }

    @Test
    void legendary() {
        assertTrue(classifier.isLegendary(3089), "Rabadon");
        assertTrue(classifier.isLegendary(3157), "Zhonya");
        assertFalse(classifier.isLegendary(2003), "Trank");
        assertFalse(classifier.isLegendary(3340), "Trinket");
        assertFalse(classifier.isLegendary(3020), "Schuhe");
        assertFalse(classifier.isLegendary(1058), "Komponente, zu billig und baut weiter");
        assertFalse(classifier.isLegendary(2420), "Komponente");
        assertFalse(classifier.isLegendary(1001));
    }

    @Test
    void starter() {
        assertTrue(classifier.isStarter(2003), "Trank");
        assertTrue(classifier.isStarter(1035), "Emberknife (Jungle)");
        assertTrue(classifier.isStarter(1001), "Boots: 300 Gold, depth 1");
        assertFalse(classifier.isStarter(3340), "Trinket");
        assertFalse(classifier.isStarter(3330), "Trinket (Fiddlesticks)");
        assertFalse(classifier.isStarter(1058), "1200 Gold");
        assertFalse(classifier.isStarter(3089));
        assertFalse(classifier.isStarter(1500), "kostet nichts");
        assertTrue(new GameItemClassifier(List.of(StatsTestData.doransRing())).isStarter(1056));
    }

    @Test
    void unknownIdsAreFalse() {
        assertFalse(classifier.isBoots(99999));
        assertFalse(classifier.isFinalBoots(99999));
        assertFalse(classifier.isLegendary(99999));
        assertFalse(classifier.isStarter(99999));
        assertFalse(new GameItemClassifier(List.of()).isLegendary(3089));
    }

    @Test
    void intoOnlyCountsPurchasableSummonersRiftItems() {
        Item upgradeTarget = new Item(7000, "Ornn-Aufwertung", "", "", new ItemGold(0, 4000, 0, false),
                List.of("SpellDamage"), Map.of(), List.of(9000), List.of(), Set.of(Item.SUMMONERS_RIFT), false, 4,
                "", false, "7000.png", 0);
        Item withUpgrade = item(9000, "Legendär mit Ornn-Upgrade", 3000, List.of("SpellDamage"), List.of(7000),
                3, true);
        Item buildsFurther = item(9001, "Baut weiter", 2500, List.of("SpellDamage"), List.of(9002), 2, true);
        Item target = item(9002, "Ziel", 3200, List.of("SpellDamage"), List.of(), 3, true);
        Item unknownInto = item(9003, "Unbekanntes into", 2800, List.of("Damage"), List.of(123456), 3, true);
        Item cheap = item(9004, "Billig", 1999, List.of("Damage"), List.of(), 2, true);
        Item expensiveBoots = item(9005, "Teure Schuhe", 2200, List.of("Boots"), List.of(), 3, true);

        List<Item> items = new ArrayList<>(List.of(upgradeTarget, withUpgrade, buildsFurther, target, unknownInto,
                cheap, expensiveBoots));
        GameItemClassifier c = new GameItemClassifier(items);
        assertTrue(c.isLegendary(9000), "into nur nicht kaufbare Items");
        assertFalse(c.isLegendary(9001), "into enthält kaufbares Item");
        assertTrue(c.isLegendary(9002));
        assertTrue(c.isLegendary(9003), "unbekannte into-ID gilt als nicht kaufbar");
        assertFalse(c.isLegendary(9004), "unter 2000 Gold");
        assertFalse(c.isLegendary(9005), "Schuhe");
        assertTrue(c.isFinalBoots(9005), "depth 3 ist auch fertige Boots");
    }
}
