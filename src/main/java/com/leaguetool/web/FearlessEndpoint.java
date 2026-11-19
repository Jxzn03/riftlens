package com.leaguetool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.leaguetool.data.GameData;
import com.leaguetool.draft.DraftArchive;
import com.leaguetool.draft.DraftArchive.Draft;
import com.leaguetool.draft.FearlessSeries;
import com.leaguetool.draft.FearlessSeries.Format;
import com.leaguetool.draft.FearlessSeries.Game;
import com.leaguetool.draft.FearlessSeries.Result;
import com.leaguetool.draft.FearlessStore;
import com.leaguetool.draft.RoleOrder;
import com.leaguetool.draft.RolePredictor.Slot;
import com.leaguetool.model.Role;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fearless-Serien (Hard Fearless) für den Draft-Tab:
 * <ul>
 *   <li>{@code GET /api/fearless}: laufende Serie, ihre Sperren und die beendeten Serien</li>
 *   <li>{@code POST /api/fearless {format, maxGames?, label}}: Serie starten (Spielzahl nur bei {@code OPEN})</li>
 *   <li>{@code POST /api/fearless/game {ally, enemy, bans, result}}: Spiel abschließen; {@code POST /api/fearless/<id>/game {draftId}}:
 *   Archiv-Draft an eine nicht volle Serie anhängen</li>
 *   <li>{@code DELETE /api/fearless/game}: letztes Spiel zurücknehmen, liefert dessen Draft</li>
 *   <li>{@code POST /api/fearless/end}: Serie beenden</li>
 *   <li>{@code PUT /api/fearless/<id> {label, maxGames, order}}: umbenennen, Spielzahl (offen) ändern bzw. Spiele neu ordnen, {@code DELETE /api/fearless/<id>}: Serie (auch laufende) in den
 *   Papierkorb legen, laufende ohne Spiel verwerfen</li>
 *   <li>{@code DELETE /api/fearless/<id>/game/<n>}: ein einzelnes Spiel ohne Papierkorb aus der Serie lösen (z. B. falsch
 *   zugeordnet), der Draft bleibt im Archiv</li>
 *   <li>{@code PUT /api/fearless/<id>/game/<n> {result} bzw. {ally, enemy}}: Ergebnis bzw. feste Rollen eines Spiels ändern</li>
 *   <li>{@code POST /api/fearless/<id>/restore}: Serie aus dem Papierkorb zurückholen, {@code DELETE /api/fearless/trash/<id>}:
 *   endgültig löschen, {@code DELETE /api/fearless/trash}: Papierkorb leeren</li>
 * </ul>
 *
 * <p>Nur lokal in {@value #FILE_NAME}. Die Daten kommen ausschließlich vom Draft-Brett und aus der Eingabe des Nutzers;
 * es gibt keine Abfrage beim League-Client, bei Riot oder Lolalytics. Die Bezeichnung geht nirgendwohin hinaus. Die
 * Reihenfolge Top bis Support kommt nur aus den festgelegten Rollen ({@link RoleOrder}).
 */
final class FearlessEndpoint {

    static final String FILE_NAME = "fearless-series.json";

    private final WebContext ctx;
    private final FearlessStore store;
    private final DraftArchive drafts;

    /** @param drafts das Draft-Archiv (dieselbe Instanz wie in {@link DraftArchiveEndpoint}, sonst veraltet sein Speicherstand) */
    FearlessEndpoint(WebContext ctx, DraftArchive drafts) {
        this.ctx = ctx;
        this.drafts = drafts;
        this.store = new FearlessStore(ctx.settings().home().resolve(FILE_NAME));
    }

    Object handle(String method, List<String> path, JsonNode body) throws IOException {
        backfillArchive();
        String sub = path.size() > 1 ? path.get(1) : "";
        if (path.size() == 3 && "POST".equals(method) && sub.matches(Input.ID_PATTERN) && "game".equals(path.get(2))) {
            return addDraftTo(sub, body);
        }
        if (path.size() == 3) {
            return trashAction(method, sub, path.get(2));
        }
        if (path.size() == 4 && sub.matches(Input.ID_PATTERN) && "game".equals(path.get(2))) {
            int number = Input.intValue(path.get(3), 404, "Unbekannter Endpunkt");
            if ("DELETE".equals(method)) {
                return removeGame(sub, number);
            }
            if ("PUT".equals(method)) {
                if (body.has("result")) {
                    return updateGameResult(sub, number, body);
                }
                return body.path("replace").asBoolean(false) ? replaceGame(sub, number, body) : updateGameRoles(sub, number, body);
            }
        }
        if (path.size() > 3) {
            throw new ApiException(404, "Unbekannter Endpunkt");
        }
        if ("DELETE".equals(method) && "trash".equals(sub)) {
            store.emptyTrash();
            return state();
        }
        return switch (method + " " + sub) {
            case "GET " -> state();
            case "POST " -> start(body);
            case "POST game" -> addGame(body);
            case "DELETE game" -> undo();
            case "POST end" -> {
                store.end(limit());
                yield state();
            }
            default -> {
                if (!sub.matches(Input.ID_PATTERN)) {
                    throw new ApiException(404, "Unbekannter Endpunkt");
                }
                yield switch (method) {
                    case "PUT" -> update(sub, body);
                    case "DELETE" -> delete(sub);
                    default -> throw new ApiException(404, "Unbekannter Endpunkt");
                };
            }
        };
    }

    /** {@code POST /api/fearless/<id>/restore} und {@code DELETE /api/fearless/trash/<id>}. */
    private Object trashAction(String method, String first, String second) throws IOException {
        if ("POST".equals(method) && first.matches(Input.ID_PATTERN) && "restore".equals(second)) {
            FearlessSeries series = store.trash().stream().map(FearlessStore.TrashedSeries::series)
                    .filter(s -> s.id().equals(first)).findFirst().orElse(null);
            if (series == null || !store.restore(first)) {
                throw new ApiException(404, "Serie nicht im Papierkorb");
            }
            // Mit der Serie kommen ihre Spiele zurück, die beim Löschen aus dem Draft-Archiv mitgingen.
            Map<String, Object> response = state();
            response.put("draftsRestored", drafts.restoreMatching(gameDrafts(series)));
            return response;
        }
        if ("DELETE".equals(method) && "trash".equals(first) && second.matches(Input.ID_PATTERN)) {
            if (!store.purge(second)) {
                throw new ApiException(404, "Nicht im Papierkorb");
            }
            return state();
        }
        throw new ApiException(404, "Unbekannter Endpunkt");
    }

    private int limit() {
        return ctx.settings().fearlessSeriesLimit();
    }

    /** Die Serie mit je Team nach Rolle geordneten Spielen (nur Anzeige, gespeichert bleibt die Reihenfolge). */
    private static FearlessSeries ordered(FearlessSeries series) {
        return new FearlessSeries(series.id(), series.label(), series.format(), series.maxGames(), series.startedAt(), series.endedAt(),
                series.games().stream().map(FearlessEndpoint::ordered).toList());
    }

    private static Game ordered(Game game) {
        return new Game(game.number(), game.playedAt(), game.patch(), RoleOrder.sort(game.draft(), game.shownRoles()), game.result(),
                game.shownRoles());
    }

    Map<String, Object> state() throws IOException {
        FearlessSeries active = store.active().map(FearlessEndpoint::ordered).orElse(null);
        return Dto.obj(
                "active", active == null ? null : series(active),
                // Je Spiel zuerst dein Team, dann der Gegner, jeweils Top bis Support (Sperrleiste im Tab).
                "locked", active == null ? List.of() : locked(active.locked()),
                "finished", store.finished(limit()).stream().map(s -> series(ordered(s))).toList(),
                "trashDays", com.leaguetool.draft.DraftArchive.TRASH_RETENTION.toDays(),
                "trash", trash(),
                "formats", Arrays.stream(Format.values()).map(Format::name).toList(),
                "maxLabelLength", FearlessSeries.MAX_LABEL_LENGTH,
                "maxGames", FearlessSeries.MAX_GAMES);
    }

    /** Papierkorb: gelöschte Serien, zuletzt gelöschte zuerst. */
    private List<Map<String, Object>> trash() throws IOException {
        List<Map<String, Object>> items = new ArrayList<>();
        for (FearlessStore.TrashedSeries t : store.trash()) {
            items.add(Dto.trashed(series(ordered(t.series())), t.deletedAt(), t.expiresAt()));
        }
        return items;
    }

    /**
     * {@code DELETE /api/fearless/<id>/game/<n>}: löst ein (z. B. falsch zugeordnetes) Spiel ohne Papierkorb aus der
     * Serie; die übrigen Spiele rücken nach, ihre Sperren folgen daraus. Der Draft bleibt unter „Drafts“: Fehlt er dort
     * ganz (auch nicht im Papierkorb), wird er aus dem Spiel neu angelegt ({@code draftRecreated}).
     */
    private Object removeGame(String id, int number) throws IOException {
        Game removed;
        try {
            removed = store.removeGame(id, number);
        } catch (IllegalArgumentException e) {
            throw new ApiException(404, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ApiException(409, e.getMessage());
        }
        if (removed == null) {
            throw new ApiException(404, "Serie nicht gefunden");
        }
        Map<String, Object> response = state();
        response.put("draftRecreated", removed.draft().complete()
                && drafts.reconstruct(removed.draft(), removed.playedAt(), removed.patch(), removed.shownRoles()) != null);
        return response;
    }

    /**
     * {@code PUT /api/fearless/<id>/game/<n> {replace: true, ally, enemy, bans, shownRoles}}: ersetzt den Draft eines Spiels
     * (z. B. aus dem Archiv geladen, ein Pick korrigiert); Ergebnis und Zeitpunkt bleiben. Champion aus einem anderen Spiel
     * der Serie oder unvollständig → 400. Gleiche Archiv-Drafts werden mit ersetzt ({@code draftsUpdated}); gibt es keinen,
     * wird er aus dem Spiel neu angelegt ({@code draftsRecreated}).
     */
    private Object replaceGame(String id, int number, JsonNode body) throws IOException {
        GameData data = ctx.awaitData();
        Draft board = DraftEndpoint.board(body, data, "Nur vollständige Drafts zählen als Game.");
        Map<Integer, Role> shownRoles = shownRoles(body.path("shownRoles"), board.ally(), board.enemy());
        Draft draft = RoleOrder.sort(board, shownRoles);
        Game before;
        try {
            before = store.replaceGameDraft(id, number, draft, shownRoles);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        if (before == null) {
            throw new ApiException(404, "Serie nicht gefunden");
        }
        Game after = store.series(id).orElseThrow().games().get(number - 1);
        Map<String, Object> response = state();
        int replaced = drafts.replaceMatching(before.draft(), after.draft(), after.shownRoles());
        response.put("draftsUpdated", replaced);
        response.put("draftsRecreated", drafts.reconstruct(after.draft(), after.playedAt(), after.patch(), after.shownRoles()) != null);
        return response;
    }

    /**
     * {@code PUT /api/fearless/<id>/game/<n> {ally, enemy}}: überschreibt nur die festen Rollen des Spiels. Andere
     * Champions → 400.
     */
    private Object updateGameRoles(String id, int number, JsonNode body) throws IOException {
        List<Slot> ally = DraftEndpoint.storedSlots(body.path("ally"));
        List<Slot> enemy = DraftEndpoint.storedSlots(body.path("enemy"));
        Game updated;
        try {
            updated = store.updateGameRoles(id, number, ally, enemy);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        if (updated == null) {
            throw new ApiException(404, "Serie nicht gefunden");
        }
        // Der gleiche Draft unter „Drafts“ bekommt dieselben Rollen.
        Map<String, Object> response = state();
        response.put("draftsUpdated", drafts.updateRolesMatching(updated.draft(), updated.shownRoles()));
        // Fehlt der Draft unter „Drafts“ ganz (auch nicht im Papierkorb), wird er aus dem Spiel neu angelegt.
        response.put("draftsRecreated", drafts.reconstruct(updated.draft(), updated.playedAt(), updated.patch(), updated.shownRoles()) != null);
        return response;
    }

    /**
     * {@code PUT /api/fearless/<id>/game/<n> {result}}: Ergebnis ändern ({@code WIN|LOSS|null}). Liefert {@code reopened},
     * wenn eine beendete Serie dadurch wieder läuft, und {@code reopenBlocked}, wenn das an einer anderen laufenden scheitert.
     */
    private Object updateGameResult(String id, int number, JsonNode body) throws IOException {
        JsonNode value = body.path("result");
        Result result = value.isNull() || value.asText("").isEmpty() ? null : Input.enumValue(Result.class, value.asText(), "Ungültiges Ergebnis");
        FearlessStore.ResultChange change;
        try {
            change = store.updateGameResult(id, number, result);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        if (change == null) {
            throw new ApiException(404, "Serie nicht gefunden");
        }
        Map<String, Object> response = state();
        response.put("reopened", change.reopened());
        response.put("reopenBlocked", change.blocked());
        response.put("ended", !change.series().active());
        return response;
    }

    /** Gleiche Serienspiele bekommen die Rollen eines geänderten Archiv-Drafts mit ({@link DraftArchiveEndpoint}). */
    int updateRolesMatching(Draft changed) throws IOException {
        return store.updateRolesMatching(changed);
    }

    private Object start(JsonNode body) throws IOException {
        Format format = Input.enumValue(Format.class, body.path("format").asText(""), "Ungültiges Format");
        try {
            store.start(format, maxGames(format, body.path("maxGames")), label(body), limit());
        } catch (IllegalStateException e) {
            throw new ApiException(409, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        return state();
    }

    /**
     * Einmal je Datei aus Version 1: Spiele laufender und beendeter Serien, die (früher absichtlich) nicht ins Draft-Archiv
     * kamen, dort anlegen; vorhandene gleiche Drafts (auch im Papierkorb) bleiben, wie sie sind.
     */
    void backfillArchive() throws IOException {
        for (Game game : store.takeArchiveBackfill()) {
            if (game.draft().complete()) {
                drafts.reconstruct(game.draft(), game.playedAt(), game.patch(), game.shownRoles());
            }
        }
    }

    /** {@code POST /api/fearless/game {ally, enemy, bans, result, shownRoles}}: schließt das Brett als Spiel der laufenden Serie ab. */
    private Object addGame(JsonNode body) throws IOException {
        GameData data = ctx.awaitData();
        Draft board = DraftEndpoint.board(body, data, "Nur vollständige Drafts zählen als Game.");
        String resultText = body.path("result").asText("");
        Result result = resultText.isEmpty() ? null : Input.enumValue(Result.class, resultText, "Ungültiges Ergebnis");
        Map<Integer, Role> shownRoles = shownRoles(body.path("shownRoles"), board.ally(), board.enemy());

        FearlessSeries active = store.active().orElseThrow(() -> new ApiException(409, "Es läuft keine Fearless-Serie."));
        requireFree(active, board, data);
        FearlessSeries updated;
        try {
            // Das Frontend schickt die Picks schon in Rollen-Reihenfolge; ganz fest belegte Teams ordnet RoleOrder nach.
            updated = store.addGame(RoleOrder.sort(board, shownRoles), result, data.patch(), shownRoles, limit());
        } catch (IllegalStateException e) {
            throw new ApiException(409, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        // Das Spiel steht auch unter „Drafts“ (dort als Spiel der Serie gekennzeichnet), außer ein gleicher Draft ist schon da.
        Game played = updated.games().get(updated.games().size() - 1);
        boolean recorded = drafts.reconstruct(played.draft(), played.playedAt(), played.patch(), played.shownRoles()) != null;
        Map<String, Object> response = state();
        response.put("draftRecorded", recorded);
        response.put("game", updated.games().size());
        response.put("ended", !updated.active());
        response.put("series", series(ordered(updated)));
        return response;
    }

    /**
     * {@code POST /api/fearless/<id>/game {draftId}}: trägt einen Draft aus dem Archiv als letztes Spiel einer laufenden oder
     * beendeten Serie nach (z. B. nach versehentlichem Entfernen), mit seinem Speicherdatum und Patch, ohne Ergebnis. Volle
     * Serie → 409, gesperrte Champions → 400, unbekannte Serie oder unbekannter Draft → 404.
     */
    private Object addDraftTo(String id, JsonNode body) throws IOException {
        GameData data = ctx.awaitData();
        DraftArchive.Entry entry = drafts.entry(body.path("draftId").asText(""))
                .orElseThrow(() -> new ApiException(404, "Draft nicht gefunden"));
        FearlessSeries series = store.series(id).orElseThrow(() -> new ApiException(404, "Serie nicht gefunden"));
        requireFree(series, entry.draft(), data);
        FearlessSeries updated;
        try {
            updated = store.addGameTo(id, RoleOrder.sort(entry.draft(), entry.shownRoles()), entry.patch(), entry.shownRoles(),
                    entry.savedAt());
        } catch (IllegalStateException e) {
            throw new ApiException(409, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        if (updated == null) {
            throw new ApiException(404, "Serie nicht gefunden");
        }
        Map<String, Object> response = state();
        response.put("game", updated.games().size());
        response.put("ended", series.active() && !updated.active());
        return response;
    }

    /** 400 mit den Namen, wenn Champions des Drafts in der Serie schon gespielt wurden. */
    private static void requireFree(FearlessSeries series, Draft draft, GameData data) {
        Map<Integer, Integer> locked = series.locked();
        List<String> conflicts = new ArrayList<>();
        for (Slot slot : concat(draft.ally(), draft.enemy())) {
            Integer game = locked.get(slot.champion());
            if (game != null) {
                conflicts.add(data.championName(slot.champion()) + " (Game " + game + ")");
            }
        }
        if (!conflicts.isEmpty()) {
            throw new ApiException(400, "In dieser Serie schon gespielt: " + String.join(", ", conflicts));
        }
    }

    private Object undo() throws IOException {
        Game game;
        try {
            game = store.undoLastGame(limit());
        } catch (IllegalStateException e) {
            throw new ApiException(409, e.getMessage());
        }
        Map<String, Object> response = state();
        response.put("undone", game(ordered(game)));
        return response;
    }

    /**
     * {@code PUT /api/fearless/<id>}: {@code label} umbenennen, {@code maxGames} (nur offene Serien, nicht unter der
     * Zahl gespielter Spiele) und/oder {@code order} (Spielnummern neu geordnet). {@code ended}: eine laufende Serie hat
     * mit der neuen Spielzahl alle Spiele und ist beendet; {@code reopened}/{@code reopenBlocked} wie bei Ergebnissen.
     */
    private Object update(String id, JsonNode body) throws IOException {
        JsonNode order = body.path("order");
        JsonNode maxGames = body.path("maxGames");
        if (!body.has("label") && order.isMissingNode() && maxGames.isMissingNode()) {
            throw new ApiException(400, "Bezeichnung, Anzahl Games oder Reihenfolge erwartet.");
        }
        boolean ended = false;
        FearlessStore.ResultChange change = null;
        try {
            if (body.has("label")) {
                FearlessSeries.cleanLabel(label(body)); // erst prüfen, damit eine ungültige Bezeichnung nichts halb ändert
            }
            if (!maxGames.isMissingNode()) {
                if (!maxGames.isInt()) {
                    throw new ApiException(400, "Die Anzahl Games muss eine ganze Zahl sein.");
                }
                boolean wasActive = store.active().map(s -> s.id().equals(id)).orElse(false);
                change = store.setMaxGames(id, maxGames.asInt());
                if (change == null) {
                    throw new ApiException(404, "Serie nicht gefunden");
                }
                ended = wasActive && !change.series().active();
            }
            if (!order.isMissingNode()) {
                if (!order.isArray()) {
                    throw new ApiException(400, "Die Reihenfolge muss eine Liste von Game-Nummern sein.");
                }
                List<Integer> numbers = new ArrayList<>();
                for (JsonNode n : order) {
                    if (!n.isInt()) {
                        throw new ApiException(400, "Die Reihenfolge muss eine Liste von Game-Nummern sein.");
                    }
                    numbers.add(n.asInt());
                }
                if (!store.reorder(id, numbers)) {
                    throw new ApiException(404, "Serie nicht gefunden");
                }
            }
            if (body.has("label") && !store.rename(id, label(body))) {
                throw new ApiException(404, "Serie nicht gefunden");
            }
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        Map<String, Object> response = state();
        response.put("ended", ended);
        // Mehr Spiele für eine volle, beendete Serie: Sie läuft wieder (oder nicht, weil schon eine andere läuft).
        response.put("reopened", change != null && change.reopened());
        response.put("reopenBlocked", change != null && change.blocked());
        return response;
    }

    /** Legt die Serie (laufende ohne Spiel: verworfen) in den Papierkorb, ihre Spiele im Draft-Archiv (gleiche zehn Champions) ebenfalls. */
    private Object delete(String id) throws IOException {
        FearlessSeries series = store.series(id).orElse(null);
        if (series == null || !store.delete(id, limit())) {
            throw new ApiException(404, "Serie nicht gefunden");
        }
        Map<String, Object> response = state();
        response.put("draftsMoved", drafts.deleteMatching(gameDrafts(series)));
        return response;
    }

    private static List<Draft> gameDrafts(FearlessSeries series) {
        return series.games().stream().map(Game::draft).toList();
    }

    // ------------------------------------------------------------------ Hilfen

    /** Gewählte Spielzahl einer offenen Serie (1 bis {@link FearlessSeries#MAX_GAMES}); fehlt sie oder ist es Bo3/Bo5, 0 = Standard. */
    private static int maxGames(Format format, JsonNode node) {
        if (format != Format.OPEN || node.isMissingNode() || node.isNull()) {
            return 0;
        }
        if (!node.canConvertToInt() || !node.isIntegralNumber() || node.asInt() < 1 || node.asInt() > FearlessSeries.MAX_GAMES) {
            throw new ApiException(400, "Die Anzahl Games muss zwischen 1 und " + FearlessSeries.MAX_GAMES + " liegen.");
        }
        return node.asInt();
    }

    private static String label(JsonNode body) {
        JsonNode label = body.path("label");
        if (label.isMissingNode() || label.isNull()) {
            return null;
        }
        if (!label.isTextual()) {
            throw new ApiException(400, "Die Bezeichnung muss Text sein.");
        }
        return label.asText();
    }

    /**
     * Vom Brett angezeigte Auto-Rollen ({@code {"champion": "ROLE"}}), nur für Picks dieses Spiels ohne feste Rolle. Kommen
     * aus der Analyse, die der Tab ohnehin hatte; hier wird nichts vorhergesagt.
     */
    static Map<Integer, Role> shownRoles(JsonNode node, List<Slot> ally, List<Slot> enemy) {
        Map<Integer, Role> shown = new HashMap<>();
        if (!node.isObject()) {
            return shown;
        }
        Set<Integer> open = new HashSet<>();
        concat(ally, enemy).stream().filter(slot -> slot.forcedRole() == null).forEach(slot -> open.add(slot.champion()));
        node.fields().forEachRemaining(e -> {
            int champion;
            try {
                champion = Integer.parseInt(e.getKey());
            } catch (NumberFormatException ex) {
                throw new ApiException(400, "Ungültige angezeigte Rolle");
            }
            if (open.contains(champion)) {
                shown.put(champion, Input.enumValue(Role.class, e.getValue().asText(""), "Ungültige angezeigte Rolle"));
            }
        });
        return shown;
    }

    private static List<Slot> concat(List<Slot> a, List<Slot> b) {
        List<Slot> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private static List<Map<String, Object>> locked(Map<Integer, Integer> locked) {
        return locked.entrySet().stream().map(e -> Dto.obj("champion", e.getKey(), "game", e.getValue())).toList();
    }

    private static Map<String, Object> series(FearlessSeries series) {
        return Dto.obj(
                "id", series.id(),
                "label", series.label(),
                "format", series.format().name(),
                "maxGames", series.maxGames(),
                "winsNeeded", series.format().winsNeeded(),
                "startedAt", series.startedAt().toString(),
                "endedAt", series.endedAt() == null ? null : series.endedAt().toString(),
                "wins", series.wins(),
                "losses", series.losses(),
                "nextGame", series.nextGame(),
                "games", series.games().stream().map(FearlessEndpoint::game).toList());
    }

    private static Map<String, Object> game(Game game) {
        Draft draft = game.draft();
        return Dto.obj(
                "number", game.number(),
                "playedAt", game.playedAt().toString(),
                "patch", game.patch(),
                "result", game.result() == null ? null : game.result().name(),
                "ally", DraftEndpoint.slotsJson(draft.ally()),
                "enemy", DraftEndpoint.slotsJson(draft.enemy()),
                "bans", draft.bans());
    }
}
