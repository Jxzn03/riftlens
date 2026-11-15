package com.leaguetool.calc;

import com.leaguetool.model.BaseStats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatCalculatorTest {

    private static final double EPS = 1e-9;

    /** Ahri laut Data Dragon (AD-Wachstum zum Testen auf 3 gesetzt). */
    static final BaseStats AHRI = new BaseStats(
            590, 104,
            418, 25,
            330,
            21, 4.2,
            30, 1.3,
            550,
            2.5, 0.6,
            8, 0.8,
            0, 0,
            53, 3,
            0.668, 2.2);

    @Test
    void growthMultiplierIsZeroAtLevelOneAndSeventeenAtLevelEighteen() {
        assertEquals(0, StatCalculator.growthMultiplier(1), EPS);
        assertEquals(0.72, StatCalculator.growthMultiplier(2), EPS);
        assertEquals(17, StatCalculator.growthMultiplier(18), EPS);
    }

    @Test
    void healthScalesWithOfficialFormula() {
        assertEquals(590, StatCalculator.statAtLevel(590, 104, 1), EPS);
        assertEquals(590 + 104 * 0.72, StatCalculator.statAtLevel(590, 104, 2), EPS);
        assertEquals(2358, StatCalculator.statAtLevel(590, 104, 18), EPS);
    }

    @Test
    void attackSpeedGrowsByPercentPerLevel() {
        assertEquals(0.668, StatCalculator.attackSpeedAtLevel(0.668, 2.2, 1), EPS);
        assertEquals(0.668 * (1 + 0.022 * 17), StatCalculator.attackSpeedAtLevel(0.668, 2.2, 18), EPS);
    }

    @Test
    void levelIsClampedToOneToEighteen() {
        assertEquals(1, StatCalculator.clampLevel(0));
        assertEquals(1, StatCalculator.clampLevel(-5));
        assertEquals(7, StatCalculator.clampLevel(7));
        assertEquals(18, StatCalculator.clampLevel(19));
        assertEquals(StatCalculator.statAtLevel(590, 104, 18), StatCalculator.statAtLevel(590, 104, 30), EPS);
        assertEquals(590, StatCalculator.statAtLevel(590, 104, 0), EPS);
    }

    @Test
    void atLevelOneReturnsBaseValues() {
        ChampionLevelStats s = StatCalculator.atLevel(AHRI, 1);
        assertEquals(new ChampionLevelStats(1, 590, 2.5, 418, 8, 21, 30, 53, 0.668, 550, 330, 0), s);
    }

    @Test
    void atLevelEighteenScalesAllGrowingStats() {
        ChampionLevelStats s = StatCalculator.atLevel(AHRI, 18);
        assertEquals(18, s.level());
        assertEquals(2358, s.hp(), EPS);
        assertEquals(2.5 + 0.6 * 17, s.hpRegen(), EPS);
        assertEquals(418 + 25 * 17, s.mp(), EPS);
        assertEquals(8 + 0.8 * 17, s.mpRegen(), EPS);
        assertEquals(21 + 4.2 * 17, s.armor(), EPS);
        assertEquals(30 + 1.3 * 17, s.magicResist(), EPS);
        assertEquals(53 + 3 * 17, s.attackDamage(), EPS);
        assertEquals(0.668 * (1 + 0.022 * 17), s.attackSpeed(), EPS);
        assertEquals(550, s.attackRange(), EPS);
        assertEquals(330, s.moveSpeed(), EPS);
        assertEquals(0, s.critChance(), EPS);
    }

    @Test
    void atLevelClampsLevel() {
        assertEquals(StatCalculator.atLevel(AHRI, 18), StatCalculator.atLevel(AHRI, 25));
        assertEquals(1, StatCalculator.atLevel(AHRI, 0).level());
    }
}
