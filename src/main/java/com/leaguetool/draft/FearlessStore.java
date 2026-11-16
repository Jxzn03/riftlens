package com.leaguetool.draft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leaguetool.draft.DraftArchive.Draft;
import com.leaguetool.draft.FearlessSeries.Format;
import com.leaguetool.draft.FearlessSeries.Game;
import com.leaguetool.draft.FearlessSeries.Result;
import com.leaguetool.draft.RolePredictor.Slot;
import com.leaguetool.util.AtomicFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Laufende und beendete Fearless-Serien, nur lokal in einer Datei im Nutzerordner ({@code version}, {@code active},
 * {@code finished}, neueste zuerst). Es läuft höchstens eine Serie.
 *
 * <p>Wie das Draft-Archiv: nur Champion-Keys, feste Rollen und Bans vom Brett, dazu die freie Bezeichnung. Keine
 * Namen, Riot-IDs oder PUUIDs, nichts verlässt den Rechner.
 */
public final class FearlessStore {

    private static final int FORMAT_VERSION = 1;

    private final Path file;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    private FearlessSeries active;
    private List<FearlessSeries> finished;
    private boolean unreadable;

    public FearlessStore(Path file) {
        this(file, Clock.systemUTC());
    }

    public FearlessStore(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    public synchronized Optional<FearlessSeries> active() throws IOException {
        load();
        return Optional.ofNullable(active);
    }

    /** Die neuesten {@code limit} beendeten Serien. */
    public synchronized List<FearlessSeries> finished(int limit) throws IOException {
        load();
        return List.copyOf(finished.subList(0, Math.min(Math.max(1, limit), finished.size())));
    }

    /**
     * Startet eine neue Serie.
     *
     * @throws IllegalStateException    wenn schon eine läuft
     * @throws IllegalArgumentException bei zu langer Bezeichnung
     */
    public synchronized FearlessSeries start(Format format, String label, int limit) throws IOException {
        load();
        if (active != null) {
            throw new IllegalStateException("Es läuft bereits eine Fearless-Serie.");
        }
        FearlessSeries series = new FearlessSeries(UUID.randomUUID().toString(), FearlessSeries.cleanLabel(label), format,
                clock.instant(), null, List.of());
        write(series, finished, limit);
        return series;
    }

    /**
     * Schließt ein Spiel der laufenden Serie ab. Ist die Serie danach nach Format entschieden, endet sie.
     *
     * @return die Serie mit dem neuen Spiel (beendet, falls entschieden)
     * @throws IllegalStateException    wenn keine Serie läuft
     * @throws IllegalArgumentException bei unvollständigem Draft oder gesperrten Champions
     */
    public synchronized FearlessSeries addGame(Draft draft, Result result, String patch, int limit) throws IOException {
        load();
        if (active == null) {
            throw new IllegalStateException("Es läuft keine Fearless-Serie.");
        }
        Instant now = clock.instant();
        FearlessSeries updated = active.withGame(draft, result, patch, now);
        if (updated.decided()) {
            updated = updated.endedAt(now);
            write(null, prepend(updated, finished), limit);
        } else {
            write(updated, finished, limit);
        }
        return updated;
    }

    /**
     * Nimmt das letzte Spiel zurück. Läuft keine Serie, wird die zuletzt beendete wieder geöffnet (z. B. nach einem
     * falsch eingetragenen entscheidenden Ergebnis).
     *
     * @return das zurückgenommene Spiel
     * @throws IllegalStateException wenn es kein Spiel zum Zurücknehmen gibt
     */
    public synchronized Game undoLastGame(int limit) throws IOException {
        load();
        FearlessSeries target = active;
        List<FearlessSeries> rest = finished;
        if (target == null) {
            if (finished.isEmpty()) {
                throw new IllegalStateException("Es gibt kein Spiel zum Zurücknehmen.");
            }
            target = finished.get(0);
            rest = finished.subList(1, finished.size());
        }
        if (target.games().isEmpty()) {
            throw new IllegalStateException("Die Serie hat noch kein Spiel.");
        }
        Game last = target.games().get(target.games().size() - 1);
        write(target.withoutLastGame(), rest, limit);
        return last;
    }

    /** Beendet die laufende Serie; ohne gespieltes Spiel wird sie verworfen. @return ob eine Serie lief */
    public synchronized boolean end(int limit) throws IOException {
        load();
        if (active == null) {
            return false;
        }
        List<FearlessSeries> updated = active.games().isEmpty() ? finished : prepend(active.endedAt(clock.instant()), finished);
        write(null, updated, limit);
        return true;
    }

    /** Ändert die Bezeichnung einer laufenden oder beendeten Serie. @return ob es die Serie gab */
    public synchronized boolean rename(String id, String label, int limit) throws IOException {
        load();
        String cleaned = FearlessSeries.cleanLabel(label);
        if (active != null && active.id().equals(id)) {
            write(active.withLabel(cleaned), finished, limit);
            return true;
        }
        List<FearlessSeries> updated = new ArrayList<>(finished);
        for (int i = 0; i < updated.size(); i++) {
            if (updated.get(i).id().equals(id)) {
                updated.set(i, updated.get(i).withLabel(cleaned));
                write(active, updated, limit);
                return true;
            }
        }
        return false;
    }

    /** Löscht eine beendete Serie. @return ob es sie gab */
    public synchronized boolean delete(String id, int limit) throws IOException {
        load();
        List<FearlessSeries> updated = new ArrayList<>(finished);
        if (!updated.removeIf(s -> s.id().equals(id))) {
            return false;
        }
        write(active, updated, limit);
        return true;
    }

    private static List<FearlessSeries> prepend(FearlessSeries series, List<FearlessSeries> list) {
        List<FearlessSeries> updated = new ArrayList<>();
        updated.add(series);
        updated.addAll(list);
        return updated;
    }

    // ------------------------------------------------------------------ Datei

    private void load() {
        if (finished != null) {
            return;
        }
        finished = List.of();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonNode root = json.readTree(Files.readAllBytes(file));
            FearlessSeries running = series(root.path("active"));
            active = running != null && running.active() ? running : null;
            List<FearlessSeries> loaded = new ArrayList<>();
            for (JsonNode node : root.path("finished")) {
                FearlessSeries series = series(node);
                if (series != null && !series.active() && !series.games().isEmpty()) {
                    loaded.add(series);
                }
            }
            finished = List.copyOf(loaded);
        } catch (IOException e) {
            // Unlesbar: Beim nächsten Schreiben wird sie beiseitegelegt statt überschrieben.
            unreadable = true;
        }
    }

    private void write(FearlessSeries newActive, List<FearlessSeries> newFinished, int limit) throws IOException {
        if (unreadable && Files.isRegularFile(file)) {
            Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
            unreadable = false;
        }
        List<FearlessSeries> kept = List.copyOf(newFinished.subList(0, Math.min(Math.max(1, limit), newFinished.size())));
        ObjectNode root = json.createObjectNode();
        root.put("version", FORMAT_VERSION);
        if (newActive == null) {
            root.putNull("active");
        } else {
            writeSeries(root.putObject("active"), newActive);
        }
        ArrayNode list = root.putArray("finished");
        kept.forEach(series -> writeSeries(list.addObject(), series));
        AtomicFiles.write(file, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
        active = newActive;
        finished = kept;
    }

    private static void writeSeries(ObjectNode node, FearlessSeries series) {
        node.put("id", series.id());
        if (series.label() == null) {
            node.putNull("label");
        } else {
            node.put("label", series.label());
        }
        node.put("format", series.format().name());
        node.put("startedAt", series.startedAt().toString());
        if (series.endedAt() == null) {
            node.putNull("endedAt");
        } else {
            node.put("endedAt", series.endedAt().toString());
        }
        ArrayNode games = node.putArray("games");
        for (Game game : series.games()) {
            ObjectNode g = games.addObject();
            g.put("number", game.number());
            g.put("playedAt", game.playedAt().toString());
            g.put("patch", game.patch());
            if (game.result() == null) {
                g.putNull("result");
            } else {
                g.put("result", game.result().name());
            }
            writeSlots(g.putArray("ally"), game.draft().ally());
            writeSlots(g.putArray("enemy"), game.draft().enemy());
            ArrayNode bans = g.putArray("bans");
            game.draft().bans().forEach(bans::add);
        }
    }

    private static void writeSlots(ArrayNode target, List<Slot> slots) {
        DraftArchive.writeSlots(target, slots);
    }

    /** Eine Serie aus der Datei; {@code null}, wenn sie fehlt oder beschädigt ist. */
    private static FearlessSeries series(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        try {
            String id = node.path("id").asText("");
            if (id.isEmpty()) {
                return null;
            }
            String label = node.path("label").isTextual() ? node.path("label").asText() : null;
            if (label != null && label.length() > FearlessSeries.MAX_LABEL_LENGTH) {
                label = label.substring(0, FearlessSeries.MAX_LABEL_LENGTH);
            }
            JsonNode ended = node.path("endedAt");
            List<Game> games = new ArrayList<>();
            for (JsonNode g : node.path("games")) {
                List<Integer> bans = new ArrayList<>();
                g.path("bans").forEach(ban -> bans.add(ban.asInt()));
                Draft draft = new Draft(slots(g.path("ally")), slots(g.path("enemy")),
                        bans.subList(0, Math.min(DraftArchive.MAX_BANS, bans.size())));
                if (!draft.complete() || games.size() >= FearlessSeries.MAX_GAMES) {
                    return null;
                }
                JsonNode result = g.path("result");
                games.add(new Game(games.size() + 1, Instant.parse(g.path("playedAt").asText()), g.path("patch").asText(""),
                        draft, result.isTextual() ? Result.valueOf(result.asText()) : null));
            }
            return new FearlessSeries(id, FearlessSeries.cleanLabel(label), Format.valueOf(node.path("format").asText()),
                    Instant.parse(node.path("startedAt").asText()),
                    ended.isTextual() ? Instant.parse(ended.asText()) : null, games);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<Slot> slots(JsonNode list) {
        return DraftArchive.slots(list);
    }
}
