package com.leaguetool.draft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leaguetool.draft.DraftArchive.Draft;
import com.leaguetool.draft.FearlessSeries.Format;
import com.leaguetool.draft.FearlessSeries.Game;
import com.leaguetool.draft.FearlessSeries.Result;
import com.leaguetool.draft.RolePredictor.Slot;
import com.leaguetool.model.Role;
import com.leaguetool.util.AtomicFiles;
import com.leaguetool.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Laufende und beendete Fearless-Serien, nur lokal in einer Datei im Nutzerordner ({@code version}, {@code active},
 * {@code finished}, neueste zuerst). Es läuft höchstens eine Serie.
 *
 * <p>Wie das Draft-Archiv: nur Champion-Keys, feste Rollen und Bans vom Brett, dazu die freie Bezeichnung. Keine
 * Namen, Riot-IDs oder PUUIDs, nichts verlässt den Rechner. Gelöschte Serien liegen wie gelöschte Archiv-Drafts
 * 7 Tage im Papierkorb ({@code trash}), danach werden sie beim nächsten Zugriff endgültig entfernt.
 */
public final class FearlessStore {

    /**
     * 2: Abgeschlossene Spiele stehen auch im Draft-Archiv. Dateien bis Version 1 werden einmal nachgetragen
     * ({@link #takeArchiveBackfill()}).
     */
    private static final int FORMAT_VERSION = 2;

    /** Eine gelöschte Serie im Papierkorb; nach {@link DraftArchive#TRASH_RETENTION} wird sie endgültig entfernt. */
    public record TrashedSeries(FearlessSeries series, Instant deletedAt) {

        public Instant expiresAt() {
            return deletedAt.plus(DraftArchive.TRASH_RETENTION);
        }
    }

    private final Path file;
    private final Clock clock;
    private FearlessSeries active;
    private List<FearlessSeries> finished;
    private List<TrashedSeries> trash;
    private boolean unreadable;
    /** Datei aus Version 1: Spiele fehlen im Draft-Archiv, einmal nachtragen. */
    private boolean archiveBackfill;

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
        return start(format, 0, label, limit);
    }

    /**
     * Startet eine neue Serie mit gewählter Spielzahl.
     *
     * @param maxGames nur bei {@code OPEN}: 1 bis {@link FearlessSeries#MAX_GAMES}, sonst (z. B. 0) der Standard
     * @throws IllegalStateException    wenn schon eine läuft
     * @throws IllegalArgumentException bei zu langer Bezeichnung
     */
    public synchronized FearlessSeries start(Format format, int maxGames, String label, int limit) throws IOException {
        load();
        if (active != null) {
            throw new IllegalStateException("Es läuft bereits eine Fearless-Serie.");
        }
        FearlessSeries series = new FearlessSeries(UUID.randomUUID().toString(), FearlessSeries.cleanLabel(label), format,
                maxGames, clock.instant(), null, List.of());
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
        return addGame(draft, result, patch, Map.of(), limit);
    }

    /** @param shownRoles angezeigte Auto-Rollen beim Abschließen (nur zum Ordnen, siehe {@link FearlessSeries.Game}) */
    public synchronized FearlessSeries addGame(Draft draft, Result result, String patch, Map<Integer, Role> shownRoles, int limit)
            throws IOException {
        load();
        if (active == null) {
            throw new IllegalStateException("Es läuft keine Fearless-Serie.");
        }
        Instant now = clock.instant();
        FearlessSeries updated = active.withGame(draft, result, patch, now, shownRoles);
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
                throw new IllegalStateException("Es gibt kein Game zum Zurücknehmen.");
            }
            target = finished.get(0);
            rest = finished.subList(1, finished.size());
        }
        if (target.games().isEmpty()) {
            throw new IllegalStateException("Die Serie hat noch kein Game.");
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
    public synchronized boolean rename(String id, String label) throws IOException {
        String cleaned = FearlessSeries.cleanLabel(label);
        return update(id, series -> series.withLabel(cleaned));
    }

    /**
     * Ändert die Spielzahl einer offenen Serie, mit denselben Folgen wie eine Ergebnis-Änderung: Hat eine laufende Serie
     * damit alle Spiele, endet sie; eine beendete, die voll war, läuft mit mehr Platz wieder (nicht, wenn schon eine andere
     * läuft). Eine von Hand vorzeitig beendete bleibt beendet.
     *
     * @return die Änderung oder {@code null}, wenn es die Serie nicht gibt
     * @throws IllegalArgumentException bei Bo3/Bo5, ungültiger Zahl oder weniger als schon gespielt
     */
    public synchronized ResultChange setMaxGames(String id, int maxGames) throws IOException {
        load();
        FearlessSeries series = find(id);
        if (series == null) {
            return null;
        }
        return applyDecision(series, series.withMaxGames(maxGames));
    }

    /**
     * Ordnet die Spiele einer laufenden oder beendeten Serie neu, z. B. nach einer Verwechslung.
     *
     * @param order bisherige Spielnummern in der neuen Reihenfolge
     * @return ob es die Serie gab
     * @throws IllegalArgumentException wenn {@code order} keine Umordnung aller Spiele ist
     */
    public synchronized boolean reorder(String id, List<Integer> order) throws IOException {
        return update(id, series -> series.withOrder(order));
    }

    /**
     * Ersetzt den Draft eines Spiels einer laufenden oder beendeten Serie (Champions, Rollen, Bans), z. B. nach einem
     * falsch eingetragenen Pick; Ergebnis und Zeitpunkt bleiben, ob die Serie läuft auch.
     *
     * @return das Spiel vor der Änderung oder {@code null}, wenn es die Serie nicht gibt
     * @throws IllegalArgumentException siehe {@link FearlessSeries#withGameDraft}
     */
    public synchronized Game replaceGameDraft(String id, int number, Draft draft, Map<Integer, Role> shownRoles) throws IOException {
        load();
        FearlessSeries series = find(id);
        if (series == null) {
            return null;
        }
        FearlessSeries updated = series.withGameDraft(number, draft, shownRoles);
        replace(updated);
        return series.games().get(number - 1);
    }

    /**
     * Überschreibt die festen Rollen eines Spiels einer laufenden oder beendeten Serie.
     *
     * @return das geänderte Spiel oder {@code null}, wenn es die Serie nicht gibt
     * @throws IllegalArgumentException wenn es das Spiel nicht gibt oder sich Champions unterscheiden
     */
    public synchronized Game updateGameRoles(String id, int number, List<Slot> ally, List<Slot> enemy) throws IOException {
        if (!update(id, series -> series.withGameRoles(number, ally, enemy))) {
            return null;
        }
        return find(id).games().get(number - 1);
    }

    /**
     * Ergebnis einer Ergebnis-Änderung.
     *
     * @param reopened die beendete Serie war danach nicht mehr entschieden und läuft wieder
     * @param blocked  sie hätte wieder geöffnet werden sollen, aber eine andere Serie läuft schon
     */
    public record ResultChange(FearlessSeries series, boolean reopened, boolean blocked) {
    }

    /**
     * Ändert das Ergebnis eines Spiels. War eine beendete Serie entschieden und ist es danach nicht mehr (z. B. 2:0 → 1:1
     * im Best-of-3), läuft sie wieder, damit weitere Spiele folgen können; das geht nur, wenn keine andere Serie läuft.
     * Wird eine laufende Serie dadurch entschieden, endet sie wie beim Abschließen eines Spiels.
     *
     * @return die Änderung oder {@code null}, wenn es die Serie nicht gibt
     * @throws IllegalArgumentException wenn es das Spiel nicht gibt
     */
    public synchronized ResultChange updateGameResult(String id, int number, Result result) throws IOException {
        load();
        FearlessSeries series = find(id);
        if (series == null) {
            return null;
        }
        return applyDecision(series, series.withGameResult(number, result));
    }

    /**
     * Speichert eine geänderte Serie und richtet danach, ob sie entschieden ist: Eine laufende endet, wenn sie entschieden
     * ist; eine beendete, die entschieden war und es nicht mehr ist, läuft wieder (nicht, wenn schon eine andere läuft).
     * Von Hand beendete, nicht entschiedene Serien bleiben beendet.
     */
    private ResultChange applyDecision(FearlessSeries series, FearlessSeries updated) throws IOException {
        String id = series.id();
        if (series.active()) {
            if (updated.decided()) {
                updated = updated.endedAt(clock.instant());
                write(null, prepend(updated, finished), Integer.MAX_VALUE);
            } else {
                write(updated, finished, Integer.MAX_VALUE);
            }
            return new ResultChange(updated, false, false);
        }
        boolean undecided = series.decided() && !updated.decided();
        if (undecided && active == null) {
            FearlessSeries reopened = updated.endedAt(null);
            List<FearlessSeries> rest = new ArrayList<>(finished);
            rest.removeIf(s -> s.id().equals(id));
            write(reopened, rest, Integer.MAX_VALUE);
            return new ResultChange(reopened, true, false);
        }
        replace(updated);
        return new ResultChange(updated, false, undecided);
    }

    /**
     * Übernimmt die festen Rollen eines geänderten Archiv-Drafts in alle gleichen Spiele laufender oder beendeter Serien
     * (dieselben zehn Champions, auch mit vertauschten Seiten). Spiele mit schon gleichen Rollen bleiben unberührt.
     *
     * @return Anzahl der geänderten Spiele
     */
    public synchronized int updateRolesMatching(Draft changed) throws IOException {
        load();
        int[] count = {0};
        java.util.function.UnaryOperator<FearlessSeries> sync = series -> {
            FearlessSeries result = series;
            for (Game game : series.games()) {
                if (DraftArchive.changes(game.draft(), changed) != 0) {
                    continue;
                }
                boolean sameSides = game.draft().ally().stream().map(Slot::champion).collect(java.util.stream.Collectors.toSet())
                        .equals(changed.ally().stream().map(Slot::champion).collect(java.util.stream.Collectors.toSet()));
                FearlessSeries updated = sameSides ? result.withGameRoles(game.number(), changed.ally(), changed.enemy())
                        : result.withGameRoles(game.number(), changed.enemy(), changed.ally());
                if (!updated.games().get(game.number() - 1).draft().equals(game.draft())) {
                    result = updated;
                    count[0]++;
                }
            }
            return result;
        };
        FearlessSeries newActive = active == null ? null : sync.apply(active);
        List<FearlessSeries> newFinished = finished.stream().map(sync).toList();
        if (count[0] > 0) {
            write(newActive, newFinished, Integer.MAX_VALUE);
        }
        return count[0];
    }

    private boolean update(String id, java.util.function.UnaryOperator<FearlessSeries> change) throws IOException {
        load();
        if (active != null && active.id().equals(id)) {
            write(change.apply(active), finished, Integer.MAX_VALUE);
            return true;
        }
        List<FearlessSeries> updated = new ArrayList<>(finished);
        for (int i = 0; i < updated.size(); i++) {
            if (updated.get(i).id().equals(id)) {
                updated.set(i, change.apply(updated.get(i)));
                write(active, updated, Integer.MAX_VALUE);
                return true;
            }
        }
        return false;
    }

    /**
     * Legt eine Serie in den Papierkorb; dort bleibt sie {@link DraftArchive#TRASH_RETENTION} wiederherstellbar. Die laufende
     * Serie endet dabei (z. B. versehentlich gestartet); hat sie noch kein Spiel, wird sie ohne Papierkorb verworfen.
     *
     * @return ob es sie gab
     */
    public synchronized boolean delete(String id, int limit) throws IOException {
        load();
        if (active != null && active.id().equals(id)) {
            Instant now = clock.instant();
            List<TrashedSeries> updatedTrash = new ArrayList<>();
            if (!active.games().isEmpty()) {
                updatedTrash.add(new TrashedSeries(active.endedAt(now), now));
            }
            updatedTrash.addAll(trash);
            write(null, finished, updatedTrash, limit);
            return true;
        }
        FearlessSeries removed = finished.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
        if (removed == null) {
            return false;
        }
        List<FearlessSeries> updated = new ArrayList<>(finished);
        updated.remove(removed);
        List<TrashedSeries> updatedTrash = new ArrayList<>();
        updatedTrash.add(new TrashedSeries(removed, clock.instant()));
        updatedTrash.addAll(trash);
        write(active, updated, updatedTrash, limit);
        return true;
    }

    /** Der Papierkorb, zuletzt gelöschte zuerst; abgelaufene Serien sind schon entfernt. */
    public synchronized List<TrashedSeries> trash() throws IOException {
        load();
        return trash;
    }

    /**
     * Löst Spiel {@code number} aus einer laufenden oder beendeten Serie, ohne Papierkorb (z. B. falsch zugeordnet; der
     * Draft bleibt im Archiv). Die übrigen Spiele werden neu durchnummeriert, die Sperren je Spiel folgen daraus. Das
     * einzige Spiel einer beendeten Serie lässt sich so nicht entfernen (dann die Serie löschen).
     *
     * @return das entfernte Spiel oder {@code null}, wenn es die Serie nicht gibt
     * @throws IllegalArgumentException wenn es das Spiel nicht gibt
     * @throws IllegalStateException    beim einzigen Spiel einer beendeten Serie
     */
    public synchronized Game removeGame(String seriesId, int number) throws IOException {
        load();
        FearlessSeries series = find(seriesId);
        if (series == null) {
            return null;
        }
        FearlessSeries updated = series.withoutGame(number);
        if (!series.active() && updated.games().isEmpty()) {
            throw new IllegalStateException("Das ist das einzige Game der Serie; lösche stattdessen die Serie.");
        }
        replace(updated);
        return series.games().get(number - 1);
    }

    /**
     * Trägt ein Spiel ohne Ergebnis am Ende einer laufenden oder beendeten Serie nach (z. B. einen Archiv-Draft nach
     * versehentlichem Entfernen), solange sie nicht alle Spiele ihres Formats hat. Ist eine laufende Serie danach voll,
     * endet sie. Eine beendete bleibt beendet.
     *
     * @param playedAt Zeitpunkt des Spiels, z. B. das Speicherdatum des Drafts
     * @return die Serie mit dem Spiel oder {@code null}, wenn es sie nicht gibt
     * @throws IllegalStateException    wenn die Serie voll ist
     * @throws IllegalArgumentException bei unvollständigem Draft oder gesperrten Champions
     */
    public synchronized FearlessSeries addGameTo(String seriesId, Draft draft, String patch, Map<Integer, Role> shownRoles,
            Instant playedAt) throws IOException {
        load();
        FearlessSeries series = find(seriesId);
        if (series == null) {
            return null;
        }
        FearlessSeries updated = series.withAddedGame(draft, patch, playedAt, shownRoles);
        if (updated.active() && updated.decided()) {
            updated = updated.endedAt(clock.instant());
        }
        replace(updated);
        return updated;
    }

    /** Die laufende oder beendete Serie mit dieser ID (nicht im Papierkorb). */
    public synchronized Optional<FearlessSeries> series(String id) throws IOException {
        load();
        return Optional.ofNullable(find(id));
    }

    /** Die laufende oder beendete Serie mit dieser ID, sonst {@code null}. */
    private FearlessSeries find(String id) {
        if (active != null && active.id().equals(id)) {
            return active;
        }
        return finished.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    /** Ersetzt eine vorhandene Serie (gleiche ID); endet eine laufende dabei, kommt sie vorn unter die beendeten. */
    private void replace(FearlessSeries series) throws IOException {
        if (active != null && active.id().equals(series.id())) {
            if (series.active()) {
                write(series, finished, Integer.MAX_VALUE);
            } else {
                write(null, prepend(series, finished), Integer.MAX_VALUE);
            }
            return;
        }
        List<FearlessSeries> updated = new ArrayList<>(finished);
        updated.replaceAll(s -> s.id().equals(series.id()) ? series : s);
        write(active, updated, Integer.MAX_VALUE);
    }

    /**
     * Holt eine Serie aus dem Papierkorb zurück, einsortiert nach ihrem Ende. Ohne Kürzung auf {@code limit}: Erst das
     * nächste Schreiben mit Grenze fasst die Liste wieder an, wie bei einem neuen Archiv-Eintrag.
     *
     * @return ob sie im Papierkorb lag
     */
    public synchronized boolean restore(String id) throws IOException {
        load();
        TrashedSeries restored = trash.stream().filter(t -> t.series().id().equals(id)).findFirst().orElse(null);
        if (restored == null) {
            return false;
        }
        List<FearlessSeries> updated = new ArrayList<>(finished);
        updated.add(restored.series());
        updated.sort(Comparator.comparing(FearlessSeries::endedAt, Comparator.<Instant>reverseOrder()));
        List<TrashedSeries> updatedTrash = new ArrayList<>(trash);
        updatedTrash.remove(restored);
        write(active, updated, updatedTrash, Integer.MAX_VALUE);
        return true;
    }

    /**
     * Löscht eine Serie aus dem Papierkorb endgültig.
     *
     * @return ob sie dort lag
     */
    public synchronized boolean purge(String id) throws IOException {
        load();
        List<TrashedSeries> updatedTrash = new ArrayList<>(trash);
        if (!updatedTrash.removeIf(t -> t.series().id().equals(id))) {
            return false;
        }
        write(active, finished, updatedTrash, Integer.MAX_VALUE);
        return true;
    }

    /** Leert den Papierkorb. @return Anzahl der endgültig gelöschten Serien */
    public synchronized int emptyTrash() throws IOException {
        load();
        int count = trash.size();
        if (count > 0) {
            write(active, finished, List.of(), Integer.MAX_VALUE);
        }
        return count;
    }

    private static List<FearlessSeries> prepend(FearlessSeries series, List<FearlessSeries> list) {
        List<FearlessSeries> updated = new ArrayList<>();
        updated.add(series);
        updated.addAll(list);
        return updated;
    }

    /**
     * Einmaliges Nachtragen ins Draft-Archiv: Bis Version 1 kamen abgeschlossene Spiele nicht ins Archiv. Liefert beim
     * ersten Aufruf nach dem Lesen einer solchen Datei alle Spiele laufender und beendeter Serien (nicht im Papierkorb)
     * und schreibt die Datei als aktuelle Version, danach eine leere Liste.
     */
    public synchronized List<Game> takeArchiveBackfill() throws IOException {
        load();
        if (!archiveBackfill) {
            return List.of();
        }
        archiveBackfill = false;
        List<Game> games = new ArrayList<>();
        if (active != null) {
            games.addAll(active.games());
        }
        finished.forEach(series -> games.addAll(series.games()));
        write(active, finished, Integer.MAX_VALUE);
        return games;
    }

    // ------------------------------------------------------------------ Datei

    /** Lädt einmal aus der Datei und räumt bei jedem Zugriff abgelaufene Serien aus dem Papierkorb (auch in der Datei). */
    private void load() throws IOException {
        if (finished == null) {
            readFile();
        }
        Instant now = clock.instant();
        if (trash.stream().anyMatch(t -> !t.expiresAt().isAfter(now))) {
            write(active, finished, trash.stream().filter(t -> t.expiresAt().isAfter(now)).toList(), Integer.MAX_VALUE);
        }
    }

    /** Liest die Datei. Einzeln gelöschte Spiele früherer Versionen ({@code trashedGames}) werden verworfen. */
    private void readFile() {
        finished = List.of();
        trash = List.of();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonNode root = Json.MAPPER.readTree(Files.readAllBytes(file));
            if (!root.isObject()) {
                unreadable = true;
                return;
            }
            archiveBackfill = root.path("version").asInt(1) < 2;
            FearlessSeries running = series(root.path("active"));
            active = running != null && running.active() ? running : null;
            List<FearlessSeries> loaded = new ArrayList<>();
            for (JsonNode node : root.path("finished")) {
                FearlessSeries series = series(node);
                if (series != null && !series.active() && !series.games().isEmpty()) {
                    loaded.add(series);
                }
            }
            List<TrashedSeries> deleted = new ArrayList<>();
            for (JsonNode node : root.path("trash")) {
                FearlessSeries series = series(node);
                if (series != null && !series.active() && node.path("deletedAt").isTextual()) {
                    try {
                        deleted.add(new TrashedSeries(series, Instant.parse(node.path("deletedAt").asText())));
                    } catch (RuntimeException e) {
                        // beschädigter Zeitpunkt: Serie überspringen
                    }
                }
            }
            finished = List.copyOf(loaded);
            trash = List.copyOf(deleted);
        } catch (IOException e) {
            // Unlesbar: Beim nächsten Schreiben wird sie beiseitegelegt statt überschrieben.
            unreadable = true;
        }
    }

    private void write(FearlessSeries newActive, List<FearlessSeries> newFinished, int limit) throws IOException {
        write(newActive, newFinished, trash, limit);
    }

    private void write(FearlessSeries newActive, List<FearlessSeries> newFinished, List<TrashedSeries> newTrash, int limit)
            throws IOException {
        if (unreadable && Files.isRegularFile(file)) {
            Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
            unreadable = false;
        }
        List<FearlessSeries> kept = List.copyOf(newFinished.subList(0, Math.min(Math.max(1, limit), newFinished.size())));
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("version", FORMAT_VERSION);
        if (newActive == null) {
            root.putNull("active");
        } else {
            writeSeries(root.putObject("active"), newActive);
        }
        ArrayNode list = root.putArray("finished");
        kept.forEach(series -> writeSeries(list.addObject(), series));
        ArrayNode deleted = root.putArray("trash");
        for (TrashedSeries t : newTrash) {
            ObjectNode node = deleted.addObject();
            writeSeries(node, t.series());
            node.put("deletedAt", t.deletedAt().toString());
        }
        AtomicFiles.write(file, Json.prettyBytes(root));
        active = newActive;
        finished = kept;
        trash = List.copyOf(newTrash);
    }

    private static void writeSeries(ObjectNode node, FearlessSeries series) {
        node.put("id", series.id());
        node.put("label", series.label());
        node.put("format", series.format().name());
        node.put("maxGames", series.maxGames());
        node.put("startedAt", series.startedAt().toString());
        node.put("endedAt", series.endedAt() == null ? null : series.endedAt().toString());
        ArrayNode games = node.putArray("games");
        for (Game game : series.games()) {
            writeGame(games.addObject(), game);
        }
    }

    private static void writeGame(ObjectNode g, Game game) {
        g.put("number", game.number());
        g.put("playedAt", game.playedAt().toString());
        g.put("patch", game.patch());
        g.put("result", game.result() == null ? null : game.result().name());
        DraftJson.writeDraft(g, game.draft(), game.shownRoles());
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
            JsonNode ended = node.path("endedAt");
            List<Game> games = new ArrayList<>();
            for (JsonNode g : node.path("games")) {
                Game game = game(g, games.size() + 1);
                if (game == null || games.size() >= FearlessSeries.MAX_GAMES) {
                    return null;
                }
                games.add(game);
            }
            // Ohne "maxGames" (ältere Dateien) gilt der Standard des Formats.
            return new FearlessSeries(id, storedLabel(node.path("label")), Format.valueOf(node.path("format").asText()),
                    node.path("maxGames").asInt(0), Instant.parse(node.path("startedAt").asText()),
                    ended.isTextual() ? Instant.parse(ended.asText()) : null, games);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Gespeicherte Bezeichnung, auf die Höchstlänge gekürzt; {@code null}, wenn keine. */
    private static String storedLabel(JsonNode node) {
        String label = node.isTextual() ? node.asText() : null;
        if (label != null && label.length() > FearlessSeries.MAX_LABEL_LENGTH) {
            label = label.substring(0, FearlessSeries.MAX_LABEL_LENGTH);
        }
        return FearlessSeries.cleanLabel(label);
    }

    /**
     * Ein Spiel aus der Datei mit Nummer {@code number}; {@code null} bei unvollständigem Draft.
     *
     * @throws RuntimeException bei beschädigten Feldern
     */
    private static Game game(JsonNode g, int number) {
        Draft draft = DraftJson.readDraft(g);
        if (draft == null) {
            return null;
        }
        JsonNode result = g.path("result");
        return new Game(number, Instant.parse(g.path("playedAt").asText()), g.path("patch").asText(""),
                draft, result.isTextual() ? Result.valueOf(result.asText()) : null, DraftJson.readShownRoles(g));
    }
}
