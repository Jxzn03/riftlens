package com.leaguetool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.leaguetool.data.GameData;
import com.leaguetool.draft.DraftArchive;
import com.leaguetool.draft.DraftArchive.Draft;
import com.leaguetool.draft.DraftArchive.Entry;
import com.leaguetool.draft.RoleOrder;
import com.leaguetool.draft.RolePredictor.Slot;

import java.io.IOException;
import java.util.List;
import java.util.Map;

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

    /** Das Archiv selbst; die Fearless-Serien teilen sich diese Instanz. */
    DraftArchive drafts() {
        return archive;
    }

    Map<String, Object> list() throws IOException {
        int limit = ctx.settings().draftArchiveLimit();
        return Dto.obj("limit", limit, "minChanges", DraftArchive.MIN_CHANGES,
                "entries", archive.entries(limit).stream().map(DraftArchiveEndpoint::entry).toList(),
                "trashDays", DraftArchive.TRASH_RETENTION.toDays(),
                "trash", archive.trash().stream().map(t -> {
                    Map<String, Object> item = entry(t.entry());
                    item.put("deletedAt", t.deletedAt().toString());
                    item.put("expiresAt", t.expiresAt().toString());
                    return item;
                }).toList());
    }

    /** {@code POST /api/draft-archive/<id>/restore}: holt einen Eintrag aus dem Papierkorb zurück. */
    Object restore(List<String> path) throws IOException {
        if (path.size() != 3 || !path.get(1).matches(ID_PATTERN) || !"restore".equals(path.get(2))) {
            throw new ApiException(404, "Unbekannter Endpunkt");
        }
        if (!archive.restore(path.get(1))) {
            throw new ApiException(404, "Draft nicht im Papierkorb");
        }
        return list();
    }

    Object record(JsonNode body) throws IOException {
        GameData data = ctx.awaitData();
        Draft board = DraftEndpoint.board(body, data, "Nur vollständige Drafts werden archiviert.");
        DraftArchive.Outcome outcome = archive.record(board, data.patch(),
                FearlessEndpoint.shownRoles(body.path("shownRoles"), board.ally(), board.enemy()), ctx.settings().draftArchiveLimit());
        return Dto.obj("saved", outcome.saved(), "changes", outcome.changes(), "minChanges", DraftArchive.MIN_CHANGES,
                "entry", entry(outcome.entry()));
    }

    /** Gibt geänderte Rollen an gleiche Fearless-Spiele weiter; liefert deren Anzahl. */
    interface RoleSync {
        int apply(Draft changed) throws IOException;
    }

    /**
     * {@code PUT /api/draft-archive/<id> {ally, enemy}}: überschreibt nur die festen Rollen eines Eintrags. Andere
     * Champions → 400 (dafür bleibt die normale Aufnahme zuständig). Gleiche Fearless-Spiele gehen mit ({@code seriesUpdated}).
     */
    Object updateRoles(List<String> path, JsonNode body, RoleSync series) throws IOException {
        if (path.size() != 2 || !path.get(1).matches(ID_PATTERN)) {
            throw new ApiException(404, "Unbekannter Endpunkt");
        }
        Entry updated;
        try {
            List<Slot> ally = DraftEndpoint.storedSlots(body.path("ally"));
            List<Slot> enemy = DraftEndpoint.storedSlots(body.path("enemy"));
            updated = archive.updateRoles(path.get(1), ally, enemy, FearlessEndpoint.shownRoles(body.path("shownRoles"), ally, enemy));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        if (updated == null) {
            throw new ApiException(404, "Draft nicht im Archiv");
        }
        Map<String, Object> response = new java.util.LinkedHashMap<>(list());
        response.put("entry", entry(updated));
        // Gleiche Spiele einer Fearless-Serie bekommen die Rollen mit.
        response.put("seriesUpdated", series.apply(updated.draft()));
        return response;
    }

    /**
     * {@code DELETE /api/draft-archive/<id>} legt in den Papierkorb, {@code …/trash/<id>} löscht daraus endgültig,
     * {@code …/trash} leert ihn.
     */
    Object delete(List<String> path) throws IOException {
        if (path.size() == 2 && "trash".equals(path.get(1))) {
            archive.emptyTrash();
            return list();
        }
        if (path.size() == 3 && "trash".equals(path.get(1)) && path.get(2).matches(ID_PATTERN)) {
            if (!archive.purge(path.get(2))) {
                throw new ApiException(404, "Draft nicht im Papierkorb");
            }
            return list();
        }
        if (path.size() != 2 || !path.get(1).matches(ID_PATTERN)) {
            throw new ApiException(404, "Unbekannter Endpunkt");
        }
        if (!archive.delete(path.get(1))) {
            throw new ApiException(404, "Draft nicht im Archiv");
        }
        return list();
    }

    /**
     * Ein Eintrag für die Anzeige: Teams, in denen alle fünf eine Rolle haben (fest oder die beim Speichern angezeigte
     * Auto-Rolle), Top bis Support geordnet
     * ({@link RoleOrder}, wie bei Fearless); sonst die gespeicherte Reihenfolge. Nur aus Gespeichertem, ohne
     * Vorhersage oder Abfrage; die Datei bleibt unverändert.
     */
    private static Map<String, Object> entry(Entry entry) {
        Draft draft = RoleOrder.sort(entry.draft(), entry.shownRoles());
        return Dto.obj("id", entry.id(), "savedAt", entry.savedAt().toString(), "patch", entry.patch(),
                "ally", DraftEndpoint.slotsJson(draft.ally()), "enemy", DraftEndpoint.slotsJson(draft.enemy()), "bans", draft.bans());
    }
}
