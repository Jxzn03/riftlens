package com.leaguetool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.leaguetool.data.GameData;
import com.leaguetool.draft.DraftArchive;
import com.leaguetool.draft.DraftArchive.Draft;
import com.leaguetool.draft.DraftArchive.Entry;
import com.leaguetool.draft.RolePredictor.Slot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code GET /api/draft-archive} listet die archivierten Drafts, {@code POST /api/draft-archive} nimmt einen
 * vollständigen Draft vom Brett auf (wenn er neu genug ist), {@code DELETE /api/draft-archive/<id>} entfernt einen.
 *
 * <p>Nur lokal: Die Daten kommen ausschließlich vom Draft-Brett (Champions, feste Rollen, Bans) und liegen in
 * {@value #FILE_NAME} im Nutzerordner. Es gibt dafür keine Abfrage beim League-Client, bei Riot oder Lolalytics.
 */
final class DraftArchiveEndpoint {

    static final String FILE_NAME = "draft-archive.json";
    private static final String ID_PATTERN = "[0-9a-f-]{36}";

    private final WebContext ctx;
    private final DraftArchive archive;

    DraftArchiveEndpoint(WebContext ctx) {
        this.ctx = ctx;
        this.archive = new DraftArchive(ctx.settings().home().resolve(FILE_NAME));
    }

    Object list() throws IOException {
        int limit = ctx.settings().draftArchiveLimit();
        return Dto.obj("limit", limit, "minChanges", DraftArchive.MIN_CHANGES,
                "entries", archive.entries(limit).stream().map(DraftArchiveEndpoint::entry).toList());
    }

    Object record(JsonNode body) throws IOException {
        GameData data = ctx.awaitData();
        List<Slot> ally = DraftEndpoint.slots(body.path("ally"), data);
        List<Slot> enemy = DraftEndpoint.slots(body.path("enemy"), data);
        if (ally.size() != DraftArchive.TEAM_SIZE || enemy.size() != DraftArchive.TEAM_SIZE) {
            throw new ApiException(400, "Nur vollständige Drafts werden archiviert.");
        }
        DraftEndpoint.requireDistinct(ally, enemy);
        JsonNode banList = body.path("bans");
        if (banList.size() > DraftArchive.MAX_BANS) {
            throw new ApiException(400, "Höchstens " + DraftArchive.MAX_BANS + " Bans.");
        }
        Set<Integer> bans = new LinkedHashSet<>();
        banList.forEach(node -> bans.add(DraftEndpoint.championKey(node, data)));

        DraftArchive.Outcome outcome = archive.record(new Draft(ally, enemy, new ArrayList<>(bans)), data.patch(),
                ctx.settings().draftArchiveLimit());
        return Dto.obj("saved", outcome.saved(), "changes", outcome.changes(), "minChanges", DraftArchive.MIN_CHANGES,
                "entry", entry(outcome.entry()));
    }

    Object delete(List<String> path) throws IOException {
        if (path.size() != 2 || !path.get(1).matches(ID_PATTERN)) {
            throw new ApiException(404, "Unbekannter Endpunkt");
        }
        if (!archive.delete(path.get(1))) {
            throw new ApiException(404, "Draft nicht im Archiv");
        }
        return list();
    }

    private static Map<String, Object> entry(Entry entry) {
        Draft draft = entry.draft();
        return Dto.obj("id", entry.id(), "savedAt", entry.savedAt().toString(), "patch", entry.patch(),
                "ally", slots(draft.ally()), "enemy", slots(draft.enemy()), "bans", draft.bans());
    }

    private static List<Map<String, Object>> slots(List<Slot> slots) {
        return slots.stream().map(slot -> Dto.obj("champion", slot.champion(),
                "role", slot.forcedRole() == null ? null : slot.forcedRole().name())).toList();
    }
}
