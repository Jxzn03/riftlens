package com.leaguetool.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.leaguetool.calc.StatBlock;
import com.leaguetool.calc.StatType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DtoStatRowsTest {

    @Test
    void rowsCarryKeyAmountAndPercentForSortingAndFiltering() {
        StatBlock block = StatBlock.of(StatType.MOVE_SPEED_PERCENT, 4)
                .plus(StatBlock.of(StatType.ATTACK_DAMAGE, 45))
                .plus(StatBlock.of(StatType.MOVE_SPEED, 25));

        List<Map<String, Object>> rows = Dto.statRows(block);

        assertEquals(List.of("ATTACK_DAMAGE", "MOVE_SPEED", "MOVE_SPEED_PERCENT"),
                rows.stream().map(r -> r.get("key")).toList());
        Map<String, Object> percent = rows.get(2);
        assertEquals("Lauftempo", percent.get("label"));
        assertEquals("4 %", percent.get("value"));
        assertEquals(4.0, percent.get("amount"));
        assertEquals(true, percent.get("percent"));
        assertEquals(false, rows.get(1).get("percent"));
    }
}
