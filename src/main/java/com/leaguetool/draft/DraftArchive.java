package com.leaguetool.draft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leaguetool.draft.RolePredictor.Slot;
import com.leaguetool.model.Role;
import com.leaguetool.util.AtomicFiles;
import com.leaguetool.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Archiv der zuletzt vollständigen Drafts, nur lokal in einer Datei im Nutzerordner.
 *
 * <p>Gespeichert werden ausschließlich Champion-Keys, feste Rollen und Bans vom Draft-Brett, dazu Zeitpunkt und Patch.
 * Keine Namen, Riot-IDs oder PUUIDs, nichts verlässt den Rechner (Riot: keine De-Anonymisierung, siehe
 * {@code docs/client-sync.md}).
 *
 * <p>Ein vollständiger Draft wird nur aufgenommen, wenn er sich von jedem gezeigten Eintrag in mindestens
 * {@link #MIN_CHANGES} Champions unterscheidet. So lässt sich ein geladener Draft beliebig mit einzelnen anderen Picks
 * durchspielen, ohne dass jede Variante einen Eintrag anlegt und ältere Drafts aus dem Archiv drängt.
 *
 * <p>Gelöschte Einträge kommen in einen Papierkorb (in derselben Datei) und werden nach {@link #TRASH_RETENTION}
 * endgültig entfernt; bis dahin lassen sie sich wiederherstellen.
 */
public final class DraftArchive {

    /** Ab so vielen ausgetauschten Champions gilt ein Draft als neu. Zwei echte Spiele teilen praktisch nie 8 von 10. */
    public static final int MIN_CHANGES = 3;
    public static final int TEAM_SIZE = 5;
    public static final int MAX_BANS = 10;
    private static final int FORMAT_VERSION = 1;
    /** So lange bleiben gelöschte Drafts wiederherstellbar. */
    public static final Duration TRASH_RETENTION = Duration.ofDays(7);

    /** Ein Draft vom Brett. */
    public record Draft(List<Slot> ally, List<Slot> enemy, List<Integer> bans) {

        public Draft {
            ally = List.copyOf(ally);
            enemy = List.copyOf(enemy);
            bans = List.copyOf(bans);
        }

        public boolean complete() {
            return ally.size() == TEAM_SIZE && enemy.size() == TEAM_SIZE;
        }
    }

    /**
     * @param shownRoles Champion → Rolle, die das Brett beim Speichern automatisch zeigte (keine feste Rolle; nur zum
     *                   Ordnen Top bis Support, siehe {@link RoleOrder}); nur für Champions ohne feste Rolle
     */
    public record Entry(String id, Instant savedAt, String patch, Draft draft, Map<Integer, Role> shownRoles) {

        public Entry {
            shownRoles = openShown(draft, shownRoles);
        }
    }

    /** Nur die angezeigten Auto-Rollen von Champions, die im Draft keine feste Rolle haben. */
    static Map<Integer, Role> openShown(Draft draft, Map<Integer, Role> shown) {
        if (shown == null || shown.isEmpty()) {
            return Map.of();
        }
        Map<Integer, Role> open = new java.util.HashMap<>();
        java.util.stream.Stream.concat(draft.ally().stream(), draft.enemy().stream())
                .filter(slot -> slot.forcedRole() == null && shown.get(slot.champion()) != null)
                .forEach(slot -> open.put(slot.champion(), shown.get(slot.champion())));
        return Map.copyOf(open);
    }

    /** Ein gelöschter Eintrag im Papierkorb; nach {@link #TRASH_RETENTION} wird er endgültig entfernt. */
    public record TrashEntry(Entry entry, Instant deletedAt) {

        public Instant expiresAt() {
            return deletedAt.plus(TRASH_RETENTION);
        }
    }

    /**
     * @param entry   der neue Eintrag, sonst der ähnlichste vorhandene
     * @param changes ausgetauschte Champions gegenüber {@code entry} (0 beim neuen Eintrag)
     */
    public record Outcome(boolean saved, Entry entry, int changes) {
    }

    private final Path file;
    private final Clock clock;
    private List<Entry> entries;
    private List<TrashEntry> trash;
    private boolean unreadable;

    public DraftArchive(Path file) {
        this(file, Clock.systemUTC());
    }

    public DraftArchive(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    /** Die neuesten {@code limit} Einträge, der neueste zuerst. */
    public synchronized List<Entry> entries(int limit) throws IOException {
        List<Entry> all = load();
        return List.copyOf(all.subList(0, Math.min(Math.max(1, limit), all.size())));
    }

    /** Der Eintrag mit dieser ID (nicht im Papierkorb) oder leer. */
    public synchronized Optional<Entry> entry(String id) throws IOException {
        return load().stream().filter(e -> e.id().equals(id)).findFirst();
    }

    /**
     * Nimmt einen vollständigen Draft auf, wenn er sich von jedem der gezeigten Einträge in mindestens
     * {@link #MIN_CHANGES} Champions unterscheidet. Ältere Einträge über {@code limit} fallen dabei weg.
     *
     * @throws IllegalArgumentException wenn der Draft nicht vollständig ist
     */
    public synchronized Outcome record(Draft draft, String patch, int limit) throws IOException {
        return record(draft, patch, Map.of(), limit);
    }

    /** @param shownRoles angezeigte Auto-Rollen beim Aufnehmen, siehe {@link Entry} */
    public synchronized Outcome record(Draft draft, String patch, Map<Integer, Role> shownRoles, int limit) throws IOException {
        if (!draft.complete()) {
            throw new IllegalArgumentException("Nur vollständige Drafts werden archiviert.");
        }
        List<Entry> visible = entries(limit);
        Entry closest = null;
        int fewest = Integer.MAX_VALUE;
        for (Entry entry : visible) {
            int changes = changes(entry.draft(), draft);
            if (changes < fewest) {
                fewest = changes;
                closest = entry;
            }
        }
        if (closest != null && fewest < MIN_CHANGES) {
            return new Outcome(false, closest, fewest);
        }
        Entry entry = new Entry(UUID.randomUUID().toString(), clock.instant(), patch == null ? "" : patch, draft, shownRoles);
        List<Entry> updated = new ArrayList<>();
        updated.add(entry);
        updated.addAll(visible);
        write(updated.subList(0, Math.min(Math.max(1, limit), updated.size())), trash);
        return new Outcome(true, entry, 0);
    }

    /**
     * Ändert nur die festen Rollen eines gespeicherten Eintrags (z. B. im Nachhinein korrigiert). Zeitpunkt, Patch und Bans
     * bleiben, die Reihenfolge je Team kommt vom Brett; ein Draft mit anderen Champions geht weiter über {@link #record}.
     *
     * @return der geänderte Eintrag oder {@code null}, wenn es ihn nicht gibt
     * @throws IllegalArgumentException wenn sich Champions unterscheiden oder eine Rolle doppelt vergeben ist
     */
    public synchronized Entry updateRoles(String id, List<Slot> ally, List<Slot> enemy) throws IOException {
        return updateRoles(id, ally, enemy, Map.of());
    }

    /** @param shownRoles die jetzt angezeigten Auto-Rollen (ersetzen die gespeicherten), siehe {@link Entry} */
    public synchronized Entry updateRoles(String id, List<Slot> ally, List<Slot> enemy, Map<Integer, Role> shownRoles) throws IOException {
        List<Entry> all = new ArrayList<>(load());
        for (int i = 0; i < all.size(); i++) {
            Entry entry = all.get(i);
            if (entry.id().equals(id)) {
                Entry updated = new Entry(entry.id(), entry.savedAt(), entry.patch(), withRoles(entry.draft(), ally, enemy), shownRoles);
                all.set(i, updated);
                write(all, trash);
                return updated;
            }
        }
        return null;
    }

    /**
     * Übernimmt die festen Rollen eines geänderten Drafts (z. B. Fearless-Spiel) in alle gleichen Einträge
     * ({@link #changes} = 0), auch mit vertauschten Seiten. Einträge mit schon gleichen Rollen bleiben unberührt.
     *
     * @return Anzahl der geänderten Einträge
     */
    public synchronized int updateRolesMatching(Draft changed) throws IOException {
        return updateRolesMatching(changed, Map.of());
    }

    /** @param shownRoles angezeigte Auto-Rollen des geänderten Drafts, siehe {@link Entry} */
    public synchronized int updateRolesMatching(Draft changed, Map<Integer, Role> shownRoles) throws IOException {
        List<Entry> all = new ArrayList<>(load());
        int count = 0;
        for (int i = 0; i < all.size(); i++) {
            Entry entry = all.get(i);
            if (changes(entry.draft(), changed) != 0) {
                continue;
            }
            boolean sameSides = sameChampions(entry.draft().ally(), changed.ally());
            Draft updated = sameSides ? withRoles(entry.draft(), changed.ally(), changed.enemy())
                    : withRoles(entry.draft(), changed.enemy(), changed.ally());
            if (!updated.equals(entry.draft())) {
                all.set(i, new Entry(entry.id(), entry.savedAt(), entry.patch(), updated, shownRoles));
                count++;
            }
        }
        if (count > 0) {
            write(all, trash);
        }
        return count;
    }

    /**
     * Ersetzt alle Einträge, die {@code before} gleichen ({@link #changes} = 0, z. B. der Archiv-Draft eines Fearless-Spiels),
     * durch {@code after}; ID, Speicherdatum und Patch bleiben. Der Papierkorb bleibt unberührt.
     *
     * @return Anzahl der ersetzten Einträge
     */
    public synchronized int replaceMatching(Draft before, Draft after, Map<Integer, Role> shownRoles) throws IOException {
        List<Entry> all = new ArrayList<>(load());
        int count = 0;
        for (int i = 0; i < all.size(); i++) {
            Entry entry = all.get(i);
            if (changes(entry.draft(), before) == 0 && !(entry.draft().equals(after) && entry.shownRoles().equals(shownRoles))) {
                all.set(i, new Entry(entry.id(), entry.savedAt(), entry.patch(), after, shownRoles));
                count++;
            }
        }
        if (count > 0) {
            write(all, trash);
        }
        return count;
    }

    /**
     * Legt einen Draft (z. B. ein Fearless-Spiel) wieder an, wenn es weder unter den Einträgen noch im Papierkorb einen
     * gleichen gibt ({@link #changes} = 0), etwa nach endgültigem Löschen. Ohne Varianten-Regel und ohne Kürzung auf das
     * Limit; einsortiert nach {@code savedAt} wie beim Wiederherstellen.
     *
     * @return der neue Eintrag oder {@code null}, wenn ein gleicher existiert
     * @throws IllegalArgumentException wenn der Draft nicht vollständig ist
     */
    public synchronized Entry reconstruct(Draft draft, Instant savedAt, String patch) throws IOException {
        return reconstruct(draft, savedAt, patch, Map.of());
    }

    /** @param shownRoles angezeigte Auto-Rollen des Drafts, siehe {@link Entry} */
    public synchronized Entry reconstruct(Draft draft, Instant savedAt, String patch, Map<Integer, Role> shownRoles) throws IOException {
        if (!draft.complete()) {
            throw new IllegalArgumentException("Nur vollständige Drafts werden archiviert.");
        }
        List<Entry> all = new ArrayList<>(load());
        boolean exists = all.stream().anyMatch(e -> changes(e.draft(), draft) == 0)
                || trash.stream().anyMatch(t -> changes(t.entry().draft(), draft) == 0);
        if (exists) {
            return null;
        }
        Entry entry = new Entry(UUID.randomUUID().toString(), savedAt, patch == null ? "" : patch, draft, shownRoles);
        all.add(entry);
        all.sort(Comparator.comparing(Entry::savedAt).reversed());
        write(all, trash);
        return entry;
    }

    private static boolean sameChampions(List<Slot> a, List<Slot> b) {
        Set<Integer> champions = new HashSet<>();
        a.forEach(s -> champions.add(s.champion()));
        return a.size() == b.size() && b.stream().allMatch(s -> champions.contains(s.champion()));
    }

    /**
     * Der gespeicherte Draft mit den festen Rollen von {@code ally}/{@code enemy}. Erlaubt ist nur ein Wechsel der Rollen:
     * Jede Seite muss genau dieselben Champions haben (keine vertauschten Seiten); die Reihenfolge kommt aus {@code ally}/{@code enemy}.
     *
     * @throws IllegalArgumentException wenn sich Champions unterscheiden oder eine Rolle in einem Team doppelt vorkommt
     */
    public static Draft withRoles(Draft stored, List<Slot> ally, List<Slot> enemy) {
        return new Draft(teamWithRoles(stored.ally(), ally), teamWithRoles(stored.enemy(), enemy), stored.bans());
    }

    private static List<Slot> teamWithRoles(List<Slot> stored, List<Slot> changed) {
        java.util.Map<Integer, Role> roles = new java.util.HashMap<>();
        Set<Role> used = new HashSet<>();
        for (Slot slot : changed) {
            if (roles.containsKey(slot.champion())) {
                throw new IllegalArgumentException("Ein Champion kann nur einmal gewählt werden.");
            }
            if (slot.forcedRole() != null && !used.add(slot.forcedRole())) {
                throw new IllegalArgumentException("Jede Rolle kann je Team nur einmal fest vergeben werden.");
            }
            roles.put(slot.champion(), slot.forcedRole());
        }
        if (roles.size() != stored.size() || !stored.stream().allMatch(s -> roles.containsKey(s.champion()))) {
            throw new IllegalArgumentException("Nur Rollen lassen sich überschreiben; bei anderen Champions entsteht ein neuer Draft.");
        }
        // Reihenfolge wie auf dem Brett: So zeigen die Listen das Team so, wie es beim Speichern zu sehen war.
        return List.copyOf(changed);
    }

    /**
     * Legt einen Eintrag in den Papierkorb. Dort bleibt er {@link #TRASH_RETENTION} wiederherstellbar.
     *
     * @return ob es den Eintrag gab
     */
    public synchronized boolean delete(String id) throws IOException {
        List<Entry> all = new ArrayList<>(load());
        Entry removed = all.stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
        if (removed == null) {
            return false;
        }
        all.remove(removed);
        List<TrashEntry> updatedTrash = new ArrayList<>();
        updatedTrash.add(new TrashEntry(removed, clock.instant()));
        updatedTrash.addAll(trash);
        write(all, updatedTrash);
        return true;
    }

    /**
     * Legt alle Einträge in den Papierkorb, die einem der Drafts entsprechen (dieselben zehn Champions, {@link #changes}
     * = 0). Für gelöschte Fearless-Serien, deren Spiele auch im Archiv stehen.
     *
     * @return Anzahl der verschobenen Einträge
     */
    public synchronized int deleteMatching(List<Draft> drafts) throws IOException {
        List<Entry> all = new ArrayList<>(load());
        List<Entry> matching = all.stream().filter(e -> drafts.stream().anyMatch(d -> changes(e.draft(), d) == 0)).toList();
        if (matching.isEmpty()) {
            return 0;
        }
        all.removeAll(matching);
        Instant now = clock.instant();
        List<TrashEntry> updatedTrash = new ArrayList<>();
        matching.forEach(e -> updatedTrash.add(new TrashEntry(e, now)));
        updatedTrash.addAll(trash);
        write(all, updatedTrash);
        return matching.size();
    }

    /** Holt alle Einträge aus dem Papierkorb zurück, die einem der Drafts entsprechen. @return Anzahl */
    public synchronized int restoreMatching(List<Draft> drafts) throws IOException {
        load();
        List<TrashEntry> matching = trash.stream()
                .filter(t -> drafts.stream().anyMatch(d -> changes(t.entry().draft(), d) == 0)).toList();
        if (matching.isEmpty()) {
            return 0;
        }
        List<Entry> all = new ArrayList<>(entries);
        matching.forEach(t -> all.add(t.entry()));
        all.sort(Comparator.comparing(Entry::savedAt).reversed());
        List<TrashEntry> updatedTrash = new ArrayList<>(trash);
        updatedTrash.removeAll(matching);
        write(all, updatedTrash);
        return matching.size();
    }

    /** Der Papierkorb, zuletzt gelöschter zuerst; abgelaufene Einträge sind schon entfernt. */
    public synchronized List<TrashEntry> trash() throws IOException {
        load();
        return trash;
    }

    /**
     * Holt einen Eintrag aus dem Papierkorb zurück, einsortiert nach seinem Speicherzeitpunkt.
     *
     * @return ob er im Papierkorb lag
     */
    public synchronized boolean restore(String id) throws IOException {
        load();
        TrashEntry restored = trash.stream().filter(t -> t.entry().id().equals(id)).findFirst().orElse(null);
        if (restored == null) {
            return false;
        }
        List<Entry> all = new ArrayList<>(entries);
        all.add(restored.entry());
        all.sort(Comparator.comparing(Entry::savedAt).reversed());
        List<TrashEntry> updatedTrash = new ArrayList<>(trash);
        updatedTrash.remove(restored);
        write(all, updatedTrash);
        return true;
    }

    /** Löscht einen Eintrag aus dem Papierkorb endgültig. @return ob er dort lag */
    public synchronized boolean purge(String id) throws IOException {
        load();
        List<TrashEntry> updatedTrash = new ArrayList<>(trash);
        if (!updatedTrash.removeIf(t -> t.entry().id().equals(id))) {
            return false;
        }
        write(entries, updatedTrash);
        return true;
    }

    /** Leert den Papierkorb. @return Anzahl der endgültig gelöschten Einträge */
    public synchronized int emptyTrash() throws IOException {
        load();
        int count = trash.size();
        if (count > 0) {
            write(entries, List.of());
        }
        return count;
    }

    /**
     * Wie viele Champions ausgetauscht werden müssen, um aus {@code a} den Draft {@code b} zu machen. Rollen,
     * Reihenfolge und Bans zählen nicht; ein Draft mit vertauschten Seiten gilt als derselbe.
     */
    public static int changes(Draft a, Draft b) {
        int direct = missing(a.ally(), b.ally()) + missing(a.enemy(), b.enemy());
        int swapped = missing(a.ally(), b.enemy()) + missing(a.enemy(), b.ally());
        return Math.min(direct, swapped);
    }

    private static int missing(List<Slot> from, List<Slot> to) {
        Set<Integer> champions = new HashSet<>();
        from.forEach(slot -> champions.add(slot.champion()));
        return (int) to.stream().filter(slot -> !champions.contains(slot.champion())).count();
    }

    // ------------------------------------------------------------------ Datei

    /** Lädt einmal aus der Datei und räumt bei jedem Zugriff abgelaufene Einträge aus dem Papierkorb (auch in der Datei). */
    private List<Entry> load() throws IOException {
        if (entries == null) {
            readFile();
        }
        Instant now = clock.instant();
        if (trash.stream().anyMatch(t -> !t.expiresAt().isAfter(now))) {
            write(entries, trash.stream().filter(t -> t.expiresAt().isAfter(now)).toList());
        }
        return entries;
    }

    private void readFile() {
        entries = List.of();
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
            List<Entry> loaded = new ArrayList<>();
            for (JsonNode node : root.path("entries")) {
                Entry entry = entry(node);
                if (entry != null) {
                    loaded.add(entry);
                }
            }
            List<TrashEntry> deleted = new ArrayList<>();
            for (JsonNode node : root.path("trash")) {
                Entry entry = entry(node);
                if (entry != null && node.path("deletedAt").isTextual()) {
                    try {
                        deleted.add(new TrashEntry(entry, Instant.parse(node.path("deletedAt").asText())));
                    } catch (RuntimeException e) {
                        // beschädigter Zeitpunkt: Eintrag überspringen
                    }
                }
            }
            entries = List.copyOf(loaded);
            trash = List.copyOf(deleted);
        } catch (IOException e) {
            // Unlesbar: Beim nächsten Schreiben wird sie beiseitegelegt statt überschrieben.
            unreadable = true;
        }
    }

    private void write(List<Entry> updated, List<TrashEntry> updatedTrash) throws IOException {
        if (unreadable && Files.isRegularFile(file)) {
            Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
            unreadable = false;
        }
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("version", FORMAT_VERSION);
        ArrayNode list = root.putArray("entries");
        updated.forEach(entry -> writeEntry(list.addObject(), entry));
        ArrayNode deleted = root.putArray("trash");
        updatedTrash.forEach(t -> writeEntry(deleted.addObject(), t.entry()).put("deletedAt", t.deletedAt().toString()));
        AtomicFiles.write(file, Json.prettyBytes(root));
        entries = List.copyOf(updated);
        trash = List.copyOf(updatedTrash);
    }

    private static ObjectNode writeEntry(ObjectNode node, Entry entry) {
        node.put("id", entry.id());
        node.put("savedAt", entry.savedAt().toString());
        node.put("patch", entry.patch());
        DraftJson.writeDraft(node, entry.draft(), entry.shownRoles());
        return node;
    }

    /** Ein Eintrag aus der Datei; {@code null}, wenn er unvollständig oder beschädigt ist. */
    private static Entry entry(JsonNode node) {
        try {
            Draft draft = DraftJson.readDraft(node);
            String id = node.path("id").asText("");
            if (draft == null || id.isEmpty()) {
                return null;
            }
            return new Entry(id, Instant.parse(node.path("savedAt").asText()), node.path("patch").asText(""), draft, DraftJson.readShownRoles(node));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
