package com.leaguetool.cache;

import com.leaguetool.util.AtomicFiles;
import com.leaguetool.util.Patches;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Datei-Cache, gegliedert nach Patch-Version: {@code <root>/<scope>/<key>}.
 *
 * <p>{@code scope} ist eine Patch-Version (z. B. {@code 16.22.1}) für unveränderliche Patch-Daten oder
 * {@link #GLOBAL} für patch-unabhängige Daten (versions.json, Wiki, Rune-Icons). Schlüssel dürfen
 * Unterordner enthalten ({@code "img/champion/Ahri.png"}). Schreibvorgänge sind atomar.
 */
public final class FileCache {

    /** Scope für patch-unabhängige Einträge. */
    public static final String GLOBAL = "_global";

    private static final Pattern UNSAFE_CHARS = Pattern.compile("[^A-Za-z0-9._/\\-]");

    private final Path root;

    public FileCache(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** Pfad eines Eintrags (unabhängig davon, ob er existiert). */
    public Path pathFor(String scope, String key) {
        String safeScope = sanitizeSegment(scope == null || scope.isBlank() ? GLOBAL : scope);
        String safeKey = UNSAFE_CHARS.matcher(key).replaceAll("_");
        Path p = root.resolve(safeScope);
        for (String part : safeKey.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("Ungültiger Cache-Schlüssel: " + key);
            }
            p = p.resolve(part);
        }
        Path normalized = p.normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("Cache-Schlüssel verlässt das Cache-Verzeichnis: " + key);
        }
        return normalized;
    }

    public boolean contains(String scope, String key) {
        return Files.isRegularFile(pathFor(scope, key));
    }

    public Optional<String> readText(String scope, String key) throws IOException {
        Path p = pathFor(scope, key);
        return Files.isRegularFile(p) ? Optional.of(Files.readString(p, StandardCharsets.UTF_8)) : Optional.empty();
    }

    public Optional<Path> file(String scope, String key) {
        Path p = pathFor(scope, key);
        return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
    }

    public void writeText(String scope, String key, String content) throws IOException {
        writeBytes(scope, key, content.getBytes(StandardCharsets.UTF_8));
    }

    public Path writeBytes(String scope, String key, byte[] data) throws IOException {
        return AtomicFiles.write(pathFor(scope, key), data);
    }

    /** Entfernt einen Eintrag (z. B. wenn sein Inhalt unbrauchbar ist). */
    public void delete(String scope, String key) throws IOException {
        Files.deleteIfExists(pathFor(scope, key));
    }

    /** Zeitpunkt des letzten Schreibens oder leer, wenn der Eintrag fehlt. */
    public Optional<Instant> lastModified(String scope, String key) throws IOException {
        Path p = pathFor(scope, key);
        return Files.isRegularFile(p) ? Optional.of(Files.getLastModifiedTime(p).toInstant()) : Optional.empty();
    }

    /**
     * Dateinamen direkt im Unterordner {@code dir} eines Scopes (ohne Unterordner); leer, wenn er fehlt. Nur Namen,
     * die als Schlüssel unverändert bleiben ({@code dir + "/" + name} trifft genau diese Datei).
     */
    public List<String> list(String scope, String dir) throws IOException {
        Path d = pathFor(scope, dir);
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(d)) {
            return names;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(d, Files::isRegularFile)) {
            for (Path f : files) {
                String name = f.getFileName().toString();
                if (!UNSAFE_CHARS.matcher(name).find()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /**
     * Löscht alte Patch-Ordner. Es bleiben {@code current}, alle neueren (z. B. ein Patch, der gerade erst
     * heruntergeladen wird) und die {@code keepOlder} neuesten älteren als Rückfall ohne Netz; {@link #GLOBAL} und
     * andere Ordner bleiben unberührt. Nicht löschbare Dateien (z. B. gerade geöffnet) bleiben liegen.
     *
     * @return die gelöschten Patches
     */
    public List<String> prunePatches(String current, int keepOlder) throws IOException {
        if (!Patches.isRegularVersion(current)) {
            return List.of();
        }
        List<String> older = cachedPatches().stream().filter(p -> Patches.NEWEST_FIRST.compare(p, current) > 0).toList();
        List<String> removed = new ArrayList<>();
        for (String patch : older.subList(Math.min(Math.max(0, keepOlder), older.size()), older.size())) {
            deleteTree(root.resolve(patch));
            removed.add(patch);
        }
        return removed;
    }

    private static void deleteTree(Path dir) throws IOException {
        try (var paths = Files.walk(dir)) {
            // Tiefste Pfade zuerst, damit die Ordner beim Löschen leer sind.
            for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // gesperrte Datei (Windows) oder nicht leerer Ordner: beim nächsten Start erneut versuchen
                }
            }
        }
    }

    /**
     * {@code true}, wenn der Eintrag existiert, jünger als {@code maxAge} und nach {@code notBefore} geschrieben
     * ist (z. B. nach einem Patchwechsel).
     */
    public boolean isFresh(String scope, String key, Duration maxAge, Instant notBefore) throws IOException {
        Instant limit = Instant.now().minus(maxAge);
        Instant threshold = notBefore != null && notBefore.isAfter(limit) ? notBefore : limit;
        return lastModified(scope, key)
                .map(t -> t.isAfter(threshold))
                .orElse(false);
    }

    /**
     * Netz-first mit Cache-Fallback: ist der Eintrag jünger als {@code maxAge}, wird er direkt geliefert;
     * sonst wird abgerufen. Schlägt der Abruf fehl, wird ein (veralteter) Cache-Eintrag verwendet.
     */
    public CacheResult getFreshOrStale(String scope, String key, Duration maxAge, IoSupplier<String> fetcher)
            throws IOException {
        return getFreshOrStale(scope, key, maxAge, Instant.EPOCH, fetcher);
    }

    /**
     * Wie {@link #getFreshOrStale(String, String, Duration, IoSupplier)}; Einträge von vor {@code notBefore} gelten als
     * veraltet, bleiben aber Rückfall ohne Netz.
     */
    public CacheResult getFreshOrStale(String scope, String key, Duration maxAge, Instant notBefore,
                                       IoSupplier<String> fetcher) throws IOException {
        if (isFresh(scope, key, maxAge, notBefore)) {
            return new CacheResult(readText(scope, key).orElseThrow(), true, false);
        }
        try {
            String fresh = fetcher.get();
            writeText(scope, key, fresh);
            return new CacheResult(fresh, false, false);
        } catch (IOException e) {
            Optional<String> stale = readText(scope, key);
            if (stale.isPresent()) {
                return new CacheResult(stale.get(), true, true);
            }
            throw e;
        }
    }

    /** Alle Patch-Versionen, für die ein Cache-Ordner existiert (neueste zuerst). */
    public List<String> cachedPatches() throws IOException {
        List<String> result = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return result;
        }
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root, Files::isDirectory)) {
            for (Path d : dirs) {
                String name = d.getFileName().toString();
                if (Patches.isRegularVersion(name)) {
                    result.add(name);
                }
            }
        }
        result.sort(Patches.NEWEST_FIRST);
        return result;
    }

    private static String sanitizeSegment(String segment) {
        String s = UNSAFE_CHARS.matcher(segment).replaceAll("_").replace("/", "_");
        if (s.isEmpty() || s.equals(".") || s.equals("..")) {
            throw new IllegalArgumentException("Ungültiger Cache-Scope: " + segment);
        }
        return s;
    }

    /** Ergebnis von {@link #getFreshOrStale}. */
    public record CacheResult(String content, boolean fromCache, boolean stale) {
    }

    /** Supplier, der eine {@link IOException} werfen darf. */
    @FunctionalInterface
    public interface IoSupplier<T> {
        T get() throws IOException;
    }
}
