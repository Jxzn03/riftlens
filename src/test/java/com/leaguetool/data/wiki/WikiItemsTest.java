package com.leaguetool.data.wiki;

import com.leaguetool.model.AbilitySlot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikiItemsTest {

    private static final String MODULE = """
            -- <pre>
            return {
                ["Endless Hunger"] = {
                    ["id"] = 2517,
                    ["effects"] = {
                        ["pass"] = {
                            ["name"] = "Famine",
                            ["unique"] = true,
                            ["description"] = "Gain {{as|5|ah}} {{as|(+ {{rd|13%|10%}} '''bonus''' AD)}} {{as|ability haste}}.",
                        },
                        ["act"] = {
                            ["name"] = "Ignored",
                            ["description"] = "Gain 100 ability haste.",
                        },
                    },
                },
                ["Whispering Circlet"] = {
                    ["id"] = 2526,
                    ["effects"] = {
                        ["pass"] = {
                            ["name"] = "Harmony",
                            ["description"] = "Grants {{as|heal and shield power}} equal to {{as|{{fd|0.5}}% '''bonus''' mana}}.",
                        },
                    },
                },
                ["No Id"] = { ["tier"] = 1 },
            }
            """;

    @Test
    void readsPassivesAsPlainTextAndKeepsMeleeAndRangedValues() {
        Map<Integer, WikiItems.Entry> items = WikiItems.parseModule(MODULE);
        assertEquals(2, items.size());
        WikiItems.Entry hunger = items.get(2517);
        assertEquals("Endless Hunger", hunger.name());
        assertEquals(List.of(new WikiItems.Passive("Famine", List.of("Gain 5 (+ 13%/10% bonus AD) ability haste."))),
                hunger.passives());
        assertEquals(List.of("Grants heal and shield power equal to 0.5% bonus mana."),
                items.get(2526).passives().get(0).lines());
    }

    @Test
    void followsReferencesToThePassiveOfAnotherItem() {
        Map<Integer, WikiItems.Entry> items = WikiItems.parseModule("""
                return {
                    ["Base"] = { ["id"] = 1, ["effects"] = { ["pass"] = { ["name"] = "Awe", ["description"] = "Text." } } },
                    ["Upgrade"] = { ["id"] = 2, ["effects"] = { ["pass"] = "=>Base" } },
                }
                """);
        assertEquals(List.of(new WikiItems.Passive("Awe", List.of("Text."))), items.get(2).passives());
    }

    @Test
    void readsCooldownFromFieldOrDescription() {
        Map<Integer, WikiItems.Entry> items = WikiItems.parseModule("""
                return {
                    ["Field"] = { ["id"] = 1, ["effects"] = { ["pass"] = { ["name"] = "A", ["description"] = "X.", ["cd"] = "60" } } },
                    ["Number"] = { ["id"] = 2, ["effects"] = { ["pass"] = { ["name"] = "B", ["description"] = "X.", ["cd"] = 90 } } },
                    ["Text"] = { ["id"] = 3, ["effects"] = { ["pass"] = { ["name"] = "Spellblade",
                        ["description"] = "Deals damage ({{fd|1.5}} second cooldown, starts after the attack)." } } },
                    ["None"] = { ["id"] = 4, ["effects"] = { ["pass"] = { ["name"] = "C", ["description"] = "No timer." } } },
                }
                """);
        assertEquals("60", items.get(1).passives().get(0).cooldown());
        assertEquals("90", items.get(2).passives().get(0).cooldown());
        assertEquals("1.5", items.get(3).passives().get(0).cooldown());
        assertEquals("", items.get(4).passives().get(0).cooldown());
    }

    @Test
    void readsActivesSeparatelyFromPassives() {
        WikiItems.Entry entry = WikiItems.parseModule("""
                return {
                    ["Hourglass"] = { ["id"] = 1, ["effects"] = {
                        ["pass"] = { ["name"] = "P", ["description"] = "X." },
                        ["act"] = { ["name"] = "Time Stop", ["description"] = "Stasis.", ["cd"] = "120" } } },
                }
                """).get(1);
        assertEquals(List.of("P"), entry.passives().stream().map(WikiItems.Passive::name).toList());
        assertEquals("Time Stop", entry.actives().get(0).name());
        assertEquals("120", entry.actives().get(0).cooldown());
    }

    @Test
    void recognizesImmobilizingAbilitiesFromTheirDescription() {
        assertTrue(ability("Ahri blows a kiss that charms the first enemy hit.").immobilizes());
        assertTrue(ability("Stuns all enemies in the area.").immobilizes());
        assertTrue(ability("Knocks up nearby enemies.").immobilizes());
        assertFalse(ability("Slows the target and silences it.").immobilizes());
    }

    private static WikiAbility ability(String description) {
        return new WikiAbility("X", AbilitySlot.Q, "Name", description, List.of(), "", List.of(), "", "", "", "", "", "", "", "", "", false);
    }

    @Test
    void itemWithoutEffectsHasNoPassives() {
        assertTrue(WikiItems.parseModule("return { [\"Plain\"] = { [\"id\"] = 1 } }").get(1).passives().isEmpty());
    }
}
