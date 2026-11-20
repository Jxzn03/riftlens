package com.leaguetool.update;

import com.leaguetool.config.AppSettings;
import com.leaguetool.data.http.HttpResult;
import com.leaguetool.testutil.FakeHttpFetcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateServiceTest {

    private static final KeyPair RELEASE_KEY = generate();

    @TempDir
    Path tmp;

    private final FakeHttpFetcher http = new FakeHttpFetcher();
    private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
    private final List<UpdateInstaller.Plan> launches = new CopyOnWriteArrayList<>();
    private final AtomicInteger restarts = new AtomicInteger();
    private final List<UpdateService> services = new ArrayList<>();
    private byte[] packageBytes;
    private byte[] signatureBytes;
    private String releaseJson;

    private static KeyPair generate() {
        try {
            return ReleaseSignature.generate();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void close() {
        services.forEach(UpdateService::close);
    }

    private AppSettings settings() throws IOException {
        return AppSettings.load(tmp.resolve("home"));
    }

    /** Programmordner wie nach dem Entpacken eines Releases. */
    private Path installDir() throws IOException {
        Path dir = tmp.resolve("Programme").resolve("RiftLens");
        Files.createDirectories(dir.resolve("app"));
        Files.createDirectories(dir.resolve("runtime"));
        if (!Files.exists(dir.resolve("RiftLens.exe"))) {
            Files.write(dir.resolve("RiftLens.exe"), new byte[] {'M', 'Z'});
        }
        return dir;
    }

    private UpdateService service(AppSettings settings, Optional<Path> installDir, String current) {
        return service(settings, installDir, current, RELEASE_KEY.getPublic());
    }

    @SuppressWarnings("unchecked")
    private UpdateService service(AppSettings settings, Optional<Path> installDir, String current, PublicKey key) {
        UpdateService s = new UpdateService(settings, http, this::download, launches::add,
                installDir, dir -> true, key, current, (name, data) -> events.add((Map<String, Object>) data));
        s.onRestart(restarts::incrementAndGet);
        services.add(s);
        return s;
    }

    private InputStream download(String url) throws IOException {
        byte[] data = url.endsWith(".sig") ? signatureBytes : packageBytes;
        if (data == null) {
            throw new IOException("nicht gefunden: " + url);
        }
        return new ByteArrayInputStream(data);
    }

    /** Hinterlegt Release {@code version} auf GitHub; das ZIP enthält ein Jar mit {@code jarVersion}, signiert mit {@code signer}. */
    private void publishRelease(String version, String jarVersion, KeyPair signer) throws Exception {
        packageBytes = UpdatePackageTest.releaseZip(jarVersion);
        String sha = UpdatePackageTest.sha256(packageBytes);
        signatureBytes = signer == null ? null : ReleaseSignature.sign(signer.getPrivate(), GitHubRelease.assetName(Version.parse(version).orElseThrow()), sha);
        String url = VersionAndReleaseTest.officialUrl(version);
        String json = VersionAndReleaseTest.releaseJson(version, url, "sha256:" + sha, signer == null ? null : url + ".sig")
                .replace("\"size\": 1234", "\"size\": " + packageBytes.length);
        releaseJson = json;
        http.respond(GitHubRelease.LATEST_API, json);
    }

    private void publishRelease(String version, String jarVersion) throws Exception {
        publishRelease(version, jarVersion, RELEASE_KEY);
    }

    private static Map<String, Object> await(UpdateService s, Predicate<Map<String, Object>> done) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Map<String, Object> state = s.state();
            if (done.test(state)) {
                return state;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Zeitüberschreitung, letzter Stand: " + s.state());
    }

    private static Predicate<Map<String, Object>> idleAfterCheck() {
        return st -> "idle".equals(st.get("phase")) && (st.get("checkedAt") != null || st.get("error") != null);
    }

    private static Predicate<Map<String, Object>> installFailed() {
        return st -> "idle".equals(st.get("phase")) && "install".equals(st.get("errorKind"));
    }

    private Map<String, Object> checked(UpdateService s) throws InterruptedException {
        s.check();
        return await(s, idleAfterCheck());
    }

    @Test
    void findsNewerRelease() throws Exception {
        publishRelease("2.1.0", "2.1.0");
        UpdateService s = service(settings(), Optional.of(installDir()), "2.0.1");

        assertEquals("checking", s.check().get("phase"));
        Map<String, Object> state = await(s, idleAfterCheck());

        assertEquals(true, state.get("available"));
        assertEquals(true, state.get("canInstall"));
        assertEquals("2.1.0", ((Map<?, ?>) state.get("latest")).get("version"));
        assertEquals("2.0.1", state.get("current"));
        assertTrue(events.stream().anyMatch(e -> "checking".equals(e.get("phase"))), "Fenster sehen die laufende Suche");
    }

    @Test
    void prereleasesOnlyWithTestSwitch() throws Exception {
        publishRelease("2.1.0", "2.1.0");
        http.respond(GitHubRelease.LIST_API, "[" + releaseJson.replaceFirst("\\{", "{\"prerelease\": true, ") + "]");
        http.respond(GitHubRelease.LATEST_API, VersionAndReleaseTest.releaseJson("2.0.1", VersionAndReleaseTest.officialUrl("2.0.1"), null));
        AppSettings settings = settings();
        UpdateService s = service(settings, Optional.of(installDir()), "2.0.1");
        assertEquals(false, checked(s).get("available"), "ohne Schalter zählt nur das neueste normale Release");

        settings.setUpdatePrereleaseEnabled(true);
        UpdateService withSwitch = service(settings, Optional.of(installDir()), "2.0.1");
        Map<String, Object> state = checked(withSwitch);
        assertEquals(true, state.get("available"));
        assertEquals(true, state.get("canInstall"), "Pre-Release mit gültiger Signatur ist installierbar");
        assertEquals(GitHubRelease.LIST_API, http.requests().get(http.requests().size() - 1));
    }

    @Test
    void sameVersionIsNoUpdate() throws Exception {
        publishRelease("2.0.1", "2.0.1");
        Map<String, Object> state = checked(service(settings(), Optional.of(installDir()), "2.0.1"));
        assertEquals(false, state.get("available"));
        assertEquals(false, state.get("canInstall"));
        assertNull(state.get("error"));
    }

    @Test
    void withoutInstallationOnlyLinksToGitHub() throws Exception {
        publishRelease("2.1.0", "2.1.0");
        UpdateService s = service(settings(), Optional.empty(), "2.0.1");
        Map<String, Object> state = checked(s);
        assertEquals(true, state.get("available"));
        assertEquals(false, state.get("canInstall"));
        assertTrue(String.valueOf(state.get("installHint")).contains("installierten Version"));
        assertThrows(IllegalStateException.class, s::install);
    }

    @Test
    void unsignedReleaseIsNotInstalled() throws Exception {
        publishRelease("2.1.0", "2.1.0", null);
        UpdateService s = service(settings(), Optional.of(installDir()), "2.0.1");
        Map<String, Object> state = checked(s);
        assertEquals(true, state.get("available"));
        assertEquals(false, state.get("canInstall"));
        assertTrue(String.valueOf(state.get("installHint")).contains("nicht signiert"), String.valueOf(state.get("installHint")));
        assertThrows(IllegalStateException.class, s::install);
        assertTrue(launches.isEmpty());
    }

    @Test
    void withoutBuiltInKeyNothingIsInstalled() throws Exception {
        publishRelease("2.1.0", "2.1.0");
        Map<String, Object> state = checked(service(settings(), Optional.of(installDir()), "2.0.1", null));
        assertEquals(false, state.get("canInstall"));
        assertTrue(String.valueOf(state.get("installHint")).contains("Signaturschlüssel"));
    }

    @Test
    void foreignFilesInInstallFolderBlockInstall() throws Exception {
        publishRelease("2.1.0", "2.1.0");
        Path install = installDir();
        Files.writeString(install.resolve("Steuererklaerung.pdf"), "x");
        UpdateService s = service(settings(), Optional.of(install), "2.0.1");
        Map<String, Object> state = checked(s);
        assertEquals(false, state.get("canInstall"));
        assertTrue(String.valueOf(state.get("installHint")).contains("Steuererklaerung.pdf"), String.valueOf(state.get("installHint")));
        assertThrows(IllegalStateException.class, s::install);
    }

    @Test
    void startupCheckFollowsSettingAndOfflineMode() throws Exception {
        publishRelease("2.1.0", "2.1.0");
        AppSettings settings = settings();
        settings.setUpdateCheckEnabled(false);
        UpdateService s = service(settings, Optional.of(installDir()), "2.0.1");
        s.checkOnStartup();
        settings.setUpdateCheckEnabled(true);
        settings.setOffline(true);
        s.checkOnStartup();
        assertTrue(http.requests().isEmpty(), "abgeschaltet oder offline: kein Abruf");
        assertEquals("idle", s.state().get("phase"));

        settings.setOffline(false);
        s.checkOnStartup();
        await(s, idleAfterCheck());
        assertEquals(List.of(GitHubRelease.LATEST_API), http.requests());
    }

    @Test
    void manualCheckOfflineReportsError() throws Exception {
        AppSettings settings = settings();
        settings.setOffline(true);
        UpdateService s = service(settings, Optional.of(installDir()), "2.0.1");
        Map<String, Object> state = s.check();
        assertEquals("check", state.get("errorKind"));
        assertTrue(String.valueOf(state.get("error")).contains("Offline"));
    }

    @Test
    void rateLimitIsReported() throws Exception {
        http.respond(GitHubRelease.LATEST_API, new HttpResult(403, new byte[0], Map.of()));
        Map<String, Object> state = checked(service(settings(), Optional.of(installDir()), "2.0.1"));
        assertTrue(String.valueOf(state.get("error")).contains("Abfragelimit"), String.valueOf(state.get("error")));
        assertEquals(false, state.get("available"));
    }

    @Test
    void installDownloadsVerifiesAndStartsUpdater() throws Exception {
        publishRelease("2.1.0", "2.1.0");
        AppSettings settings = settings();
        Path install = installDir();
        UpdateService s = service(settings, Optional.of(install), "2.0.1");
        checked(s);

        assertEquals("downloading", s.install().get("phase"));
        await(s, st -> "restarting".equals(st.get("phase")) && restarts.get() == 1);

        assertEquals(1, launches.size());
        UpdateInstaller.Plan plan = launches.get(0);
        assertEquals(install, plan.target());
        assertTrue(Files.isRegularFile(plan.source().resolve("RiftLens.exe")), "entpackte neue Version liegt bereit");
        assertEquals(ProcessHandle.current().pid(), plan.waitPids().get(0));
        assertTrue(plan.allowed().containsAll(List.of("riftlens.exe", "app", "runtime")), String.valueOf(plan.allowed()));
        Path dir = settings.home().resolve(UpdateService.DIR);
        assertEquals(dir.resolve(UpdateService.PENDING), plan.pending(), "Updater erkennt den Start der neuen Version daran");
        assertFalse(Files.exists(dir.resolve("RiftLens-2.1.0-win64.zip")), "ZIP nach dem Entpacken gelöscht");
        Properties pending = new Properties();
        try (InputStream in = Files.newInputStream(dir.resolve(UpdateService.PENDING))) {
            pending.load(in);
        }
        assertEquals("2.1.0", pending.getProperty("to"));
        assertTrue(Files.readString(dir.resolve(UpdateService.LOG)).contains("Signatur geprüft"));
        assertTrue(events.stream().anyMatch(e -> "extracting".equals(e.get("phase"))));
        assertThrows(IllegalStateException.class, s::install, "kein zweiter Start während des Neustarts");
    }

    @Test
    void signatureFromOtherKeyStopsInstall() throws Exception {
        publishRelease("2.1.0", "2.1.0", generate());
        AppSettings settings = settings();
        UpdateService s = service(settings, Optional.of(installDir()), "2.0.1");
        checked(s);
        s.install();
        Map<String, Object> state = await(s, installFailed());

        assertTrue(String.valueOf(state.get("error")).contains("Signatur"), String.valueOf(state.get("error")));
        assertTrue(launches.isEmpty());
        assertEquals(0, restarts.get());
        Path dir = settings.home().resolve(UpdateService.DIR);
        assertFalse(Files.exists(dir.resolve("RiftLens-2.1.0-win64.zip")), "ungültiges Paket wird gelöscht");
        assertFalse(Files.exists(dir.resolve(UpdateService.STAGING)), "ungültiges Paket wird nicht entpackt");
    }

    @Test
    void tamperedPackageStopsInstall() throws Exception {
        publishRelease("2.1.0", "2.1.0");
        // Paket nach dem Signieren getauscht, GitHub-Digest passend zum neuen Paket (z. B. fremdes Konto-Upload)
        byte[] evil = UpdatePackageTest.releaseZip("2.1.0");
        evil[evil.length - 1] ^= 1;
        String url = VersionAndReleaseTest.officialUrl("2.1.0");
        http.respond(GitHubRelease.LATEST_API, VersionAndReleaseTest.releaseJson("2.1.0", url, "sha256:" + UpdatePackageTest.sha256(evil))
                .replace("\"size\": 1234", "\"size\": " + evil.length));
        packageBytes = evil;
        UpdateService s = service(settings(), Optional.of(installDir()), "2.0.1");
        checked(s);
        s.install();
        Map<String, Object> state = await(s, installFailed());
        assertTrue(String.valueOf(state.get("error")).contains("Signatur"), String.valueOf(state.get("error")));
        assertTrue(launches.isEmpty());
    }

    @Test
    void packageWithOtherVersionIsRejected() throws Exception {
        publishRelease("2.1.0", "2.0.9");
        AppSettings settings = settings();
        UpdateService s = service(settings, Optional.of(installDir()), "2.0.1");
        checked(s);
        s.install();
        Map<String, Object> state = await(s, installFailed());

        assertTrue(String.valueOf(state.get("error")).contains("2.0.9"), String.valueOf(state.get("error")));
        assertTrue(launches.isEmpty());
        assertEquals(0, restarts.get());
        assertFalse(Files.exists(settings.home().resolve(UpdateService.DIR).resolve(UpdateService.PENDING)));
        assertEquals(true, state.get("canInstall"), "erneuter Versuch bleibt möglich");
    }

    @Test
    void secondInstanceDoesNotInstallOrCleanUpConcurrently() throws Exception {
        publishRelease("2.1.0", "2.1.0");
        AppSettings settings = settings();
        Path dir = settings.home().resolve(UpdateService.DIR);
        Files.createDirectories(dir.resolve(UpdateService.STAGING).resolve("RiftLens"));
        try (FileChannel channel = FileChannel.open(dir.resolve(UpdateService.LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = channel.lock()) {
            UpdateService s = service(settings, Optional.of(installDir()), "2.0.1");
            assertTrue(Files.isDirectory(dir.resolve(UpdateService.STAGING)), "Start räumt nicht auf, während ein anderer installiert");
            checked(s);
            s.install();
            Map<String, Object> state = await(s, installFailed());
            assertTrue(String.valueOf(state.get("error")).contains("anderes RiftLens-Fenster"), String.valueOf(state.get("error")));
            assertTrue(launches.isEmpty());
        }
    }

    @Test
    void installWithoutUpdateIsRefused() throws Exception {
        UpdateService s = service(settings(), Optional.of(installDir()), "2.0.1");
        assertThrows(IllegalStateException.class, s::install);
    }

    @Test
    void reportsResultOfPreviousUpdateOnceAndCleansUp() throws Exception {
        AppSettings settings = settings();
        Path dir = settings.home().resolve(UpdateService.DIR);
        Files.createDirectories(dir.resolve(UpdateService.STAGING).resolve("RiftLens"));
        Files.writeString(dir.resolve("RiftLens-2.1.0-win64.zip.part"), "x");
        Files.writeString(dir.resolve(UpdateService.PENDING), "to=2.1.0\n");

        Map<?, ?> ok = (Map<?, ?>) service(settings, Optional.empty(), "2.1.0").state().get("lastResult");
        assertEquals(true, ok.get("ok"));
        assertTrue(String.valueOf(ok.get("text")).contains("2.1.0"));
        assertFalse(Files.exists(dir.resolve(UpdateService.PENDING)));
        assertFalse(Files.exists(dir.resolve(UpdateService.STAGING)));
        assertFalse(Files.exists(dir.resolve("RiftLens-2.1.0-win64.zip.part")));
        assertNull(service(settings, Optional.empty(), "2.1.0").state().get("lastResult"), "nur einmal melden");

        Files.writeString(dir.resolve(UpdateService.PENDING), "to=2.1.0\n");
        Map<?, ?> failed = (Map<?, ?>) service(settings, Optional.empty(), "2.0.1").state().get("lastResult");
        assertEquals(false, failed.get("ok"));
        assertTrue(String.valueOf(failed.get("text")).contains(UpdateService.LOG));
    }
}
