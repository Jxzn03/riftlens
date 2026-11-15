package com.leaguetool.calc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemPassiveTextParserTest {

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "Increase your ability power by 30%.;ABILITY_POWER_AMP;30",
            "Increases your total Ability Power by 30%.;ABILITY_POWER_AMP;30",
            "Gain bonus health equal to 12% bonus health from items.;ITEM_HEALTH_AMP;12",
            "Gain bonus Health equal to 12% of your Item Health (0).;ITEM_HEALTH_AMP;12",
            "Gain ability power equal to 2% bonus health.;AP_PER_BONUS_HEALTH;2",
            "Gain 2% of your bonus Health as Ability Power.;AP_PER_BONUS_HEALTH;2",
            "Gain bonus attack damage equal to 2.5% bonus health.;AD_PER_BONUS_HEALTH;2.5",
            "Gain 2.5% of your bonus Health as Attack Damage.;AD_PER_BONUS_HEALTH;2.5",
            "Grants ability power equal to 1% bonus mana.;AP_PER_BONUS_MANA;1",
            "Grants bonus attack damage equal to 2% maximum mana.;AD_PER_MAX_MANA;2",
            "Gain 2% max Mana as bonus Attack Damage.;AD_PER_MAX_MANA;2",
            "Grants bonus health equal to 15% bonus mana.;HEALTH_PER_BONUS_MANA;15",
            "Gain bonus attack damage equal to 50% base AD.;AD_PER_BASE_AD;50",
            "Grants heal and shield power equal to 0.5% bonus mana.;HSP_PER_BONUS_MANA;0.5",
            "Gain adaptive force equal to 5% of your total movement speed.;ADAPTIVE_PER_MOVE_SPEED;5",
            "Gain 5% of your Move Speed as Adaptive Force.;ADAPTIVE_PER_MOVE_SPEED;5",
            "Gain 25 Basic Ability Haste.;BASIC_ABILITY_HASTE;25",
            "Gain 20 ultimate haste.;ULTIMATE_HASTE;20",
            "Gain 30 Ultimate Ability Haste.;ULTIMATE_HASTE;30",
            "Abilities with immobilizing effects have their cooldown reduced equivalent to 20 ability haste.;IMMOBILIZE_ABILITY_HASTE;20",
            "Gain 20 Ability Haste for your abilities with Immobilizing effects.;IMMOBILIZE_ABILITY_HASTE;20",
            "Gain 10 Summoner Spell Haste.;SUMMONER_SPELL_HASTE;10",
    })
    void recognizesSingleEffects(String line, StatType type, double value) {
        assertEquals(List.of(new ItemPassiveTextParser.Effect(type, value)), ItemPassiveTextParser.parse(line));
    }

    @Test
    void recognizesDawncoreInBothWordings() {
        List<ItemPassiveTextParser.Effect> expected = List.of(
                new ItemPassiveTextParser.Effect(StatType.HSP_PER_100_BASE_MANA_REGEN, 2),
                new ItemPassiveTextParser.Effect(StatType.AP_PER_100_BASE_MANA_REGEN, 15));
        assertEquals(expected, ItemPassiveTextParser.parse(
                "Gain 2% heal and shield power and 15 ability power for every additional 100% base mana regeneration."));
        assertEquals(expected, ItemPassiveTextParser.parse("Gain 2% Heal and Shield Power and 15 Ability Power per 100% Base Mana Regen."));
    }

    @Test
    void recognizesHasteFromBonusAdWithMeleeAndRangedValues() {
        assertEquals(List.of(new ItemPassiveTextParser.Effect(StatType.ABILITY_HASTE, 5),
                        new ItemPassiveTextParser.Effect(StatType.HASTE_PER_BONUS_AD_MELEE, 13),
                        new ItemPassiveTextParser.Effect(StatType.HASTE_PER_BONUS_AD_RANGED, 10)),
                ItemPassiveTextParser.parse("Gain 5 (+ 13%/10% bonus AD) ability haste."));
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "After 5 seconds of champion combat, increase your bonus Armor and Magic Resist by 30% until end of combat.",
            "Gain up to 10% increased Attack Damage based on your percent missing Health.",
            "Gain 5 Ability Power per Glory and 10% Move Speed at 10 or higher Glory.",
            "Attacks grant 8% Attack Speed for 4 seconds.",
    })
    void conditionalEffectsAreNeitherRecognizedNorFlagged(String line) {
        assertTrue(ItemPassiveTextParser.parse(line).isEmpty());
        assertFalse(ItemPassiveTextParser.looksPermanent(line));
    }

    @Test
    void unknownPermanentEffectIsFlagged() {
        assertTrue(ItemPassiveTextParser.looksPermanent("Gain ability power equal to 3% maximum mana."));
    }

    @Test
    void dataDragonLineWithoutNumberIsNotFlagged() {
        assertFalse(ItemPassiveTextParser.looksPermanent("Gain  bonus Attack Damage."));
    }

    @Test
    void recognizedEffectIsNotFlagged() {
        assertFalse(ItemPassiveTextParser.looksPermanent("Gain 25 Basic Ability Haste."));
    }
}
