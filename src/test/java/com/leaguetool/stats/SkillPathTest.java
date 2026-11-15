package com.leaguetool.stats;

import com.leaguetool.model.AbilitySlot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.leaguetool.model.AbilitySlot.E;
import static com.leaguetool.model.AbilitySlot.Q;
import static com.leaguetool.model.AbilitySlot.R;
import static com.leaguetool.model.AbilitySlot.W;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillPathTest {

    private static final List<AbilitySlot> QEW = List.of(Q, E, W);

    @Test
    void continuesTheStartAlongTheMaxOrder() {
        assertEquals(List.of(Q, E, W, Q, Q, R, Q, E, Q), SkillPath.firstLevels(List.of(Q, E, W, Q, Q, R), QEW));
    }

    @Test
    void respectsTheRankLimitOfEachLevel() {
        // Q steht nach Level 7 auf Rang 4 und darf erst auf Level 9 Rang 5 erreichen.
        List<AbilitySlot> path = SkillPath.firstLevels(List.of(W, Q, E, Q, Q, R), List.of(Q, W, E));
        assertEquals(List.of(W, Q, E, Q, Q, R, Q, W, Q), path);
    }

    @Test
    void anUntakenUltimateComesFirst() {
        assertEquals(R, SkillPath.firstLevels(List.of(Q, E, W, Q, E, Q), QEW).get(6));
    }

    @Test
    void anEarlyUltimateIsNotTakenAgain() {
        // Champions mit R ab Level 1 (z. B. Jayce) bekommen die Punkte weiter nach Max-Reihenfolge.
        assertEquals(List.of(R, Q, E, Q, W, Q, Q, E, Q), SkillPath.firstLevels(List.of(R, Q, E, Q, W, Q), QEW));
    }

    @Test
    void missingDataGivesNoPath() {
        assertTrue(SkillPath.firstLevels(List.of(), QEW).isEmpty());
        assertTrue(SkillPath.firstLevels(List.of(Q, E, W, Q, Q, R), List.of()).isEmpty());
    }

    @Test
    void aMaxOrderThatCannotFillTheLevelsGivesNoPath() {
        assertEquals(SkillPath.LEVELS, SkillPath.firstLevels(List.of(Q, E, W, Q, Q, R), List.of(W)).size());
        assertTrue(SkillPath.firstLevels(List.of(Q, Q, Q, Q, Q, R), List.of(Q)).isEmpty());
    }
}
