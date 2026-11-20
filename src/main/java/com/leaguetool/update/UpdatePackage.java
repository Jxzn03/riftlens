package com.leaguetool.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.function.DoubleConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Herunterladen, Prüfen und Entpacken eines Release-ZIPs. Ein ZIP enthält genau einen Ordner {@code RiftLens/} mit
 * {@code RiftLens.exe}, {@code app/league-tool-modern.jar} und {@code runtime/} (jpackage-App-Image).
 */
final class UpdatePackage {

    static final String ROOT = "RiftLens";
    static final String EXE = "RiftLens.exe";
    static final String JAR = "app/league-tool-modern.jar";
    /** Obergrenzen gegen kaputte oder manipulierte Pakete (ein Release wiegt rund 36 MB gepackt, 80 MB entpackt). */
    static final long MAX_DOWNLOAD = 300L * 1024 * 1024;
    static final long MAX_EXTRACTED = 1024L * 1024 * 1024;
    private static final int MAX_ENTRIES = 5000;

    private UpdatePackage() {
    }

    /** Öffnet einen Download-Strom; Fehlerstatus wird als {@link IOException} gemeldet. */
    @FunctionalInterface
    interface Download {
        InputStream open(String url) throws IOException;
    }

    /**
     * Lädt {@code asset} nach {@code target} (über eine {@code .part}-Datei) und prüft die Größe und, falls GitHub
     * eine angibt, die SHA-256.
     *
     * @param progress Anteil 0..1
     * @return SHA-256 der geladenen Datei (hex, klein) für die Signaturprüfung
     * @throws IOException bei Abbruch, falscher Größe oder Prüfsumme (die Datei ist dann gelöscht)
     */
    static String download(Download download, GitHubRelease.Asset asset, Path target, DoubleConsumer progress) throws IOException {
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        MessageDigest sha = sha256();
        long total = 0;
        try (InputStream in = download.open(asset.url()); OutputStream out = Files.newOutputStream(part)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > asset.size() || total > MAX_DOWNLOAD) {
                    throw new IOException("Download größer als angekündigt (" + asset.size() + " Bytes)");
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Download abgebrochen");
                }
                sha.update(buffer, 0, n);
                out.write(buffer, 0, n);
                progress.accept((double) total / asset.size());
            }
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        String actual = HexFormat.of().formatHex(sha.digest());
        if (total != asset.size() || (asset.sha256() != null && !actual.equals(asset.sha256()))) {
            Files.deleteIfExists(part);
            throw new IOException(total != asset.size()
                    ? "Download unvollständig (" + total + " von " + asset.size() + " Bytes)"
                    : "Prüfsumme des Downloads stimmt nicht – Update abgebrochen");
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        return actual;
    }

    /** Lädt eine {@code .sig}-Datei (höchstens {@link ReleaseSignature#MAX_SIGNATURE_FILE} Bytes). */
    static byte[] downloadSignature(Download download, String url) throws IOException {
        try (InputStream in = download.open(url)) {
            byte[] data = in.readNBytes(ReleaseSignature.MAX_SIGNATURE_FILE + 1);
            if (data.length > ReleaseSignature.MAX_SIGNATURE_FILE) {
                throw new IOException("Signatur des Update-Pakets ist zu groß");
            }
            return data;
        }
    }

    /**
     * Entpackt das ZIP nach {@code staging} (vorher geleert) und prüft den Aufbau.
     *
     * @return der entpackte App-Ordner {@code staging/RiftLens}
     */
    static Path extract(Path zip, Path staging) throws IOException {
        deleteTree(staging);
        Files.createDirectories(staging);
        Path root = staging.toAbsolutePath().normalize();
        long extracted = 0;
        int entries = 0;
        try (ZipFile file = new ZipFile(zip.toFile())) {
            var all = file.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                if (++entries > MAX_ENTRIES) {
                    throw new IOException("Update-Paket enthält zu viele Dateien");
                }
                String name = entry.getName().replace('\\', '/');
                if (!name.startsWith(ROOT + "/")) {
                    throw new IOException("Unerwarteter Eintrag im Update-Paket: " + name);
                }
                Path out = root.resolve(name).normalize();
                if (!out.startsWith(root.resolve(ROOT))) {
                    throw new IOException("Ungültiger Pfad im Update-Paket: " + name);
                }
                if (entry.isDirectory() || name.endsWith("/")) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (InputStream in = file.getInputStream(entry)) {
                    extracted += copyLimited(in, out, MAX_EXTRACTED - extracted);
                }
            }
        }
        Path app = root.resolve(ROOT);
        if (!Files.isRegularFile(app.resolve(EXE)) || !Files.isRegularFile(app.resolve(JAR)) || !Files.isDirectory(app.resolve("runtime"))) {
            throw new IOException("Update-Paket unvollständig (RiftLens.exe, App-Jar oder Laufzeit fehlt)");
        }
        return app;
    }

    /** Namen der Einträge direkt in {@code dir}, sortiert. */
    static List<String> entries(Path dir) throws IOException {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                names.add(p.getFileName().toString());
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** Programmversion im App-Jar ({@code com/leaguetool/version.properties}), sonst leer. */
    static Optional<Version> jarVersion(Path jar) throws IOException {
        try (ZipFile file = new ZipFile(jar.toFile())) {
            ZipEntry entry = file.getEntry("com/leaguetool/version.properties");
            if (entry == null) {
                return Optional.empty();
            }
            Properties props = new Properties();
            try (InputStream in = file.getInputStream(entry)) {
                props.load(in);
            }
            return Version.parse(props.getProperty("version", ""));
        }
    }

    private static long copyLimited(InputStream in, Path out, long budget) throws IOException {
        long written = 0;
        try (OutputStream os = Files.newOutputStream(out)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                written += n;
                if (written > budget) {
                    throw new IOException("Update-Paket entpackt zu groß");
                }
                os.write(buffer, 0, n);
            }
        }
        return written;
    }

    /** Löscht einen Ordner samt Inhalt; fehlt er, passiert nichts. */
    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
