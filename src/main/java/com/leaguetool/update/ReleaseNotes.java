package com.leaguetool.update;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Versionshinweise der App aus {@code release-notes.json} im Jar (offline, mit dem Jar signiert, kein Netzzugriff).
 * Neueste Version zuerst; jede Version hat Abschnitte der Art {@code new}, {@code improved} oder {@code fixed}.
 */
public final class ReleaseNotes {

    static final String RESOURCE = "/com/leaguetool/update/release-notes.json";

    public record Section(String kind, List<String> items) {
    }

    public record Release(String version, String date, String title, List<Section> sections) {
    }

    private static volatile List<Release> cached;

    private ReleaseNotes() {
    }

    /** Alle Versionen, neueste zuerst (Einträge ohne gültige Version fallen weg). */
    public static List<Release> all() {
        List<Release> list = cached;
        if (list == null) {
            try (InputStream in = ReleaseNotes.class.getResourceAsStream(RESOURCE)) {
                list = in == null ? List.of() : parse(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            cached = list;
        }
        return list;
    }

    static List<Release> parse(InputStream in) throws IOException {
        List<Release> raw = new ObjectMapper().readValue(in, new TypeReference<List<Release>>() { });
        return raw.stream()
                .filter(r -> Version.parse(r.version()).isPresent())
                .sorted(Comparator.comparing((Release r) -> Version.parse(r.version()).orElseThrow()).reversed())
                .toList();
    }

    /**
     * Versionen, deren Hinweise nach einem Update noch zu zeigen sind: alle nach {@code seen} bis einschließlich
     * {@code current}. Ohne gespeicherte Version zeigt ein bestehender Nutzer (Einstellungen gab es schon, also ein
     * Update von einer Version vor dieser Funktion) nur die aktuelle Version, eine Neuinstallation nichts.
     */
    public static List<String> unseen(List<Release> all, String seen, String current, boolean existingUser) {
        Optional<Version> now = Version.parse(current);
        if (now.isEmpty()) {
            return List.of(); // Start ohne Maven-Version (IDE): keine Hinweise
        }
        Optional<Version> last = Version.parse(seen);
        if (last.isEmpty()) {
            return existingUser
                    ? all.stream().map(Release::version).filter(v -> Version.parse(v).orElseThrow().equals(now.get())).toList()
                    : List.of();
        }
        return all.stream()
                .map(Release::version)
                .filter(v -> {
                    Version version = Version.parse(v).orElseThrow();
                    return version.isNewerThan(last.get()) && !version.isNewerThan(now.get());
                })
                .toList();
    }
}
