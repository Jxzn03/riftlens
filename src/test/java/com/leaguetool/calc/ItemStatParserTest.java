package com.leaguetool.calc;

import com.leaguetool.model.Item;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.leaguetool.calc.TestItems.byId;
import static com.leaguetool.calc.TestItems.description;
import static com.leaguetool.calc.TestItems.synthetic;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemStatParserTest {

    private static final double EPS = 1e-9;

    private static ParsedItemStats parse(Item item) {
        return ItemStatParser.parse(item);
    }

    // --- Fixture: Beschreibung als Primärquelle ---------------------------------------------------

    @Test
    void rabadonsDeathcapHas130AbilityPower() {
        ParsedItemStats p = parse(byId(TestItems.RABADONS_DEATHCAP));
        assertEquals(StatBlock.of(StatType.ABILITY_POWER, 130), p.stats());
        assertTrue(p.complete());
    }

    @Test
    void sorcerersShoesHaveFlatMagicPenetrationAndMoveSpeed() {
        ParsedItemStats p = parse(byId(TestItems.SORCERERS_SHOES));
        assertEquals(12, p.stats().get(StatType.MAGIC_PENETRATION), EPS);
        assertEquals(0, p.stats().get(StatType.MAGIC_PENETRATION_PERCENT), EPS);
        assertEquals(45, p.stats().get(StatType.MOVE_SPEED), EPS);
        assertEquals(0, p.stats().get(StatType.MOVE_SPEED_PERCENT), EPS);
        assertEquals(2, p.stats().size());
        assertTrue(p.unparsed().isEmpty());
    }

    @Test
    void zhonyasHourglassHasAbilityPowerAndArmor() {
        ParsedItemStats p = parse(byId(TestItems.ZHONYAS_HOURGLASS));
        assertEquals(StatBlock.of(StatType.ABILITY_POWER, 105).with(StatType.ARMOR, 50), p.stats());
        assertTrue(p.unparsed().isEmpty());
    }

    @Test
    void bootsHave25MoveSpeed() {
        assertEquals(StatBlock.of(StatType.MOVE_SPEED, 25), parse(byId(TestItems.BOOTS)).stats());
    }

    @Test
    void itemsWithoutStatsAreEmpty() {
        for (int id : List.of(TestItems.HEALTH_POTION, TestItems.STEALTH_WARD, 1500, 1035, 3330)) {
            ParsedItemStats p = parse(byId(id));
            assertTrue(p.stats().isEmpty(), "Item " + id);
            assertTrue(p.unparsed().isEmpty(), "Item " + id);
        }
    }

    @Test
    void everyFixtureItemIsFullyRecognised() {
        for (Item item : TestItems.fixture().values()) {
            assertTrue(parse(item).complete(), item.name() + ": " + parse(item).unparsed());
        }
    }

    // --- Robustheit des stats-Blocks --------------------------------------------------------------

    @Test
    void toleratesExtraTagsWhitespaceAndOtherNumberTags() {
        String html = description("  <attention> 25% </attention>&nbsp;Attack   Speed <br/>"
                + "<font color='#ffffff'><attention>18</attention> Lethality</font><BR>"
                + "<ornnBonus>10%</ornnBonus> Move Speed<br><br>"
                + "<attention>+20</attention>Ability Haste");
        ParsedItemStats p = parse(synthetic("Robust", html, Map.of(), 0));
        assertEquals(25, p.stats().get(StatType.ATTACK_SPEED), EPS);
        assertEquals(18, p.stats().get(StatType.LETHALITY), EPS);
        assertEquals(10, p.stats().get(StatType.MOVE_SPEED_PERCENT), EPS);
        assertEquals(20, p.stats().get(StatType.ABILITY_HASTE), EPS);
        assertTrue(p.complete(), p.unparsed().toString());
    }

    @Test
    void percentSignSelectsPercentVariant() {
        String html = description("<attention>20</attention> Magic Penetration<br>"
                + "<attention>8%</attention> Magic Penetration<br>"
                + "<attention>45</attention> Move Speed<br>"
                + "<attention>4%</attention> Move Speed");
        StatBlock s = parse(synthetic("Prozent", html, Map.of(), 0)).stats();
        assertEquals(20, s.get(StatType.MAGIC_PENETRATION), EPS);
        assertEquals(8, s.get(StatType.MAGIC_PENETRATION_PERCENT), EPS);
        assertEquals(45, s.get(StatType.MOVE_SPEED), EPS);
        assertEquals(4, s.get(StatType.MOVE_SPEED_PERCENT), EPS);
    }

    @Test
    void sameStatTwiceIsSummed() {
        String html = description("<attention>10</attention> Ability Haste<br><attention>5</attention> Ability Haste");
        assertEquals(15, parse(synthetic("Doppelt", html, Map.of(), 0)).stats().get(StatType.ABILITY_HASTE), EPS);
    }

    /** Alle Stat-Namen, die in Patch 16.22.1 bei Summoner's-Rift-Items vorkommen (en_US). */
    @Test
    void recognisesAllStatNamesFromRealSummonersRiftItems() {
        String html = description(String.join("<br>",
                "<attention>10</attention> Ability Haste",
                "<attention>45</attention> Ability Power",
                "<attention>90</attention> Adaptive Force",
                "<attention>15</attention> Armor",
                "<attention>30%</attention> Armor Penetration",
                "<attention>10</attention> Attack Damage",
                "<attention>10%</attention> Attack Speed",
                "<attention>100%</attention> Base Health Regen",
                "<attention>50%</attention> Base Mana Regen",
                "<attention>15%</attention> Critical Strike Chance",
                "<attention>30%</attention> Critical Strike Damage",
                "<attention>2</attention> Gold Per 10 Seconds",
                "<attention>12%</attention> Heal and Shield Power",
                "<attention>350</attention> Health",
                "<attention>18</attention> Lethality",
                "<attention>7%</attention> Life Steal",
                "<attention>12</attention> Magic Penetration",
                "<attention>40%</attention> Magic Penetration",
                "<attention>20</attention> Magic Resist",
                "<attention>300</attention> Mana",
                "<attention>25</attention> Move Speed",
                "<attention>4%</attention> Move Speed",
                "<attention>2.5%</attention> Omnivamp",
                "<attention>30%</attention> Tenacity"));
        ParsedItemStats p = parse(synthetic("Alle", html, Map.of(), 0));
        assertTrue(p.complete(), p.unparsed().toString());
        StatBlock s = p.stats();
        assertEquals(10, s.get(StatType.ABILITY_HASTE), EPS);
        assertEquals(45, s.get(StatType.ABILITY_POWER), EPS);
        assertEquals(90, s.get(StatType.ADAPTIVE_FORCE), EPS);
        assertEquals(15, s.get(StatType.ARMOR), EPS);
        assertEquals(30, s.get(StatType.ARMOR_PENETRATION), EPS);
        assertEquals(10, s.get(StatType.ATTACK_DAMAGE), EPS);
        assertEquals(10, s.get(StatType.ATTACK_SPEED), EPS);
        assertEquals(100, s.get(StatType.HEALTH_REGEN), EPS);
        assertEquals(50, s.get(StatType.MANA_REGEN), EPS);
        assertEquals(15, s.get(StatType.CRIT_CHANCE), EPS);
        assertEquals(30, s.get(StatType.CRIT_DAMAGE), EPS);
        assertEquals(2, s.get(StatType.GOLD_PER_10), EPS);
        assertEquals(12, s.get(StatType.HEAL_SHIELD_POWER), EPS);
        assertEquals(350, s.get(StatType.HEALTH), EPS);
        assertEquals(18, s.get(StatType.LETHALITY), EPS);
        assertEquals(7, s.get(StatType.LIFE_STEAL), EPS);
        assertEquals(12, s.get(StatType.MAGIC_PENETRATION), EPS);
        assertEquals(40, s.get(StatType.MAGIC_PENETRATION_PERCENT), EPS);
        assertEquals(20, s.get(StatType.MAGIC_RESIST), EPS);
        assertEquals(300, s.get(StatType.MANA), EPS);
        assertEquals(25, s.get(StatType.MOVE_SPEED), EPS);
        assertEquals(4, s.get(StatType.MOVE_SPEED_PERCENT), EPS);
        assertEquals(2.5, s.get(StatType.OMNIVAMP), EPS);
        assertEquals(30, s.get(StatType.TENACITY), EPS);
    }

    @Test
    void recognisesStatNamesOfOtherModes() {
        String html = description("<attention>10%</attention> Cooldown Reduction<br>"
                + "<attention>5</attention> Health Regen per 5 seconds<br>"
                + "<attention>3</attention> Mana Regen per 5 seconds");
        ParsedItemStats p = parse(synthetic("Andere Modi", html, Map.of(), 0));
        assertTrue(p.complete(), p.unparsed().toString());
        assertEquals(10, p.stats().get(StatType.COOLDOWN_REDUCTION), EPS);
        assertEquals(5, p.stats().get(StatType.HEALTH_REGEN_FLAT), EPS);
        assertEquals(3, p.stats().get(StatType.MANA_REGEN_FLAT), EPS);
    }

    // --- Fallback auf die Data-Dragon-stats-Map ---------------------------------------------------

    @Test
    void fallsBackToDdragonStatsWhenDescriptionIsEmpty() {
        Map<String, Double> stats = Map.of(
                "PercentAttackSpeedMod", 0.25,
                "FlatCritChanceMod", 0.2,
                "FlatPhysicalDamageMod", 40.0,
                "PercentMovementSpeedMod", 0.07,
                "PercentLifeStealMod", 0.1,
                "FlatHPRegenMod", 1.0,
                "FlatMagicDamageMod", 0.0);
        ParsedItemStats p = parse(synthetic("Nur DDragon", "", stats, 0));
        StatBlock expected = StatBlock.of(StatType.ATTACK_SPEED, 25)
                .with(StatType.CRIT_CHANCE, 20)
                .with(StatType.ATTACK_DAMAGE, 40)
                .with(StatType.MOVE_SPEED_PERCENT, 7)
                .with(StatType.LIFE_STEAL, 10)
                .with(StatType.HEALTH_REGEN_FLAT, 5);
        assertEquals(expected, p.stats());
        assertTrue(p.complete());
    }

    @Test
    void fallsBackWhenStatsBlockIsMissingOrEmpty() {
        Map<String, Double> stats = Map.of("FlatMovementSpeedMod", 45.0);
        StatBlock expected = StatBlock.of(StatType.MOVE_SPEED, 45);
        assertEquals(expected, parse(synthetic("Ohne Block", "<mainText><br></mainText>", stats, 0)).stats());
        assertEquals(expected, parse(synthetic("Leerer Block", description(""), stats, 0)).stats());
        assertEquals(expected, parse(synthetic("Nur Leerzeichen", description(" <br> "), stats, 0)).stats());
    }

    @Test
    void unknownDdragonKeysAreReported() {
        Map<String, Double> stats = Map.of("PercentHPPoolMod", 0.1, "FlatArmorMod", 20.0, "rFlatGoldPer10Mod", 0.0);
        ParsedItemStats p = parse(synthetic("Unbekannt", "", stats, 0));
        assertEquals(StatBlock.of(StatType.ARMOR, 20), p.stats());
        assertEquals(List.of("PercentHPPoolMod = 0.1"), p.unparsed());
        assertFalse(p.complete());
    }

    @Test
    void otherLanguageUsesDdragonAndReportsUncoveredLines() {
        String html = description("<attention>12</attention> Magiedurchdringung<br><attention>45</attention> Lauftempo");
        ParsedItemStats p = parse(synthetic("Zauberschuhe", html, Map.of("FlatMovementSpeedMod", 45.0), 0));
        assertEquals(StatBlock.of(StatType.MOVE_SPEED, 45), p.stats());
        // "45 Lauftempo" ist durch FlatMovementSpeedMod abgedeckt, die Magiedurchdringung nicht
        assertEquals(List.of("12 Magiedurchdringung"), p.unparsed());
    }

    @Test
    void descriptionValuesTakePrecedenceOverDdragon() {
        String html = description("<attention>45</attention> Move Speed<br><attention>12</attention> Unbekannter Wert");
        ParsedItemStats p = parse(synthetic("Vorrang", html, Map.of("FlatMovementSpeedMod", 50.0), 0));
        assertEquals(StatBlock.of(StatType.MOVE_SPEED, 45), p.stats());
        assertEquals(List.of("12 Unbekannter Wert"), p.unparsed());
    }

    @Test
    void partiallyKnownBlockIsCompletedFromDdragon() {
        String html = description("<attention>15</attention> Ability Haste<br><attention>50</attention> Fähigkeitsstärke");
        ParsedItemStats p = parse(synthetic("Gemischt", html, Map.of("FlatMagicDamageMod", 50.0), 0));
        assertEquals(StatBlock.of(StatType.ABILITY_HASTE, 15).with(StatType.ABILITY_POWER, 50), p.stats());
        assertTrue(p.complete(), p.unparsed().toString());
    }

    @Test
    void fullyRecognisedBlockIgnoresDdragonExtras() {
        // wie Doran's Shield: die Map enthält zusätzlich die Regeneration der Passive
        Map<String, Double> stats = Map.of("FlatHPPoolMod", 110.0, "FlatHPRegenMod", 0.8);
        ParsedItemStats p = parse(synthetic("Doran", description("<attention>110</attention> Health"), stats, 450));
        assertEquals(StatBlock.of(StatType.HEALTH, 110), p.stats());
        assertTrue(p.complete());
    }

    @Test
    void linesWithoutNumberAreReported() {
        String html = description("<attention>30</attention> Ability Power<br>Unique: Something");
        ParsedItemStats p = parse(synthetic("Ohne Zahl", html, Map.of(), 0));
        assertEquals(StatBlock.of(StatType.ABILITY_POWER, 30), p.stats());
        assertEquals(List.of("Unique: Something"), p.unparsed());
    }

    @Test
    void percentOfFlatOnlyStatIsReported() {
        String html = description("<attention>10%</attention> Health");
        ParsedItemStats p = parse(synthetic("Prozent-Leben", html, Map.of(), 0));
        assertTrue(p.stats().isEmpty());
        assertEquals(List.of("10% Health"), p.unparsed());
    }
}
