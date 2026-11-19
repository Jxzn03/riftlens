package com.leaguetool.data.lolalytics;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Merkt sich die zuletzt geparsten Cache-Inhalte. Jede Draft-Anfrage liest dieselben Tierlisten und Seiten, die schnelle
 * und die vollständige Anfrage sogar doppelt; das Parsen war gut die Hälfte ihrer Rechenzeit.
 *
 * <p>Rein lokal: Gelesen, geprüft und abgerufen wird genau wie vorher. Wiederverwendet wird ein Ergebnis nur, wenn der
 * gerade gelesene Text derselbe ist wie beim letzten Parsen; ein neu geschriebener Eintrag wird also neu geparst. Die
 * Ergebnisse ({@link LolalyticsFullTierList}, {@link LolalyticsBuild}, {@link LolalyticsPairs}) sind unveränderlich.
 */
final class ParsedMemo {

    /** Ein Draft braucht höchstens 10 Tierlisten, 10 Seiten und 8 Team-Werte; dazu ein paar aus dem Champions-Tab. */
    static final int DEFAULT_CAPACITY = 64;

    @FunctionalInterface
    interface Parser<T> {
        T parse(String json) throws IOException;
    }

    private record Parsed(String json, Object value) {
    }

    private final Map<String, Parsed> entries;

    ParsedMemo(int capacity) {
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Parsed> eldest) {
                return size() > capacity;
            }
        };
    }

    /** Das Ergebnis von {@code parser} für {@code json}; aus dem Speicher, wenn {@code key} zuletzt denselben Text hatte. */
    @SuppressWarnings("unchecked")
    <T> T parse(String key, String json, Parser<T> parser) throws IOException {
        Objects.requireNonNull(json, "json");
        synchronized (entries) {
            Parsed entry = entries.get(key);
            if (entry != null && entry.json().equals(json)) {
                return (T) entry.value();
            }
        }
        // Außerhalb der Sperre: Parallele Anfragen warten nicht aufeinander. Fehler landen nicht im Speicher.
        T value = parser.parse(json);
        synchronized (entries) {
            entries.put(key, new Parsed(json, value));
        }
        return value;
    }

    int size() {
        synchronized (entries) {
            return entries.size();
        }
    }
}
