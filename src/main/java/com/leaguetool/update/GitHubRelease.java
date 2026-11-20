package com.leaguetool.update;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Neuestes Release aus der GitHub-API ({@code /releases/latest}, ohne Entwürfe und Vorabversionen).
 *
 * @param version Version aus dem Tag
 * @param name    Titel des Releases, z. B. „RiftLens 2.0.1 - Bugfixes“
 * @param pageUrl Release-Seite auf GitHub (nur Adressen dieses Repositorys)
 * @param notes   Release-Text (Markdown), gekürzt
 * @param asset   ZIP für Windows oder {@code null}, wenn das Release keines hat
 */
public record GitHubRelease(Version version, String name, String pageUrl, String notes, Asset asset) {

    public static final String REPOSITORY = "Jxzn03/riftlens";
    public static final String LATEST_API = "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
    /** Alle Releases inkl. Pre-Releases (nur mit Testschalter {@code update.prerelease}). */
    public static final String LIST_API = "https://api.github.com/repos/" + REPOSITORY + "/releases?per_page=30";
    public static final String RELEASES_PAGE = "https://github.com/" + REPOSITORY + "/releases";
    /** Downloads nur von dieser Adresse (GitHub leitet von dort auf seinen Speicher um). */
    static final String DOWNLOAD_PREFIX = RELEASES_PAGE + "/download/";
    private static final int MAX_NOTES = 4000;
    private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");

    /**
     * ZIP-Paket eines Releases.
     *
     * @param sha256       SHA-256 (hex, klein), die GitHub zum Asset angibt, oder {@code null}; nur Gegenprüfung,
     *                     maßgeblich ist die Signatur
     * @param signatureUrl Adresse der Ed25519-Signatur ({@code <name>.sig}) oder {@code null}, wenn das Release
     *                     keine hat (dann keine automatische Installation)
     */
    public record Asset(String name, String url, long size, String sha256, String signatureUrl) {
    }

    /** Name des Windows-Pakets, wie es {@code build_release.ps1} erzeugt. */
    static String assetName(Version version) {
        return "RiftLens-" + version + "-win64.zip";
    }

    /**
     * Liest die Antwort von {@code /releases/latest}.
     *
     * @throws IOException wenn das Release keinen Versions-Tag hat
     */
    public static GitHubRelease parse(JsonNode json) throws IOException {
        String tag = json.path("tag_name").asText("");
        Version version = Version.parse(tag)
                .orElseThrow(() -> new IOException("Unbekanntes Versionsformat im Release-Tag: " + tag));
        String page = json.path("html_url").asText("");
        if (!page.startsWith(RELEASES_PAGE + "/")) {
            page = RELEASES_PAGE;
        }
        String notes = json.path("body").asText("");
        if (notes.length() > MAX_NOTES) {
            notes = notes.substring(0, MAX_NOTES) + " …";
        }
        String name = json.path("name").asText("");
        return new GitHubRelease(version, name.isBlank() ? "RiftLens " + version : name, page, notes, asset(json, version));
    }

    /**
     * Höchste Version aus der Liste von {@code /releases} (Entwürfe und Releases ohne Versions-Tag übersprungen,
     * Pre-Releases eingeschlossen).
     *
     * @throws IOException wenn die Liste kein brauchbares Release enthält
     */
    public static GitHubRelease parseNewest(JsonNode list) throws IOException {
        GitHubRelease best = null;
        for (JsonNode json : list) {
            if (json.path("draft").asBoolean(false) || Version.parse(json.path("tag_name").asText("")).isEmpty()) {
                continue;
            }
            GitHubRelease release = parse(json);
            if (best == null || release.version().isNewerThan(best.version())) {
                best = release;
            }
        }
        if (best == null) {
            throw new IOException("Auf GitHub ist noch kein Release veröffentlicht");
        }
        return best;
    }

    /** Asset nur mit genau dem erwarteten Namen und der offiziellen Download-Adresse dieses Tags. */
    private static Asset asset(JsonNode json, Version version) {
        String expected = assetName(version);
        String folder = DOWNLOAD_PREFIX + "v" + version + "/";
        JsonNode zip = null;
        String signatureUrl = null;
        for (JsonNode a : json.path("assets")) {
            String name = a.path("name").asText("");
            String url = a.path("browser_download_url").asText("");
            long size = a.path("size").asLong(0);
            if (name.equals(expected) && url.equals(folder + expected) && size > 0) {
                zip = a;
            } else if (name.equals(expected + ".sig") && url.equals(folder + expected + ".sig")
                    && size > 0 && size <= ReleaseSignature.MAX_SIGNATURE_FILE) {
                signatureUrl = url;
            }
        }
        if (zip == null) {
            return null;
        }
        String digest = zip.path("digest").asText("").toLowerCase(Locale.ROOT);
        String sha256 = SHA256.matcher(digest).matches() ? digest.substring("sha256:".length()) : null;
        return new Asset(expected, folder + expected, zip.path("size").asLong(), sha256, signatureUrl);
    }
}
