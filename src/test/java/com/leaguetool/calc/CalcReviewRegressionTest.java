package com.leaguetool.calc;

import com.leaguetool.model.BaseStats;
import com.leaguetool.model.Item;
import com.leaguetool.model.ItemGold;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regressionstests zu den Befunden des Code-Reviews von Phase 5. */
class CalcReviewRegressionTest {

    @Test
    void scalingParsesOfPrefixNbspAndParentheses() {
        assertEquals(Optional.of(new Scaling(0.08, ScalingStat.MAX_HEALTH)), Scaling.parse("8% of maximum health"));
        assertEquals(Optional.of(new Scaling(0.5, ScalingStat.AP)), Scaling.parse("50 % AP"));
        assertEquals(Optional.of(new Scaling(0.6, ScalingStat.BONUS_AD)), Scaling.parse("(+  60% bonus AD)"));
        assertEquals(Optional.of(ScalingStat.BONUS_AD), ScalingStat.parse("(bonus AD)"));
        assertEquals(Optional.of(ScalingStat.AP), ScalingStat.parse("of AP"));
    }

    @Test
    void levelScalingUsesRawValuePerLevel() {
        Optional<Scaling> perLevel = Scaling.parse("10 per level");
        assertEquals(Optional.of(new Scaling(10, ScalingStat.LEVEL)), perLevel);
        DamageInput input = new DamageInput(1, 18, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertEquals(180.0, perLevel.orElseThrow().contribution(input), 1e-9);
        assertTrue(Scaling.parse("10% per level").isEmpty(), "Prozent pro Level ist mehrdeutig");
        assertTrue(Scaling.parse("10 AP").isEmpty(), "ohne % keine Ratio");
    }

    @Test
    void floatingPointRemaindersCancelOut() {
        StatBlock sum = StatBlock.of(StatType.MOVE_SPEED_PERCENT, 0.1)
                .plus(StatBlock.of(StatType.MOVE_SPEED_PERCENT, 0.2))
                .plus(StatBlock.of(StatType.MOVE_SPEED_PERCENT, -0.3));
        assertFalse(sum.has(StatType.MOVE_SPEED_PERCENT));
        assertTrue(sum.isEmpty());
    }

    @Test
    void fallbackDoesNotAddUnmatchedPassiveValues() {
        Item item = new Item(9001, "Testschild", "", "<mainText><stats><attention>110</attention> Health<br>"
                + "<attention>5</attention> Frobnication</stats></mainText>",
                new ItemGold(450, 450, 180, true), List.of(), Map.of("FlatHPPoolMod", 110.0, "FlatHPRegenMod", 1.2),
                List.of(), List.of(), Set.of(11), true, 1, "", false, "9001.png", 0);
        ParsedItemStats parsed = ItemStatParser.parse(item);
        assertEquals(110.0, parsed.stats().get(StatType.HEALTH));
        assertFalse(parsed.stats().has(StatType.HEALTH_REGEN_FLAT), "passive Regeneration zählt nicht");
        assertEquals(List.of("5 Frobnication"), parsed.unparsed());
    }

    @Test
    void attackSpeedFormulaIsConsistentBetweenCalculators() {
        BaseStats ahri = new BaseStats(590, 104, 418, 25, 330, 21, 4.2, 30, 1.3, 550, 2.5, 0.6, 8, 0.8, 0, 0,
                53, 3, 0.668, 2.2);
        for (int level = 1; level <= 18; level++) {
            double viaLevel = StatCalculator.atLevel(ahri, level).attackSpeed();
            double viaBuild = ChampionBuildCalculator.compute(ahri, level, StatBlock.EMPTY).attackSpeedRaw();
            assertEquals(viaLevel, viaBuild, 1e-12, "Level " + level);
        }
    }
}
