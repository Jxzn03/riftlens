package com.leaguetool.draft;

import com.leaguetool.draft.RolePredictor.Slot;
import com.leaguetool.model.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoleOrderTest {

    private static List<Integer> champions(List<Slot> slots) {
        return slots.stream().map(Slot::champion).toList();
    }

    @Test
    void teamWithAllFixedRolesIsOrderedTopToSupport() {
        List<Slot> team = List.of(new Slot(1, Role.UTILITY), new Slot(2, Role.TOP), new Slot(3, Role.MIDDLE),
                new Slot(4, Role.BOTTOM), new Slot(5, Role.JUNGLE));
        List<Slot> sorted = RoleOrder.sort(team);
        assertEquals(List.of(2, 5, 3, 4, 1), champions(sorted));
        assertEquals(Role.UTILITY, sorted.get(4).forcedRole(), "Rollen bleiben unverändert");
    }

    @Test
    void teamWithMissingOrDoubleRolesKeepsItsStoredOrder() {
        List<Slot> partial = List.of(new Slot(10, null), new Slot(11, Role.UTILITY), new Slot(12, null), new Slot(13, Role.TOP),
                new Slot(14, Role.BOTTOM));
        assertEquals(List.of(10, 11, 12, 13, 14), champions(RoleOrder.sort(partial)), "Keine Vorhersage, nichts umsortiert");

        List<Slot> doubled = List.of(new Slot(1, Role.TOP), new Slot(2, Role.TOP), new Slot(3, Role.MIDDLE),
                new Slot(4, Role.BOTTOM), new Slot(5, Role.JUNGLE));
        assertEquals(List.of(1, 2, 3, 4, 5), champions(RoleOrder.sort(doubled)));
    }

    @Test
    void shownRolesOrderTeamsWithoutFixedRoles() {
        List<Slot> enemy = List.of(new Slot(6, null), new Slot(7, null), new Slot(8, Role.MIDDLE), new Slot(9, null), new Slot(10, null));
        java.util.Map<Integer, Role> shown = java.util.Map.of(6, Role.UTILITY, 7, Role.TOP, 9, Role.JUNGLE, 10, Role.BOTTOM);
        List<Slot> sorted = RoleOrder.sort(enemy, shown);
        assertEquals(List.of(7, 9, 8, 10, 6), champions(sorted));
        assertEquals(null, sorted.get(0).forcedRole(), "Angezeigte Rolle wird nicht fest");

        // Fehlt eine angezeigte Rolle, bleibt die Reihenfolge
        assertEquals(List.of(6, 7, 8, 9, 10), champions(RoleOrder.sort(enemy, java.util.Map.of(6, Role.UTILITY))));
    }
}
