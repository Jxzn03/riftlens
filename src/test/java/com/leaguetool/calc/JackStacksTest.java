package com.leaguetool.calc;

import com.leaguetool.model.Item;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JackStacksTest {

    private static Item item(String name, Map<String, Double> stats) {
        return TestItems.synthetic(name, "", stats, 1000);
    }

    @Test
    void leererBuildGibtKeineStapel() {
        assertEquals(0, JackStacks.count(List.of()));
    }

    @Test
    void jedeWerteartZaehltEinmal() {
        Item sword = item("Schwert", Map.of("FlatPhysicalDamageMod", 10.0));
        Item vest = item("Weste", Map.of("FlatArmorMod", 15.0, "FlatHPPoolMod", 100.0));
        assertEquals(3, JackStacks.count(List.of(sword, sword, vest)));
    }

    @Test
    void flachesUndProzentualesLauftempoZaehlenGetrennt() {
        Item flat = item("Flach", Map.of("FlatMovementSpeedMod", 25.0));
        Item percent = item("Prozent", Map.of("PercentMovementSpeedMod", 0.05));
        assertEquals(2, JackStacks.count(List.of(flat, percent)));
    }
}
