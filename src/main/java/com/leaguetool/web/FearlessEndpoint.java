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
import com.leaguetool.draft.RolePredictor.Slot;
import com.leaguetool.model.Champion;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fearless-Serien (Hard Fearless) für den Draft-Tab:
 * <ul>
 *   <li>{@code GET /api/fearless}: laufende Serie, ihre Sperren und die beendeten Serien</li>
 *   <li>{@code POST /api/fearless {format, label}}: Serie starten</li>
 *   <li>{@code POST /api/fearless/game {ally, enemy, bans, result}}: Spiel abschließen</li>
 *   <li>{@code DELETE /api/fearless/game}: letztes Spiel zurücknehmen, liefert dessen Draft</li>
 *   <li>{@code POST /api/fearless/end}: Serie beenden</li>
 *   <li>{@code PUT /api/fearless/<id> {label}}: umbenennen, {@code DELETE /api/fearless/<id>}: beendete Serie löschen</li>
 * </ul>
 *
 * <p>Nur lokal in {@value #FILE_NAME}. Die Daten kommen ausschließlich vom Draft-Brett und aus der Eingabe des Nutzers;
 * es gibt keine Abfrage beim League-Client, bei Riot oder Lolalytics. Die Bezeichnung geht nirgendwohin hinaus.
 */
final class FearlessEndpoint {

    static final String FILE_NAME = "fearless-series.json";
    private static final String ID_PATTERN = "[0-9a-f-]{36}";

    private final WebContext ctx;
    private final FearlessStore store;

    FearlessEndpoint(WebContext ctx) {
        this.ctx = ctx;
        this.store = new FearlessStore(ctx.settings().home().resolve(FILE_NAME));
    }

    Object handle(String method, List<String> path, JsonNode body) throws IOException {
        String sub = path.size() > 1 ? path.get(1) : "";
        if (path.size() > 2) {
            throw new ApiException(404, "Unbekannter Endpunkt");
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
                if (!sub.matches(ID_PATTERN)) {
                    throw new ApiException(404, "Unbekannter Endpunkt");
                }
                yield switch (method) {
                    case "PUT" -> rename(sub, body);
                    case "DELETE" -> delete(sub);
                    default -> throw new ApiException(404, "Unbekannter Endpunkt");
                };
            }
        };
    }

    private int limit() {
        return ctx.settings().draftArchiveLimit();
    }

    Map<String, Object> state() throws IOException {
        FearlessSeries active = store.active().orElse(null);
        return Dto.obj(
                "active", active == null ? null : series(active),
                "locked", active == null ? List.of() : locked(active.locked()),
                "finished", store.finished(limit()).stream().map(FearlessEndpoint::series).toList(),
                "formats", Arrays.stream(Format.values()).map(Format::name).toList(),
                "maxLabelLength", FearlessSeries.MAX_LABEL_LENGTH,
                "maxGames", FearlessSeries.MAX_GAMES);
    }

    private Object start(JsonNode body) throws IOException {
        Format format = parse(Format.class, body.path("format").asText(""), "Ungültiges Format");
        try {
            store.start(format, label(body), limit());
        } catch (IllegalStateException e) {
            throw new ApiException(409, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        return state();
    }

    private Object addGame(JsonNode body) throws IOException {
        GameData data = ctx.awaitData();
        List<Slot> ally = DraftEndpoint.slots(body.path("ally"), data);
        List<Slot> enemy = DraftEndpoint.slots(body.path("enemy"), data);
        if (ally.size() != DraftArchive.TEAM_SIZE || enemy.size() != DraftArchive.TEAM_SIZE) {
            throw new ApiException(400, "Nur vollständige Drafts zählen als Spiel.");
        }
        DraftEndpoint.requireDistinct(ally, enemy);
        JsonNode banList = body.path("bans");
        if (banList.size() > DraftArchive.MAX_BANS) {
            throw new ApiException(400, "Höchstens " + DraftArchive.MAX_BANS + " Bans.");
        }
        Set<Integer> bans = new LinkedHashSet<>();
        banList.forEach(node -> bans.add(DraftEndpoint.championKey(node, data)));
        String resultText = body.path("result").asText("");
        Result result = resultText.isEmpty() ? null : parse(Result.class, resultText, "Ungültiges Ergebnis");

        FearlessSeries active = store.active().orElseThrow(() -> new ApiException(409, "Es läuft keine Fearless-Serie."));
        Map<Integer, Integer> locked = active.locked();
        List<String> conflicts = new ArrayList<>();
        for (Slot slot : concat(ally, enemy)) {
            Integer game = locked.get(slot.champion());
            if (game != null) {
                conflicts.add(name(data, slot.champion()) + " (Spiel " + game + ")");
            }
        }
        if (!conflicts.isEmpty()) {
            throw new ApiException(400, "In dieser Serie schon gespielt: " + String.join(", ", conflicts));
        }
        FearlessSeries updated;
        try {
            updated = store.addGame(new Draft(ally, enemy, new ArrayList<>(bans)), result, data.patch(), limit());
        } catch (IllegalStateException e) {
            throw new ApiException(409, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        Map<String, Object> response = new LinkedHashMap<>(state());
        response.put("game", updated.games().size());
        response.put("ended", !updated.active());
        response.put("series", series(updated));
        return response;
    }

    private Object undo() throws IOException {
        Game game;
        try {
            game = store.undoLastGame(limit());
        } catch (IllegalStateException e) {
            throw new ApiException(409, e.getMessage());
        }
        Map<String, Object> response = new LinkedHashMap<>(state());
        response.put("undone", game(game));
        return response;
    }

    private Object rename(String id, JsonNode body) throws IOException {
        try {
            if (!store.rename(id, label(body), limit())) {
                throw new ApiException(404, "Serie nicht gefunden");
            }
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        return state();
    }

    private Object delete(String id) throws IOException {
        if (!store.delete(id, limit())) {
            throw new ApiException(404, "Serie nicht gefunden");
        }
        return state();
    }

    // ------------------------------------------------------------------ Hilfen

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

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String message) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, message);
        }
    }

    private static List<Slot> concat(List<Slot> a, List<Slot> b) {
        List<Slot> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private static String name(GameData data, int key) {
        return data.championByKey(key).map(Champion::name).orElse("Champion " + key);
    }

    private static List<Map<String, Object>> locked(Map<Integer, Integer> locked) {
        return locked.entrySet().stream().map(e -> Dto.obj("champion", e.getKey(), "game", e.getValue())).toList();
    }

    private static Map<String, Object> series(FearlessSeries series) {
        return Dto.obj(
                "id", series.id(),
                "label", series.label(),
                "format", series.format().name(),
                "maxGames", series.format().maxGames(),
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
                "ally", slots(draft.ally()),
                "enemy", slots(draft.enemy()),
                "bans", draft.bans());
    }

    private static List<Map<String, Object>> slots(List<Slot> slots) {
        return slots.stream().map(slot -> Dto.obj("champion", slot.champion(),
                "role", slot.forcedRole() == null ? null : slot.forcedRole().name())).toList();
    }
}
