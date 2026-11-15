package com.leaguetool.calc;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatBlockTest {

    @Test
    void missingValuesDefaultToZero() {
        assertTrue(StatBlock.EMPTY.isEmpty());
        assertEquals(0, StatBlock.EMPTY.get(StatType.ABILITY_POWER));
        assertFalse(StatBlock.EMPTY.has(StatType.ABILITY_POWER));
    }

    @Test
    void withReturnsNewBlockAndLeavesOriginalUnchanged() {
        StatBlock a = StatBlock.of(StatType.ABILITY_POWER, 65);
        StatBlock b = a.with(StatType.ARMOR, 25);
        assertEquals(1, a.size());
        assertEquals(0, a.get(StatType.ARMOR));
        assertEquals(65, b.get(StatType.ABILITY_POWER));
        assertEquals(25, b.get(StatType.ARMOR));
        assertEquals(30, b.with(StatType.ARMOR, 30).get(StatType.ARMOR));
    }

    @Test
    void zeroRemovesEntry() {
        StatBlock a = StatBlock.of(StatType.ABILITY_POWER, 65).with(StatType.ABILITY_POWER, 0);
        assertTrue(a.isEmpty());
        assertEquals(StatBlock.EMPTY, a);
        assertTrue(StatBlock.of(StatType.MANA, 0).isEmpty());
    }

    @Test
    void plusAddsValuesOfSameType() {
        StatBlock a = StatBlock.of(StatType.ABILITY_POWER, 65).with(StatType.MOVE_SPEED, 25);
        StatBlock b = StatBlock.of(StatType.ABILITY_POWER, 40).with(StatType.ARMOR, 25);
        StatBlock sum = a.plus(b);
        assertEquals(105, sum.get(StatType.ABILITY_POWER));
        assertEquals(25, sum.get(StatType.MOVE_SPEED));
        assertEquals(25, sum.get(StatType.ARMOR));
        assertEquals(3, sum.size());
        assertSame(a, a.plus(StatBlock.EMPTY));
        assertSame(b, StatBlock.EMPTY.plus(b));
    }

    @Test
    void plusDropsValuesThatCancelOut() {
        StatBlock sum = StatBlock.of(StatType.MOVE_SPEED, 25).plus(StatBlock.of(StatType.MOVE_SPEED, -25));
        assertTrue(sum.isEmpty());
    }

    @Test
    void entriesAreSortedByEnumOrder() {
        StatBlock block = StatBlock.of(StatType.TENACITY, 30)
                .with(StatType.HEALTH, 300)
                .with(StatType.ABILITY_HASTE, 10)
                .with(StatType.ATTACK_DAMAGE, 20);
        List<StatType> order = block.entries().stream().map(Map.Entry::getKey).toList();
        assertEquals(List.of(StatType.HEALTH, StatType.ATTACK_DAMAGE, StatType.ABILITY_HASTE, StatType.TENACITY),
                order);
        assertEquals(300.0, block.entries().get(0).getValue());
        assertThrows(UnsupportedOperationException.class, () -> block.entries().clear());
        assertThrows(UnsupportedOperationException.class, () -> block.asMap().put(StatType.MANA, 1.0));
    }

    @Test
    void ofMapCopiesAndSkipsZeros() {
        Map<StatType, Double> map = new EnumMap<>(StatType.class);
        map.put(StatType.ARMOR, 50.0);
        map.put(StatType.MANA, 0.0);
        StatBlock block = StatBlock.of(map);
        map.put(StatType.ARMOR, 1.0);
        assertEquals(50, block.get(StatType.ARMOR));
        assertEquals(1, block.size());
        assertSame(StatBlock.EMPTY, StatBlock.of(Map.of()));
    }

    @Test
    void equalityIsValueBased() {
        StatBlock a = StatBlock.of(StatType.ARMOR, 50).with(StatType.ABILITY_POWER, 105);
        StatBlock b = StatBlock.of(StatType.ABILITY_POWER, 105).with(StatType.ARMOR, 50);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, b.with(StatType.ARMOR, 51));
    }

    @Test
    void rejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> StatBlock.of(StatType.ARMOR, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> StatBlock.EMPTY.with(StatType.ARMOR, Double.POSITIVE_INFINITY));
        assertThrows(NullPointerException.class, () -> StatBlock.EMPTY.with(null, 1));
    }

    @Test
    void statTypesHaveGermanLabelsAndPercentFlags() {
        for (StatType t : StatType.values()) {
            assertFalse(t.label().isBlank(), t.name());
        }
        assertEquals("Fähigkeitstempo", StatType.ABILITY_HASTE.label());
        assertTrue(StatType.ATTACK_SPEED.percent());
        assertTrue(StatType.CRIT_CHANCE.percent());
        assertTrue(StatType.MOVE_SPEED_PERCENT.percent());
        assertTrue(StatType.ARMOR_PENETRATION.percent());
        assertTrue(StatType.MAGIC_PENETRATION_PERCENT.percent());
        assertTrue(StatType.HEALTH_REGEN.percent());
        assertFalse(StatType.MOVE_SPEED.percent());
        assertFalse(StatType.MAGIC_PENETRATION.percent());
        assertFalse(StatType.LETHALITY.percent());
    }

    @Test
    void formatsValueWithUnit() {
        assertEquals("45 Lauftempo", StatType.MOVE_SPEED.format(45));
        assertEquals("4 % Lauftempo", StatType.MOVE_SPEED_PERCENT.format(4));
        assertEquals("2.5 %", StatType.OMNIVAMP.formatValue(2.5));
    }
}
