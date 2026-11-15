package com.leaguetool.calc;

import com.leaguetool.model.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.leaguetool.calc.TestItems.byId;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildCalculatorTest {

    @BeforeEach
    void useBaselinePassives() {
        ItemPassives.install(ItemPassives.baseline());
    }

    @Test
    void differentlyNamedPassivesAddUp() {
        StatBlock stats = BuildCalculator.totals(List.of(TestItems.withId(3073), TestItems.withId(3118))).stats();
        assertEquals(50, stats.get(StatType.ULTIMATE_HASTE));   // Hexcharged 30 + Scorn 20
    }

    @Test
    void sumsGoldAndStats() {
        BuildTotals t = BuildCalculator.totals(List.of(
                byId(TestItems.RABADONS_DEATHCAP),
                byId(TestItems.ZHONYAS_HOURGLASS),
                byId(TestItems.SORCERERS_SHOES)));
        StatBlock expected = StatBlock.of(StatType.ABILITY_POWER, 130 + 105)
                .with(StatType.ARMOR, 50)
                .with(StatType.MAGIC_PENETRATION, 12)
                .with(StatType.MOVE_SPEED, 45)
                .with(StatType.ABILITY_POWER_AMP, 30);
        assertEquals(expected, t.stats());
        assertEquals(3500 + 3250 + 1100, t.totalGold());
        assertEquals(3, t.itemCount());
        assertTrue(t.unparsed().isEmpty());
    }

    @Test
    void duplicateItemsCountTwice() {
        Item rod = byId(TestItems.NEEDLESSLY_LARGE_ROD);
        BuildTotals t = BuildCalculator.totals(List.of(rod, rod));
        assertEquals(StatBlock.of(StatType.ABILITY_POWER, 130), t.stats());
        assertEquals(2400, t.totalGold());
        assertEquals(2, t.itemCount());
    }

    @Test
    void duplicateItemsApplyTheirPassiveOnce() {
        Item rabadon = byId(TestItems.RABADONS_DEATHCAP);
        StatBlock stats = BuildCalculator.totals(List.of(rabadon, rabadon)).stats();
        assertEquals(260, stats.get(StatType.ABILITY_POWER));
        assertEquals(30, stats.get(StatType.ABILITY_POWER_AMP));
    }

    @Test
    void sameKindOfPassiveCountsOnlyWithItsStrongestValue() {
        StatBlock stats = BuildCalculator.totals(List.of(TestItems.withId(3003), TestItems.withId(3040))).stats();
        assertEquals(2, stats.get(StatType.AP_PER_BONUS_MANA));   // Archangel's 1 %, Seraph's 2 %
    }

    @Test
    void endlessHungerAddsFlatHasteFromItsPassive() {
        StatBlock stats = BuildCalculator.totals(List.of(TestItems.withId(2517))).stats();
        assertEquals(5, stats.get(StatType.ABILITY_HASTE));
        assertEquals(13, stats.get(StatType.HASTE_PER_BONUS_AD_MELEE));
        assertEquals(10, stats.get(StatType.HASTE_PER_BONUS_AD_RANGED));
    }

    @Test
    void emptyBuildIsEmpty() {
        BuildTotals t = BuildCalculator.totals(List.of());
        assertTrue(t.stats().isEmpty());
        assertEquals(0, t.totalGold());
        assertEquals(0, t.itemCount());
    }

    @Test
    void sixItemsAreAllowedSevenAreNot() {
        Item rod = byId(TestItems.NEEDLESSLY_LARGE_ROD);
        BuildTotals six = BuildCalculator.totals(Collections.nCopies(6, rod));
        assertEquals(6 * 65, six.stats().get(StatType.ABILITY_POWER));
        assertEquals(6 * 1200, six.totalGold());

        List<Item> seven = Collections.nCopies(7, rod);
        assertThrows(IllegalArgumentException.class, () -> BuildCalculator.totals(seven));
    }

    @Test
    void unparsedEntriesNameTheItem() {
        Item odd = TestItems.synthetic("Seltsames Item", "", Map.of("PercentHPPoolMod", 0.1), 100);
        BuildTotals t = BuildCalculator.totals(List.of(byId(TestItems.BOOTS), odd));
        assertEquals(List.of("Seltsames Item: PercentHPPoolMod = 0.1"), t.unparsed());
        assertEquals(400, t.totalGold());
    }
}
