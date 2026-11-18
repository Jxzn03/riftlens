package com.leaguetool.draft;

import com.leaguetool.draft.DraftArchive.Draft;
import com.leaguetool.draft.RolePredictor.Slot;
import com.leaguetool.model.Role;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Ordnet ein Team von Top über Jungle, Mid und ADC bis Support. Es zählt die feste Rolle, sonst die Rolle, die das Brett
 * beim Abschließen angezeigt hat ({@code shownRoles}). Keine neue Vorhersage, keine Daten von Lolalytics. Umsortiert wird
 * nur, wenn so jeder Champion eine eigene Rolle hat; sonst bleibt die gespeicherte Reihenfolge. Reine Anzeige: Rollen
 * bleiben, wie sie sind.
 */
public final class RoleOrder {

    private RoleOrder() {
    }

    public static List<Slot> sort(List<Slot> team) {
        return sort(team, Map.of());
    }

    public static List<Slot> sort(List<Slot> team, Map<Integer, Role> shownRoles) {
        List<Role> roles = team.stream()
                .map(slot -> slot.forcedRole() != null ? slot.forcedRole() : shownRoles.get(slot.champion())).toList();
        if (!roles.stream().allMatch(Objects::nonNull) || roles.stream().distinct().count() != team.size()) {
            return team;
        }
        List<Slot> sorted = new ArrayList<>(team);
        sorted.sort(Comparator.comparingInt(slot -> roles.get(team.indexOf(slot)).ordinal()));
        return sorted;
    }

    public static Draft sort(Draft draft, Map<Integer, Role> shownRoles) {
        return new Draft(sort(draft.ally(), shownRoles), sort(draft.enemy(), shownRoles), draft.bans());
    }
}
