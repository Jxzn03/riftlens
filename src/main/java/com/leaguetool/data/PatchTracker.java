package com.leaguetool.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leaguetool.cache.FileCache;
import com.leaguetool.util.Patches;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Merkt sich den zuletzt geladenen Patch ({@code cache/_global/patch/state.json}) und erkennt den ersten Start nach
 * einem Patchwechsel.
 *
 * <p>Der Zeitpunkt des Wechsels ({@link #changedAt()}) ist die Grenze für patch-abhängige Caches (Wiki, Lolalytics):
 * Was davor gespeichert wurde, gilt als veraltet und wird beim nächsten Zugriff neu geholt, bleibt aber ohne Netz
 * nutzbar. Offline geladene ältere Patches und Rückschritte zählen nicht als Wechsel.
 */
public final class PatchTracker {

    /** Ergebnis von {@link #observe}. */
    public record Change(String previous, String current, boolean isNew, Instant changedAt) {
    }

    static final String STATE_KEY = "patch/state.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FileCache cache;
    private volatile String patch = "";
    private volatile Instant changedAt = Instant.EPOCH;
    private volatile boolean loaded;

    public PatchTracker(FileCache cache) {
        this.cache = cache;
    }

    /** Zeitpunkt des letzten Patchwechsels; {@link Instant#EPOCH}, solange keiner bekannt ist. */
    public Instant changedAt() {
        load();
        return changedAt;
    }

    /** Zuletzt bekannter Patch oder leer. */
    public String patch() {
        load();
        return patch;
    }

    /**
     * Meldet den geladenen Patch. Ist er neuer als der gespeicherte, gilt das als Patchwechsel: Zeitpunkt und Patch
     * werden sofort gespeichert, damit der Abgleich nur beim ersten Start nach dem Wechsel läuft.
     *
     * @param offline {@code true}, wenn die Daten ohne Netz aus dem Cache kamen
     */
    public synchronized Change observe(String current, boolean offline) {
        load();
        String previous = patch;
        if (offline) {
            return new Change(previous, current, false, changedAt);
        }
        if (previous.isEmpty()) {
            // Erster Start mit dieser Programmversion: Stand merken, aber vorhandene Caches nicht verwerfen.
            patch = current;
            save(current, previous);
            return new Change(previous, current, false, changedAt);
        }
        if (Patches.NEWEST_FIRST.compare(current, previous) >= 0) {
            return new Change(previous, current, false, changedAt);
        }
        patch = current;
        changedAt = Instant.now();
        save(current, previous);
        return new Change(previous, current, true, changedAt);
    }

    private void save(String current, String previous) {
        try {
            ObjectNode state = MAPPER.createObjectNode().put("patch", current).put("previous", previous)
                    .put("changedAt", changedAt.toString());
            cache.writeText(FileCache.GLOBAL, STATE_KEY, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(state));
        } catch (IOException e) {
            // Ohne gespeicherten Stand läuft der Abgleich beim nächsten Start erneut; das schadet nicht.
        }
    }

    private void load() {
        if (loaded) {
            return;
        }
        synchronized (this) {
            if (loaded) {
                return;
            }
            try {
                JsonNode state = MAPPER.readTree(cache.readText(FileCache.GLOBAL, STATE_KEY).orElse("{}"));
                patch = state.path("patch").asText("");
                changedAt = Instant.parse(state.path("changedAt").asText(Instant.EPOCH.toString()));
            } catch (IOException | DateTimeParseException e) {
                patch = "";
                changedAt = Instant.EPOCH;
            }
            loaded = true;
        }
    }
}
