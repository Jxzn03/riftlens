package com.leaguetool.update;

import com.leaguetool.config.AppSettings;
import com.leaguetool.data.http.HttpFetcher;
import com.leaguetool.data.http.HttpResult;
import com.leaguetool.data.http.JavaHttpFetcher;
import com.leaguetool.util.AppVersion;
import com.leaguetool.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.PublicKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Automatisches Update über GitHub-Releases: beim Start (abschaltbar) oder von Hand prüfen, ob es ein neueres Release
 * gibt; auf Wunsch das ZIP laden, Ed25519-Signatur ({@link ReleaseSignature}), Prüfsumme und Inhalt prüfen, entpacken
 * und über den Updater ({@link UpdateInstaller}) den App-Ordner austauschen und RiftLens neu starten. Jede
 * Zustandsänderung geht als Ereignis {@code update} an die Fenster. Arbeitsordner: {@code <home>/update}
 * (Log {@code update.log}).
 */
public final class UpdateService implements AutoCloseable {

    /** Laufende Tätigkeit; ohne Tätigkeit {@code IDLE}. */
    public enum Phase { IDLE, CHECKING, DOWNLOADING, EXTRACTING, RESTARTING }

    static final String DIR = "update";
    static final String LOG = "update.log";
    static final String PENDING = "pending.properties";
    static final String STAGING = "staging";
    /** Sperre gegen zwei RiftLens-Prozesse, die gleichzeitig installieren oder aufräumen. */
    static final String LOCK = "update.lock";
    private static final long MAX_LOG = 256 * 1024;
    /** Zeit für das Fenster, die Meldung „startet neu“ zu zeigen, bevor die App endet. */
    private static final long RESTART_DELAY_MS = 1500;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    /** Längste Pause ohne Daten beim Download; danach Abbruch statt endlosem Warten. */
    private static final int READ_TIMEOUT_MS = 30_000;

    private final AppSettings settings;
    private final HttpFetcher http;
    private final UpdatePackage.Download download;
    private final UpdateInstaller.Launcher launcher;
    private final Optional<Path> installDir;
    private final Predicate<Path> replaceable;
    private final PublicKey releaseKey;
    private final String currentText;
    private final Optional<Version> current;
    private final BiConsumer<String, Object> events;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("lt-update").factory());

    private volatile Runnable restart = () -> { };
    private Phase phase = Phase.IDLE;
    private GitHubRelease latest;
    private String installHint;
    private Instant checkedAt;
    private String error;
    private String errorKind;
    private double progress;
    private Map<String, Object> lastResult;
    private long seq;

    /**
     * @param releaseKey öffentlicher Release-Schlüssel; {@code null} = keine automatische Installation
     */
    UpdateService(AppSettings settings, HttpFetcher http, UpdatePackage.Download download, UpdateInstaller.Launcher launcher,
            Optional<Path> installDir, Predicate<Path> replaceable, PublicKey releaseKey, String currentVersion,
            BiConsumer<String, Object> events) {
        this.settings = settings;
        this.http = http;
        this.download = download;
        this.launcher = launcher;
        this.installDir = installDir;
        this.replaceable = replaceable;
        this.releaseKey = releaseKey;
        this.currentText = currentVersion == null ? "" : currentVersion;
        this.current = Version.parse(currentText);
        this.events = events;
        finishPreviousUpdate();
    }

    /**
     * Dienst für die laufende App.
     *
     * @param http   Abruf der Release-Info (beachtet den Offline-Modus)
     * @param events Ereignisse an die Fenster
     */
    public static UpdateService create(AppSettings settings, HttpFetcher http, BiConsumer<String, Object> events) {
        PublicKey key;
        try {
            key = ReleaseSignature.publicKey();
        } catch (IOException e) {
            key = null;
        }
        return new UpdateService(settings, http, UpdateService::openDownload, UpdateInstaller::launchPowerShell,
                UpdateInstaller.detectInstallDir(), UpdateInstaller::replaceable, key, AppVersion.get(), events);
    }

    /** Beendet die App nach dem Start des Updaters (Fenster schließen, Prozess beenden). */
    public void onRestart(Runnable action) {
        restart = action;
    }

    /** Prüfung beim Programmstart, sofern in den Einstellungen erlaubt und nicht offline. */
    public void checkOnStartup() {
        if (settings.updateCheckEnabled() && !settings.offline()) {
            check();
        }
    }

    /** Startet eine Prüfung im Hintergrund (läuft schon etwas, bleibt es dabei) und liefert den Zustand. */
    public synchronized Map<String, Object> check() {
        if (phase != Phase.IDLE) {
            return stateLocked();
        }
        if (settings.offline()) {
            fail("check", "Offline-Modus aktiv, keine Update-Prüfung");
            return stateLocked();
        }
        phase = Phase.CHECKING;
        error = null;
        errorKind = null;
        publishLocked();
        worker.execute(this::runCheck);
        return stateLocked();
    }

    /**
     * Startet Download und Installation des gefundenen Releases. Danach beendet sich die App, der Updater tauscht den
     * Ordner und startet die neue Version.
     *
     * @throws IllegalStateException wenn gerade etwas läuft, kein Update vorliegt oder es sich nicht installieren lässt
     */
    public synchronized Map<String, Object> install() {
        if (phase != Phase.IDLE) {
            throw new IllegalStateException("Es läuft bereits eine Update-Aktion");
        }
        if (!available()) {
            throw new IllegalStateException("Kein neueres Release gefunden");
        }
        if (settings.offline()) {
            throw new IllegalStateException("Offline-Modus aktiv, kein Download möglich");
        }
        installHint = hint(latest);
        if (installHint != null) {
            publishLocked();
            throw new IllegalStateException(installHint);
        }
        GitHubRelease release = latest;
        phase = Phase.DOWNLOADING;
        progress = 0;
        error = null;
        errorKind = null;
        publishLocked();
        worker.execute(() -> runInstall(release));
        return stateLocked();
    }

    /** Nach dem Speichern der Einstellungen: Fenster bekommen den neuen Stand des Schalters. */
    public synchronized void settingsChanged() {
        publishLocked();
    }

    /** Aktueller Zustand für die Oberfläche. */
    public synchronized Map<String, Object> state() {
        return stateLocked();
    }

    // ------------------------------------------------------------------ Prüfen

    private void runCheck() {
        try {
            GitHubRelease release = fetchLatest();
            String hint = current.isPresent() && release.version().isNewerThan(current.get()) ? hint(release) : null;
            synchronized (this) {
                latest = release;
                installHint = hint;
                checkedAt = Instant.now();
                phase = Phase.IDLE;
                publishLocked();
            }
        } catch (IOException | RuntimeException e) {
            synchronized (this) {
                phase = Phase.IDLE;
                fail("check", "Update-Prüfung fehlgeschlagen: " + message(e));
            }
        }
    }

    /** Neuestes Release; mit Testschalter {@code update.prerelease} auch Pre-Releases. Prüfungen bleiben gleich. */
    private GitHubRelease fetchLatest() throws IOException {
        boolean prerelease = settings.updatePrereleaseEnabled();
        HttpResult r = http.get(prerelease ? GitHubRelease.LIST_API : GitHubRelease.LATEST_API,
                Map.of("Accept", "application/vnd.github+json", "X-GitHub-Api-Version", "2022-11-28"));
        if (r.status() == 404) {
            throw new IOException("Auf GitHub ist noch kein Release veröffentlicht");
        }
        if (r.status() == 403 || r.status() == 429) {
            throw new IOException("GitHub-Abfragelimit erreicht, bitte später erneut versuchen");
        }
        if (!r.isSuccess()) {
            throw new IOException("GitHub antwortet mit HTTP " + r.status());
        }
        var json = Json.MAPPER.readTree(r.body());
        return prerelease ? GitHubRelease.parseNewest(json) : GitHubRelease.parse(json);
    }

    private boolean available() {
        return latest != null && current.isPresent() && latest.version().isNewerThan(current.get());
    }

    /** Grund, warum sich das Release nicht automatisch installieren lässt, oder {@code null}. */
    private String hint(GitHubRelease release) {
        if (installDir.isEmpty()) {
            return "Automatische Updates gehen nur in der installierten Version (RiftLens.exe). Neue Version bitte von GitHub laden.";
        }
        if (releaseKey == null) {
            return "Dieser RiftLens-Version fehlt der Signaturschlüssel für automatische Updates. Neue Version bitte von GitHub laden.";
        }
        if (release.asset() == null) {
            return "Das Release enthält kein passendes Windows-Paket. Bitte von GitHub laden.";
        }
        if (release.asset().signatureUrl() == null) {
            return "Das Release ist nicht signiert, RiftLens installiert es deshalb nicht automatisch. Bitte von GitHub laden.";
        }
        String foreign = foreignHint(installDir.get(), List.of());
        if (foreign != null) {
            return foreign;
        }
        if (!replaceable.test(installDir.get())) {
            return "Keine Schreibrechte im Ordner " + installDir.get().getParent() + ". Bitte von Hand aktualisieren.";
        }
        return null;
    }

    /**
     * Der Updater ersetzt den ganzen Programmordner. Liegen dort fremde Dateien (z. B. weil RiftLens direkt auf den
     * Desktop entpackt wurde), gingen sie verloren: dann nicht automatisch installieren.
     */
    private static String foreignHint(Path dir, List<String> packageEntries) {
        List<String> foreign;
        try {
            foreign = UpdateInstaller.foreignEntries(dir, packageEntries);
        } catch (IOException e) {
            return "Programmordner " + dir + " ist nicht lesbar. Bitte von Hand aktualisieren.";
        }
        if (foreign.isEmpty()) {
            return null;
        }
        String names = String.join(", ", foreign.subList(0, Math.min(3, foreign.size()))) + (foreign.size() > 3 ? ", …" : "");
        return "Im Programmordner " + dir + " liegen fremde Dateien (" + names + "). Das Update ersetzt den ganzen Ordner, "
                + "darum installiert RiftLens nur in einen eigenen Ordner. Dateien verschieben oder von Hand aktualisieren.";
    }

    // ------------------------------------------------------------------ Installieren

    private void runInstall(GitHubRelease release) {
        Path dir = updateDir();
        Path log = dir.resolve(LOG);
        Path pending = dir.resolve(PENDING);
        boolean pendingWritten = false;
        try {
            Files.createDirectories(dir);
            try (FileChannel channel = FileChannel.open(dir.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock lock = tryLock(channel)) {
                if (lock == null) {
                    throw new IOException("Ein anderes RiftLens-Fenster installiert gerade ein Update");
                }
                if (Files.isRegularFile(log) && Files.size(log) > MAX_LOG) {
                    Files.delete(log);
                }
                GitHubRelease.Asset asset = release.asset();
                log(log, "Update " + currentText + " -> " + release.version() + ": Download " + asset.url());
                byte[] signature = UpdatePackage.downloadSignature(download, asset.signatureUrl());
                Path zip = dir.resolve(asset.name());
                String sha = UpdatePackage.download(download, asset, zip, this::onProgress);
                setPhase(Phase.EXTRACTING);
                try {
                    ReleaseSignature.verify(releaseKey, asset.name(), sha, signature);
                } catch (IOException e) {
                    Files.deleteIfExists(zip);
                    throw e;
                }
                log(log, "Signatur geprüft (SHA-256 " + sha + ")");
                Path app = UpdatePackage.extract(zip, dir.resolve(STAGING));
                Files.deleteIfExists(zip);
                Version packaged = UpdatePackage.jarVersion(app.resolve(UpdatePackage.JAR))
                        .orElseThrow(() -> new IOException("Version im Update-Paket nicht lesbar"));
                if (!packaged.equals(release.version())) {
                    throw new IOException("Update-Paket enthält Version " + packaged + " statt " + release.version());
                }
                Path target = installDir.orElseThrow();
                List<String> packageEntries = UpdatePackage.entries(app);
                String foreign = foreignHint(target, packageEntries);
                if (foreign != null) {
                    throw new IOException(foreign);
                }
                writePending(pending, release.version());
                pendingWritten = true;
                List<Long> pids = new ArrayList<>();
                ProcessHandle self = ProcessHandle.current();
                pids.add(self.pid());
                self.parent().ifPresent(p -> pids.add(p.pid())); // jpackage-Starter, der den Java-Prozess startet
                log(log, "Paket geprüft, starte Updater für " + target);
                launcher.launch(new UpdateInstaller.Plan(app, target, log, pending, pids,
                        List.copyOf(UpdateInstaller.allowedEntries(packageEntries)), inheritedHome()));
                setPhase(Phase.RESTARTING);
                Thread.sleep(RESTART_DELAY_MS);
                restart.run();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            try {
                if (pendingWritten) {
                    Files.deleteIfExists(pending);
                }
                log(log, "FEHLER: " + message(e));
            } catch (IOException ignored) {
                // Meldung kommt trotzdem in der Oberfläche an
            }
            synchronized (this) {
                phase = Phase.IDLE;
                fail("install", "Update fehlgeschlagen: " + message(e));
            }
        }
    }

    /** Sperre oder {@code null}, wenn ein anderer Prozess (oder ein anderer Dienst in dieser JVM) sie hält. */
    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException e) {
            return null;
        }
    }

    /** {@code LEAGUETOOL_HOME} der laufenden App: Der Updater startet die neue Version außerhalb dieser Umgebung. */
    private static String inheritedHome() {
        String env = System.getenv("LEAGUETOOL_HOME");
        return env == null || env.isBlank() ? null : env;
    }

    private synchronized void onProgress(double fraction) {
        double clamped = Math.max(0, Math.min(1, fraction));
        if (clamped - progress >= 0.01 || (clamped >= 1 && progress < 1)) {
            progress = clamped;
            publishLocked();
        }
    }

    private synchronized void setPhase(Phase next) {
        phase = next;
        publishLocked();
    }

    private static void writePending(Path pending, Version target) throws IOException {
        Properties props = new Properties();
        props.setProperty("to", target.toString());
        props.setProperty("at", Instant.now().toString());
        try (OutputStream out = Files.newOutputStream(pending)) {
            props.store(out, "RiftLens-Update");
        }
    }

    /**
     * Nach einem Neustart: Ergebnis des letzten Updates merken (Erfolg, wenn diese App die Zielversion ist) und
     * Reste (entpackter Ordner, Downloads) entfernen. Das Löschen von {@code pending.properties} ist zugleich das
     * Zeichen an den Updater, dass die neue Version startet. Installiert gerade ein anderer RiftLens-Prozess, bleibt
     * alles liegen.
     */
    private void finishPreviousUpdate() {
        Path dir = updateDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        Path pending = dir.resolve(PENDING);
        try (FileChannel channel = FileChannel.open(dir.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = tryLock(channel)) {
            if (lock == null) {
                return;
            }
            if (Files.isRegularFile(pending)) {
                Properties props = new Properties();
                try (InputStream in = Files.newInputStream(pending)) {
                    props.load(in);
                }
                Files.deleteIfExists(pending);
                String target = props.getProperty("to", "");
                boolean ok = current.isPresent() && Version.parse(target).equals(current);
                lastResult = new LinkedHashMap<>();
                lastResult.put("ok", ok);
                lastResult.put("text", ok ? "RiftLens wurde auf " + target + " aktualisiert."
                        : "Update auf " + target + " hat nicht geklappt, es läuft weiter " + (currentText.isEmpty() ? "die alte Version" : currentText)
                                + ". Details: " + dir.resolve(LOG));
            }
            UpdatePackage.deleteTree(dir.resolve(STAGING));
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.{zip,part}")) {
                for (Path f : files) {
                    Files.deleteIfExists(f);
                }
            }
        } catch (IOException | RuntimeException e) {
            // beim nächsten Start erneut
        }
    }

    // ------------------------------------------------------------------ Zustand

    private void fail(String kind, String text) {
        error = text;
        errorKind = kind;
        publishLocked();
    }

    private void publishLocked() {
        seq++;
        events.accept("update", stateLocked());
    }

    private Map<String, Object> stateLocked() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", seq);
        m.put("current", currentText);
        m.put("phase", phase.name().toLowerCase(java.util.Locale.ROOT));
        m.put("autoCheck", settings.updateCheckEnabled());
        m.put("checkedAt", checkedAt == null ? null : checkedAt.toString());
        Map<String, Object> release = null;
        if (latest != null) {
            release = new LinkedHashMap<>();
            release.put("version", latest.version().toString());
            release.put("name", latest.name());
            release.put("url", latest.pageUrl());
            release.put("notes", latest.notes());
            release.put("size", latest.asset() == null ? null : latest.asset().size());
        }
        m.put("latest", release);
        boolean available = available();
        m.put("available", available);
        m.put("canInstall", available && installHint == null);
        m.put("installHint", available ? installHint : null);
        m.put("progress", progress);
        m.put("error", error);
        m.put("errorKind", errorKind);
        m.put("lastResult", lastResult);
        m.put("releasesUrl", GitHubRelease.RELEASES_PAGE);
        return m;
    }

    private Path updateDir() {
        return settings.home().resolve(DIR);
    }

    private static void log(Path log, String text) throws IOException {
        Files.createDirectories(log.getParent());
        String line = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " " + text + System.lineSeparator();
        Files.writeString(log, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String message(Throwable e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * Download über HTTPS. GitHub leitet auf seinen Speicher um; {@link HttpURLConnection} folgt nur Weiterleitungen
     * im selben Protokoll (also nie auf HTTP) und bricht anders als {@code HttpClient} ab, wenn
     * {@value #READ_TIMEOUT_MS} ms lang keine Daten kommen.
     */
    private static InputStream openDownload(String url) throws IOException {
        if (!url.startsWith(GitHubRelease.DOWNLOAD_PREFIX)) {
            throw new IOException("Download nur von " + GitHubRelease.RELEASES_PAGE);
        }
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", JavaHttpFetcher.USER_AGENT);
        connection.setRequestProperty("Accept", "application/octet-stream");
        int status = connection.getResponseCode();
        if (status != 200) {
            connection.disconnect();
            throw new IOException("Download fehlgeschlagen: HTTP " + status);
        }
        if (!"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
            connection.disconnect();
            throw new IOException("Download nur über HTTPS");
        }
        return connection.getInputStream();
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}
