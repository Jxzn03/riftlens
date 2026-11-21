package com.leaguetool.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdatePackageTest {

    @TempDir
    Path tmp;

    static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** Jar mit {@code version.properties}, wie es Maven baut. */
    static byte[] appJar(String version) throws IOException {
        return zip(Map.of("com/leaguetool/version.properties", ("version=" + version + "\n").getBytes(StandardCharsets.ISO_8859_1)));
    }

    /** Release-ZIP im Aufbau von {@code build_release.ps1}. */
    static byte[] releaseZip(String version) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("RiftLens/RiftLens.exe", new byte[] {'M', 'Z'});
        entries.put("RiftLens/app/league-tool-modern.jar", appJar(version));
        entries.put("RiftLens/runtime/release", "JAVA_VERSION=\"23\"".getBytes(StandardCharsets.UTF_8));
        return zip(entries);
    }

    static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    static GitHubRelease.Asset asset(byte[] data, String sha) {
        String url = GitHubRelease.DOWNLOAD_PREFIX + "v2.1.0/RiftLens-2.1.0-win64.zip";
        return new GitHubRelease.Asset("RiftLens-2.1.0-win64.zip", url, data.length, sha, url + ".sig");
    }

    @Test
    void downloadChecksSizeAndChecksum() throws Exception {
        byte[] data = releaseZip("2.1.0");
        Path target = tmp.resolve("update").resolve("RiftLens-2.1.0-win64.zip");
        List<Double> progress = new ArrayList<>();

        String sha = UpdatePackage.download(url -> new ByteArrayInputStream(data), asset(data, sha256(data)), target, progress::add);

        assertEquals(sha256(data), sha, "berechnete Prüfsumme geht an die Signaturprüfung");
        assertTrue(Files.isRegularFile(target));
        assertEquals(data.length, Files.size(target));
        assertEquals(1.0, progress.get(progress.size() - 1), 1e-9);
        assertFalse(Files.exists(target.resolveSibling(target.getFileName() + ".part")));
    }

    @Test
    void downloadWithoutGitHubDigestReturnsOwnChecksum() throws Exception {
        byte[] data = releaseZip("2.1.0");
        String sha = UpdatePackage.download(url -> new ByteArrayInputStream(data), asset(data, null), tmp.resolve("r.zip"), p -> { });
        assertEquals(sha256(data), sha);
    }

    @Test
    void signatureDownloadIsLimited() throws Exception {
        byte[] sig = "c2lnbmF0dXI=\n".getBytes(StandardCharsets.US_ASCII);
        assertEquals(new String(sig, StandardCharsets.US_ASCII),
                new String(UpdatePackage.downloadSignature(url -> new ByteArrayInputStream(sig), "u"), StandardCharsets.US_ASCII));
        byte[] huge = new byte[ReleaseSignature.MAX_SIGNATURE_FILE + 1];
        assertThrows(IOException.class, () -> UpdatePackage.downloadSignature(url -> new ByteArrayInputStream(huge), "u"));
    }

    @Test
    void wrongChecksumDeletesDownload() throws Exception {
        byte[] data = releaseZip("2.1.0");
        Path target = tmp.resolve("RiftLens-2.1.0-win64.zip");
        IOException e = assertThrows(IOException.class, () -> UpdatePackage.download(url -> new ByteArrayInputStream(data),
                asset(data, "0".repeat(64)), target, p -> { }));
        assertTrue(e.getMessage().contains("Prüfsumme"), e.getMessage());
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(target.resolveSibling(target.getFileName() + ".part")));
    }

    @Test
    void downloadLargerThanAnnouncedIsAborted() throws Exception {
        byte[] data = releaseZip("2.1.0");
        GitHubRelease.Asset small = new GitHubRelease.Asset("x.zip", "u", data.length - 1, sha256(data), "u.sig");
        assertThrows(IOException.class, () -> UpdatePackage.download(url -> new ByteArrayInputStream(data), small,
                tmp.resolve("x.zip"), p -> { }));
    }

    @Test
    void extractsReleaseAndReadsJarVersion() throws Exception {
        Path zip = Files.write(tmp.resolve("r.zip"), releaseZip("2.1.0"));
        Path staging = tmp.resolve("staging");
        Files.createDirectories(staging.resolve("alt"));

        Path app = UpdatePackage.extract(zip, staging);

        assertEquals(staging.resolve("RiftLens").toAbsolutePath().normalize(), app);
        assertTrue(Files.isRegularFile(app.resolve("RiftLens.exe")));
        assertFalse(Files.exists(staging.resolve("alt")), "Reste eines früheren Versuchs werden vorher entfernt");
        assertEquals(new Version(2, 1, 0), UpdatePackage.jarVersion(app.resolve(UpdatePackage.JAR)).orElseThrow());
    }

    @Test
    void rejectsPathsOutsideTheAppFolder() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("RiftLens/RiftLens.exe", new byte[] {1});
        entries.put("RiftLens/../../boese.txt", new byte[] {1});
        Path zip = Files.write(tmp.resolve("r.zip"), zip(entries));
        assertThrows(IOException.class, () -> UpdatePackage.extract(zip, tmp.resolve("staging")));
        assertFalse(Files.exists(tmp.resolve("boese.txt")));
    }

    @Test
    void rejectsUnexpectedLayout() throws Exception {
        Path other = Files.write(tmp.resolve("a.zip"), zip(Map.of("Anderes/RiftLens.exe", new byte[] {1})));
        assertThrows(IOException.class, () -> UpdatePackage.extract(other, tmp.resolve("s1")));
        Path incomplete = Files.write(tmp.resolve("b.zip"), zip(Map.of("RiftLens/RiftLens.exe", new byte[] {1})));
        IOException e = assertThrows(IOException.class, () -> UpdatePackage.extract(incomplete, tmp.resolve("s2")));
        assertTrue(e.getMessage().contains("unvollständig"), e.getMessage());
    }

    @Test
    void detectsInstallFolderOnlyInAppImage() throws Exception {
        Path dir = tmp.resolve("RiftLens");
        Path jar = dir.resolve("app").resolve("league-tool-modern.jar");
        Files.createDirectories(jar.getParent());
        Files.write(jar, appJar("2.0.1"));
        assertTrue(UpdateInstaller.installDirOf(jar).isEmpty(), "ohne RiftLens.exe und Laufzeit kein App-Image");
        Files.write(dir.resolve("RiftLens.exe"), new byte[] {1});
        Files.createDirectories(dir.resolve("runtime"));
        assertEquals(dir.toAbsolutePath().normalize(), UpdateInstaller.installDirOf(jar).orElseThrow());
        Path loose = Files.write(tmp.resolve("league-tool-modern.jar"), appJar("2.0.1"));
        assertTrue(UpdateInstaller.installDirOf(loose).isEmpty(), "loses Jar (riftlens-build.cmd) ist keine Installation");
    }

    @Test
    void foreignFilesInTheInstallFolderAreFound() throws Exception {
        Path dir = tmp.resolve("RiftLens");
        Files.createDirectories(dir.resolve("app"));
        Files.createDirectories(dir.resolve("runtime"));
        Files.createDirectories(dir.resolve("licenses"));
        Files.write(dir.resolve("RiftLens.exe"), new byte[] {1});
        Files.write(dir.resolve("LICENSE.txt"), new byte[] {1});
        Files.write(dir.resolve("desktop.ini"), new byte[] {1});
        assertEquals(List.of(), UpdateInstaller.foreignEntries(dir, List.of()));

        // z. B. RiftLens direkt auf den Desktop entpackt: Austausch des Ordners würde diese Dateien löschen
        Files.write(dir.resolve("Notizen.docx"), new byte[] {1});
        Files.createDirectories(dir.resolve("Fotos"));
        assertEquals(List.of("Fotos", "Notizen.docx"), UpdateInstaller.foreignEntries(dir, List.of()));

        // Neue Einträge einer neuen Version sind erlaubt
        assertEquals(List.of("Notizen.docx"), UpdateInstaller.foreignEntries(dir, List.of("Fotos")));
        assertTrue(UpdateInstaller.allowedEntries(List.of("Neu.TXT")).contains("neu.txt"), "Windows unterscheidet keine Groß-/Kleinschreibung");
    }

    @Test
    void updaterParamsCarryEverythingTheScriptReads() throws Exception {
        UpdateInstaller.Plan plan = new UpdateInstaller.Plan(tmp.resolve("neu"), tmp.resolve("RiftLens"), tmp.resolve("update.log"),
                tmp.resolve("pending.properties"), List.of(1L, 2L), List.of("app", "riftlens.exe"), null);
        Map<String, Object> json = UpdateInstaller.paramsJson(plan);
        try (var in = UpdateInstaller.class.getResourceAsStream(UpdateInstaller.SCRIPT)) {
            String script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String key : json.keySet()) {
                assertTrue(script.contains("$p." + key), "Skript liest " + key);
            }
        }
        assertEquals(List.of(1L, 2L), json.get("waitPids"));
    }

    @Test
    void installerScriptIsBundled() throws Exception {
        try (var in = UpdateInstaller.class.getResourceAsStream(UpdateInstaller.SCRIPT)) {
            String script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(script.contains("Win32_Process"), "Updater muss sich vom Job-Objekt des Starters lösen");
            assertTrue(script.contains("Test-AppFolder $Target"), "Updater prüft den Zielordner selbst vor dem Austausch");
            assertTrue(script.contains("Wait-Started"), "Updater wartet, ob die neue Version startet, sonst Rückfall");
            assertFalse(script.contains("Move-Item -"), "Move-Item schiebt in vorhandene Ordner hinein, darum Directory.Move");
            assertTrue(script.chars().allMatch(c -> c < 128), "Windows PowerShell 5.1 liest Skripte ohne BOM als ANSI");
        }
    }
}
