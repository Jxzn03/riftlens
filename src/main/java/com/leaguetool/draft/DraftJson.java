package com.leaguetool.draft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leaguetool.draft.DraftArchive.Draft;
import com.leaguetool.draft.RolePredictor.Slot;
import com.leaguetool.model.Role;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Gemeinsames Dateiformat eines Bretts im Draft-Archiv und in Fearless-Serien. */
final class DraftJson {

    private DraftJson() {
    }

    /** Schreibt {@code ally}, {@code enemy}, {@code bans} und – nur wenn vorhanden – {@code shownRoles}. */
    static void writeDraft(ObjectNode node, Draft draft, Map<Integer, Role> shownRoles) {
        writeSlots(node.putArray("ally"), draft.ally());
        writeSlots(node.putArray("enemy"), draft.enemy());
        ArrayNode bans = node.putArray("bans");
        draft.bans().forEach(bans::add);
        if (!shownRoles.isEmpty()) {
            ObjectNode shown = node.putObject("shownRoles");
            shownRoles.forEach((champion, role) -> shown.put(String.valueOf(champion), role.name()));
        }
    }

    /**
     * Das Brett eines Eintrags, Bans auf {@link DraftArchive#MAX_BANS} gekürzt; {@code null}, wenn es unvollständig ist.
     *
     * @throws RuntimeException bei beschädigten Slots
     */
    static Draft readDraft(JsonNode node) {
        List<Integer> bans = new ArrayList<>();
        node.path("bans").forEach(ban -> bans.add(ban.asInt()));
        Draft draft = new Draft(slots(node.path("ally")), slots(node.path("enemy")),
                bans.subList(0, Math.min(DraftArchive.MAX_BANS, bans.size())));
        return draft.complete() ? draft : null;
    }

    /** Angezeigte Auto-Rollen; unbekannte Einträge fallen einzeln weg. */
    static Map<Integer, Role> readShownRoles(JsonNode node) {
        Map<Integer, Role> shown = new HashMap<>();
        for (Map.Entry<String, JsonNode> e : node.path("shownRoles").properties()) {
            try {
                shown.put(Integer.parseInt(e.getKey()), Role.valueOf(e.getValue().asText()));
            } catch (IllegalArgumentException ignored) {
                // unbekannter Eintrag: nur diese Rolle fehlt
            }
        }
        return shown;
    }

    private static void writeSlots(ArrayNode target, List<Slot> slots) {
        for (Slot slot : slots) {
            ObjectNode node = target.addObject();
            node.put("champion", slot.champion());
            node.put("role", slot.forcedRole() == null ? null : slot.forcedRole().name());
        }
    }

    private static List<Slot> slots(JsonNode list) {
        List<Slot> slots = new ArrayList<>();
        for (JsonNode node : list) {
            int champion = node.path("champion").asInt(0);
            if (champion <= 0) {
                throw new IllegalArgumentException("Champion fehlt");
            }
            JsonNode role = node.path("role");
            boolean auto = role.isNull() || role.isMissingNode() || role.asText().isEmpty();
            slots.add(new Slot(champion, auto ? null : Role.valueOf(role.asText())));
        }
        return slots;
    }
}
