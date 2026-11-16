package com.leaguetool.draft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leaguetool.draft.RolePredictor.Slot;
import com.leaguetool.model.Role;
import com.leaguetool.util.AtomicFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
 */
public final class DraftArchive {

    /** Ab so vielen ausgetauschten Champions gilt ein Draft als neu. Zwei echte Spiele teilen praktisch nie 8 von 10. */
    public static final int MIN_CHANGES = 3;
    public static final int TEAM_SIZE = 5;
    public static final int MAX_BANS = 10;
    private static final int FORMAT_VERSION = 1;

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

    public record Entry(String id, Instant savedAt, String patch, Draft draft) {
    }

    /**
     * @param entry   der neue Eintrag, sonst der ähnlichste vorhandene
     * @param changes ausgetauschte Champions gegenüber {@code entry} (0 beim neuen Eintrag)
     */
    public record Outcome(boolean saved, Entry entry, int changes) {
    }

    private final Path file;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    private List<Entry> entries;
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

    /**
     * Nimmt einen vollständigen Draft auf, wenn er sich von jedem der gezeigten Einträge in mindestens
     * {@link #MIN_CHANGES} Champions unterscheidet. Ältere Einträge über {@code limit} fallen dabei weg.
     *
     * @throws IllegalArgumentException wenn der Draft nicht vollständig ist
     */
    public synchronized Outcome record(Draft draft, String patch, int limit) throws IOException {
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
        Entry entry = new Entry(UUID.randomUUID().toString(), clock.instant(), patch == null ? "" : patch, draft);
        List<Entry> updated = new ArrayList<>();
        updated.add(entry);
        updated.addAll(visible);
        write(updated.subList(0, Math.min(Math.max(1, limit), updated.size())));
        return new Outcome(true, entry, 0);
    }

    /** @return ob es den Eintrag gab */
    public synchronized boolean delete(String id) throws IOException {
        List<Entry> all = new ArrayList<>(load());
        if (!all.removeIf(e -> e.id().equals(id))) {
            return false;
        }
        write(all);
        return true;
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

    private List<Entry> load() throws IOException {
        if (entries != null) {
            return entries;
        }
        entries = List.of();
        if (!Files.isRegularFile(file)) {
            return entries;
        }
        try {
            JsonNode root = json.readTree(Files.readAllBytes(file));
            List<Entry> loaded = new ArrayList<>();
            for (JsonNode node : root.path("entries")) {
                Entry entry = entry(node);
                if (entry != null) {
                    loaded.add(entry);
                }
            }
            entries = List.copyOf(loaded);
        } catch (IOException e) {
            // Unlesbar: Beim nächsten Schreiben wird sie beiseitegelegt statt überschrieben.
            unreadable = true;
        }
        return entries;
    }

    private void write(List<Entry> updated) throws IOException {
        if (unreadable && Files.isRegularFile(file)) {
            Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
            unreadable = false;
        }
        ObjectNode root = json.createObjectNode();
        root.put("version", FORMAT_VERSION);
        ArrayNode list = root.putArray("entries");
        for (Entry entry : updated) {
            ObjectNode node = list.addObject();
            node.put("id", entry.id());
            node.put("savedAt", entry.savedAt().toString());
            node.put("patch", entry.patch());
            writeSlots(node.putArray("ally"), entry.draft().ally());
            writeSlots(node.putArray("enemy"), entry.draft().enemy());
            ArrayNode bans = node.putArray("bans");
            entry.draft().bans().forEach(bans::add);
        }
        AtomicFiles.write(file, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
        entries = List.copyOf(updated);
    }

    static void writeSlots(ArrayNode target, List<Slot> slots) {
        for (Slot slot : slots) {
            ObjectNode node = target.addObject();
            node.put("champion", slot.champion());
            if (slot.forcedRole() == null) {
                node.putNull("role");
            } else {
                node.put("role", slot.forcedRole().name());
            }
        }
    }

    /** Ein Eintrag aus der Datei; {@code null}, wenn er unvollständig oder beschädigt ist. */
    private static Entry entry(JsonNode node) {
        try {
            List<Slot> ally = slots(node.path("ally"));
            List<Slot> enemy = slots(node.path("enemy"));
            List<Integer> bans = new ArrayList<>();
            node.path("bans").forEach(ban -> bans.add(ban.asInt()));
            Draft draft = new Draft(ally, enemy, bans.subList(0, Math.min(MAX_BANS, bans.size())));
            String id = node.path("id").asText("");
            if (!draft.complete() || id.isEmpty()) {
                return null;
            }
            return new Entry(id, Instant.parse(node.path("savedAt").asText()), node.path("patch").asText(""), draft);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static List<Slot> slots(JsonNode list) {
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
