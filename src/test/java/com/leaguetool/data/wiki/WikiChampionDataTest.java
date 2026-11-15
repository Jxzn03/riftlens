package com.leaguetool.data.wiki;

import com.leaguetool.model.AbilitySlot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikiChampionDataTest {

    private final Map<String, WikiChampionData> data = WikiChampionData.parseModule(WikiTestPages.championLua());

    @Test
    void keysAreDataDragonIds() {
        assertEquals(List.of("Aatrox", "Ahri", "Kaisa", "Kled", "MonkeyKing"), List.copyOf(data.keySet()));
    }

    @Test
    void ahriValues() {
        WikiChampionData ahri = data.get("Ahri");
        assertEquals("Ahri", ahri.wikiName());
        assertEquals(103, ahri.id());
        assertEquals("The Nine-Tailed Fox", ahri.title());
        assertEquals("Mana", ahri.resource());
        assertEquals("Ranged", ahri.rangeType());
        assertEquals("Magic", ahri.adaptiveType());
        assertEquals(List.of("Middle"), ahri.positions());
        assertEquals(List.of("Burst"), ahri.roles());
        assertEquals(590, ahri.hpBase());
        assertEquals(104, ahri.hpLvl());
        assertEquals(418, ahri.mpBase());
        assertEquals(25, ahri.mpLvl());
        assertEquals(21, ahri.armBase());
        assertEquals(4.2, ahri.armLvl());
        assertEquals(30, ahri.mrBase());
        assertEquals(1.3, ahri.mrLvl());
        assertEquals(2.5, ahri.hp5Base());
        assertEquals(0.6, ahri.hp5Lvl());
        assertEquals(8, ahri.mp5Base());
        assertEquals(0.8, ahri.mp5Lvl());
        assertEquals(53, ahri.damBase());
        assertEquals(3, ahri.damLvl());
        assertEquals(0.668, ahri.asBase());
        assertEquals(2.2, ahri.asLvl());
        assertEquals(0.625, ahri.asRatio());
        assertEquals(550, ahri.range());
        assertEquals(330, ahri.ms());
        assertTrue(ahri.attackCastTime().isEmpty());
        assertEquals(-0.1, ahri.attackDelayOffset().orElseThrow());
        assertEquals(0.2, ahri.windupPercent().orElseThrow(), 1e-9);
        assertEquals(List.of("Essence Theft"), ahri.skillNames(AbilitySlot.P));
        assertEquals(List.of("Orb of Deception"), ahri.skillNames(AbilitySlot.Q));
        assertEquals(List.of("Spirit Rush"), ahri.skillNames(AbilitySlot.R));
    }

    @Test
    void wukongUsesApiNameMonkeyKing() {
        WikiChampionData wukong = data.get("MonkeyKing");
        assertEquals("Wukong", wukong.wikiName());
        assertEquals("MonkeyKing", wukong.apiName());
        assertEquals(62, wukong.id());
        assertEquals(List.of("Crushing Blow"), wukong.skillNames(AbilitySlot.Q));
    }

    @Test
    void championWithApostropheAndAttackTimes() {
        WikiChampionData kaisa = data.get("Kaisa");
        assertEquals("Kai'Sa", kaisa.wikiName());
        assertEquals(0.25, kaisa.attackCastTime().orElseThrow());
        assertEquals(0.25 / 1.55200004577636, kaisa.windupPercent().orElseThrow(), 1e-12);
        assertEquals(List.of("Icathian Rain", "Icathian Rain 2"), kaisa.skillNames(AbilitySlot.Q));

        WikiChampionData aatrox = data.get("Aatrox");
        assertEquals(0.300000011920928 / 1.51999998092651, aatrox.windupPercent().orElseThrow(), 1e-12);
        assertEquals(List.of("The Darkin Blade", "The Darkin Blade 2", "The Darkin Blade 3"),
                aatrox.skillNames(AbilitySlot.Q));
        assertEquals(List.of("Juggernaut"), aatrox.roles());
    }

    @Test
    void specialFormWithExpressionAndFractionalId() {
        WikiChampionData kled = data.get("Kled");
        assertEquals("Kled & Skaarl", kled.wikiName());
        assertEquals(240, kled.id());
        assertEquals(84 + 1000.0 / 17, kled.hpLvl(), 1e-9);
        assertEquals(List.of("Chaaaaaaaarge!!!", "Chaaaaaaaarge!!! 2"), kled.skillNames(AbilitySlot.R));
    }

    @Test
    void windupIsEmptyWithoutTimingData() {
        WikiChampionData noTiming = WikiChampionData.parseModule(
                "return { [\"X\"] = { id = 1, apiname = \"X\", stats = { hp_base = 1 } } }").get("X");
        assertFalse(noTiming.windupPercent().isPresent());
        assertEquals(0, noTiming.mpBase());
        assertTrue(noTiming.skills().isEmpty());
    }

    @Test
    void duplicateApiNamePrefersIntegralIdAndBrokenEntriesAreSkipped() {
        String lua = """
                return {
                  ["Kled & Skaarl"] = { id = 240.1, apiname = "Kled", stats = { hp_base = 810 } },
                  ["Kled"] = { id = 240, apiname = "Kled", stats = { hp_base = 410 } },
                  ["Kaputt"] = { id = 1, apiname = "Kaputt" },
                  ["OhneId"] = { apiname = "OhneId", stats = { hp_base = 1 } },
                  ["Mega Gnar"] = { id = 150.2, apiname = "GnarBig", stats = { hp_base = 1 } },
                }
                """;
        Map<String, WikiChampionData> parsed = WikiChampionData.parseModule(lua);
        assertEquals(List.of("Kled", "GnarBig"), List.copyOf(parsed.keySet()));
        assertEquals("Kled", parsed.get("Kled").wikiName());
        assertEquals(410, parsed.get("Kled").hpBase());
        assertEquals(150, parsed.get("GnarBig").id());
    }

    @Test
    void fromLuaRejectsMissingFields() {
        assertThrows(IllegalArgumentException.class, () -> WikiChampionData.fromLua("X", Map.of("id", 1.0)));
        assertThrows(IllegalArgumentException.class,
                () -> WikiChampionData.fromLua("X", Map.of("id", 1.0, "apiname", "X")));
        assertThrows(IllegalArgumentException.class,
                () -> WikiChampionData.fromLua("X", Map.of("apiname", "X", "stats", Map.of("hp_base", 1.0))));
    }
}
