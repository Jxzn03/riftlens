package com.leaguetool.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class StatLabelsTest {

    @Test
    void germanUsesTheAppLabels() {
        assertEquals("Angriffsschaden", StatLabels.label(StatType.ATTACK_DAMAGE, "de_DE"));
        assertEquals("Fähigkeitstempo", StatLabels.label(StatType.ABILITY_HASTE, "de_DE"));
    }

    @Test
    void otherLanguagesUseTheirOwnNames() {
        assertEquals("Attack Damage", StatLabels.label(StatType.ATTACK_DAMAGE, "en_US"));
        assertEquals("Attack Damage", StatLabels.label(StatType.ATTACK_DAMAGE, "en_GB"));
        assertEquals("Dégâts d'attaque", StatLabels.label(StatType.ATTACK_DAMAGE, "fr_FR"));
        assertEquals("Velocidad de habilidades", StatLabels.label(StatType.ABILITY_HASTE, "es_ES"));
        assertEquals("Letalità", StatLabels.label(StatType.LETHALITY, "it_IT"));
        assertEquals("Destrukcja", StatLabels.label(StatType.LETHALITY, "pl_PL"));
    }

    @Test
    void missingNamesFallBackToEnglishThenGerman() {
        assertEquals("Cooldown Reduction", StatLabels.label(StatType.COOLDOWN_REDUCTION, "fr_FR"));
        assertEquals("Attack Damage", StatLabels.label(StatType.ATTACK_DAMAGE, "ja_JP"));
        assertEquals(StatType.SLOW_RESIST.label(), StatLabels.label(StatType.SLOW_RESIST, "en_US"));
    }

    @Test
    void shortNamesOnlyForGermanAndEnglish() {
        assertEquals("AD", StatLabels.shortLabel(StatType.ATTACK_DAMAGE, "en_US"));
        assertEquals("Krit", StatLabels.shortLabel(StatType.CRIT_CHANCE, "de_DE"));
        assertEquals("Leben", StatLabels.shortLabel(StatType.HEALTH, "de_DE"));
        assertEquals("Health", StatLabels.shortLabel(StatType.HEALTH, "en_US"));
        assertEquals("Armure", StatLabels.shortLabel(StatType.ARMOR, "fr_FR"));
    }

    @Test
    void everyLanguageNamesEveryStatThatRiftItemsHave() {
        StatType[] riftStats = {StatType.HEALTH, StatType.MANA, StatType.ATTACK_DAMAGE, StatType.ABILITY_POWER,
                StatType.ARMOR, StatType.MAGIC_RESIST, StatType.ATTACK_SPEED, StatType.CRIT_CHANCE, StatType.MOVE_SPEED,
                StatType.MOVE_SPEED_PERCENT, StatType.ABILITY_HASTE, StatType.LETHALITY, StatType.ARMOR_PENETRATION,
                StatType.MAGIC_PENETRATION, StatType.LIFE_STEAL, StatType.OMNIVAMP, StatType.HEALTH_REGEN,
                StatType.MANA_REGEN, StatType.HEAL_SHIELD_POWER, StatType.TENACITY};
        for (String locale : new String[] {"fr_FR", "es_ES", "it_IT", "pl_PL"}) {
            for (StatType type : riftStats) {
                assertFalse(StatLabels.label(type, locale).equals(StatLabels.label(type, "en_US"))
                                && !type.equals(StatType.MANA),
                        () -> locale + " fehlt " + type);
            }
        }
    }
}
