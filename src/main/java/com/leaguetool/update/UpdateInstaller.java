package com.leaguetool.update;

import com.leaguetool.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * Installationsordner erkennen und den Updater ({@code install-update.ps1}) starten, der nach dem Beenden der App
 * den Ordner austauscht und RiftLens neu startet.
 */
final class UpdateInstaller {

    static final String SCRIPT = "install-update.ps1";
    static final String PARAMS = "update-params.json";
    /**
     * Was in einem RiftLens-Programmordner liegen darf: jpackage-App-Image, Lizenzen aus {@code build_release.ps1}
     * und Dateien, die der Explorer selbst anlegt. Alles andere gilt als fremd: Der Updater tauscht den ganzen Ordner
     * aus und würde es löschen.
     */
    static final Set<String> KNOWN_ENTRIES = Set.of("riftlens.exe", "riftlens.ico", "app", "runtime", "license.txt",
            "third-party-notices.txt", "licenses", "desktop.ini", "thumbs.db");

    private UpdateInstaller() {
    }

    /**
     * Auftrag an den Updater.
     *
     * @param source         entpackte neue Version
     * @param target         Programmordner, der ersetzt wird
     * @param log            Protokoll ({@code <home>/update/update.log})
     * @param pending        Datei, die die neue Version beim Start entfernt (Zeichen für einen geglückten Start)
     * @param waitPids       Prozesse, deren Ende der Updater abwartet (Java-Prozess, Starter)
     * @param allowed        Einträge, die im Programmordner liegen dürfen (sonst bricht der Updater ab)
     * @param leaguetoolHome Wert von {@code LEAGUETOOL_HOME} für die neue Version oder {@code null}
     */
    record Plan(Path source, Path target, Path log, Path pending, List<Long> waitPids, List<String> allowed,
            String leaguetoolHome) {
    }

    /** Startet den Updater; kehrt zurück, sobald er unabhängig von der App läuft. */
    @FunctionalInterface
    interface Launcher {
        void launch(Plan plan) throws IOException;
    }

    /**
     * Ordner des jpackage-App-Images, aus dem diese App läuft ({@code <ordner>/app/league-tool-modern.jar} neben
     * {@code RiftLens.exe} und {@code runtime/}); leer beim Start aus dem Projekt oder einem losen Jar.
     */
    static Optional<Path> detectInstallDir() {
        try {
            var source = UpdateInstaller.class.getProtectionDomain().getCodeSource();
            return source == null ? Optional.empty() : installDirOf(Path.of(source.getLocation().toURI()));
        } catch (URISyntaxException | SecurityException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    static Optional<Path> installDirOf(Path jar) {
        Path app = jar.toAbsolutePath().normalize().getParent();
        if (app == null || app.getFileName() == null || !app.getFileName().toString().equalsIgnoreCase("app")
                || !Files.isRegularFile(jar)) {
            return Optional.empty();
        }
        Path dir = app.getParent();
        return dir != null && dir.getParent() != null && Files.isRegularFile(dir.resolve(UpdatePackage.EXE))
                && Files.isDirectory(dir.resolve("runtime")) ? Optional.of(dir) : Optional.empty();
    }

    /** Ob der Updater den Ordner austauschen darf: Im übergeordneten Ordner muss sich eine Datei anlegen lassen. */
    static boolean replaceable(Path installDir) {
        Path parent = installDir.getParent();
        if (parent == null) {
            return false;
        }
        try {
            Path probe = Files.createTempFile(parent, ".riftlens-update-", ".tmp");
            Files.delete(probe);
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    /** Einträge im Programmordner, die weder bekannt noch in {@code extra} (Inhalt des neuen Pakets) sind. */
    static List<String> foreignEntries(Path installDir, Collection<String> extra) throws IOException {
        Set<String> allowed = allowedEntries(extra);
        return UpdatePackage.entries(installDir).stream()
                .filter(name -> !allowed.contains(name.toLowerCase(Locale.ROOT)))
                .toList();
    }

    /** Bekannte Einträge plus {@code extra}, klein geschrieben (Windows unterscheidet nicht). */
    static Set<String> allowedEntries(Collection<String> extra) {
        Set<String> allowed = new TreeSet<>(KNOWN_ENTRIES);
        extra.forEach(name -> allowed.add(name.toLowerCase(Locale.ROOT)));
        return allowed;
    }

    /** Windows PowerShell über den festen Systempfad, nicht über PATH. */
    private static Path powershell() {
        String root = System.getenv("SystemRoot");
        return Path.of(root == null || root.isBlank() ? "C:\\Windows" : root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
    }

    /**
     * Echter Start unter Windows: Skript und Auftrag ({@value #PARAMS}) neben das Log schreiben und das Skript mit
     * {@code -Detach} ausführen. Pfade gehen nur über die JSON-Datei, nicht über eine zusammengesetzte Kommandozeile.
     */
    static void launchPowerShell(Plan plan) throws IOException {
        Path dir = plan.log().getParent();
        Path script = dir.resolve(SCRIPT);
        Path params = dir.resolve(PARAMS);
        try (InputStream in = UpdateInstaller.class.getResourceAsStream(SCRIPT)) {
            if (in == null) {
                throw new IOException("Updater-Skript fehlt im Programm");
            }
            Files.write(script, in.readAllBytes());
        }
        Files.write(params, Json.MAPPER.writeValueAsBytes(paramsJson(plan)));
        List<String> command = List.of(powershell().toString(), "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.toString(),
                "-ParamsFile", params.toString(), "-Detach");
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Updater startet nicht (Zeitüberschreitung)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Unterbrochen", e);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            throw new IOException("Updater startet nicht (Exit " + process.exitValue() + (output.isEmpty() ? "" : ": " + output) + ")");
        }
    }

    /** Inhalt von {@value #PARAMS}, wie ihn {@code install-update.ps1} liest. */
    static Map<String, Object> paramsJson(Plan plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", plan.source().toAbsolutePath().toString());
        m.put("target", plan.target().toAbsolutePath().toString());
        m.put("log", plan.log().toAbsolutePath().toString());
        m.put("pending", plan.pending().toAbsolutePath().toString());
        m.put("waitPids", plan.waitPids());
        m.put("allowed", plan.allowed());
        m.put("leaguetoolHome", plan.leaguetoolHome());
        return m;
    }
}
