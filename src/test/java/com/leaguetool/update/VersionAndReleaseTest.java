package com.leaguetool.update;

import com.leaguetool.util.Json;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionAndReleaseTest {

    static final String DIGEST = "b529ae794536e03d1f5110284cebd63d1d781978676bb99eb8f60c8ae2759676";

    /** Antwort von {@code /releases/latest} in der Form, die GitHub liefert (gekürzt), mit ZIP und Signatur. */
    static String releaseJson(String version, String assetUrl, String digest) {
        return releaseJson(version, assetUrl, digest, officialUrl(version) + ".sig");
    }

    /** Wie oben; {@code signatureUrl == null} lässt die {@code .sig} weg. */
    static String releaseJson(String version, String assetUrl, String digest, String signatureUrl) {
        String sig = signatureUrl == null ? "" : """
                , {"name": "RiftLens-%1$s-win64.zip.sig", "size": 89, "digest": null, "browser_download_url": "%2$s"}"""
                .formatted(version, signatureUrl);
        return """
                {"tag_name": "v%1$s", "name": "RiftLens %1$s - Test",
                 "html_url": "https://github.com/Jxzn03/riftlens/releases/tag/v%1$s", "body": "Änderungen",
                 "assets": [{"name": "RiftLens-%1$s-win64.zip", "size": 1234, "digest": %3$s,
                             "browser_download_url": "%2$s"}%4$s]}
                """.formatted(version, assetUrl, digest == null ? "null" : "\"" + digest + "\"", sig);
    }

    static String officialUrl(String version) {
        return "https://github.com/Jxzn03/riftlens/releases/download/v" + version + "/RiftLens-" + version + "-win64.zip";
    }

    private static GitHubRelease parse(String json) throws IOException {
        return GitHubRelease.parse(Json.MAPPER.readTree(json));
    }

    @Test
    void parsesAndComparesVersions() {
        assertEquals(new Version(2, 0, 1), Version.parse("v2.0.1").orElseThrow());
        assertEquals(new Version(10, 2, 0), Version.parse(" 10.2.0 ").orElseThrow());
        assertTrue(Version.parse("2.0.0-SNAPSHOT").isEmpty());
        assertTrue(Version.parse("").isEmpty());
        assertTrue(Version.parse(null).isEmpty());
        assertTrue(new Version(2, 1, 0).isNewerThan(new Version(2, 0, 9)));
        assertTrue(new Version(2, 0, 10).isNewerThan(new Version(2, 0, 9)), "numerisch, nicht als Text vergleichen");
        assertFalse(new Version(2, 0, 1).isNewerThan(new Version(2, 0, 1)));
        assertEquals("2.0.1", new Version(2, 0, 1).toString());
    }

    @Test
    void readsLatestReleaseWithChecksumAndSignature() throws IOException {
        GitHubRelease r = parse(releaseJson("2.1.0", officialUrl("2.1.0"), "sha256:" + DIGEST));
        assertEquals(new Version(2, 1, 0), r.version());
        assertEquals("RiftLens 2.1.0 - Test", r.name());
        assertEquals("https://github.com/Jxzn03/riftlens/releases/tag/v2.1.0", r.pageUrl());
        assertNotNull(r.asset());
        assertEquals(DIGEST, r.asset().sha256());
        assertEquals(1234, r.asset().size());
        assertEquals(officialUrl("2.1.0"), r.asset().url());
        assertEquals(officialUrl("2.1.0") + ".sig", r.asset().signatureUrl());
    }

    @Test
    void ignoresAssetsFromElsewhere() throws IOException {
        assertNull(parse(releaseJson("2.1.0", "https://example.com/RiftLens-2.1.0-win64.zip", "sha256:" + DIGEST)).asset());
        // Paket einer anderen Version im Release: nicht verwenden
        assertNull(parse(releaseJson("2.1.0", officialUrl("2.0.0"), "sha256:" + DIGEST)).asset());
    }

    @Test
    void checksumIsOptionalSignatureDecides() throws IOException {
        GitHubRelease noDigest = parse(releaseJson("2.1.0", officialUrl("2.1.0"), null));
        assertNotNull(noDigest.asset(), "ohne GitHub-Digest zählt allein die Signatur");
        assertNull(noDigest.asset().sha256());
        assertNull(parse(releaseJson("2.1.0", officialUrl("2.1.0"), "md5:abc")).asset().sha256());
    }

    @Test
    void signatureOnlyFromTheOfficialReleaseFolder() throws IOException {
        assertNull(parse(releaseJson("2.1.0", officialUrl("2.1.0"), "sha256:" + DIGEST, null)).asset().signatureUrl());
        assertNull(parse(releaseJson("2.1.0", officialUrl("2.1.0"), "sha256:" + DIGEST,
                "https://example.com/RiftLens-2.1.0-win64.zip.sig")).asset().signatureUrl());
        assertNull(parse(releaseJson("2.1.0", officialUrl("2.1.0"), "sha256:" + DIGEST, officialUrl("2.0.0") + ".sig"))
                .asset().signatureUrl(), "Signatur eines anderen Tags");
        String huge = releaseJson("2.1.0", officialUrl("2.1.0"), "sha256:" + DIGEST).replace("\"size\": 89", "\"size\": 5000");
        assertNull(parse(huge).asset().signatureUrl(), "Signaturdatei zu groß");
    }

    @Test
    void newestFromListIncludesPrereleasesButNoDrafts() throws IOException {
        String list = "[" + releaseJson("2.0.1", officialUrl("2.0.1"), null)
                + ", " + releaseJson("2.1.0", officialUrl("2.1.0"), null).replaceFirst("\\{", "{\"prerelease\": true, ")
                + ", " + releaseJson("9.0.0", officialUrl("9.0.0"), null).replaceFirst("\\{", "{\"draft\": true, ")
                + ", {\"tag_name\": \"nightly\"}]";
        GitHubRelease newest = GitHubRelease.parseNewest(Json.MAPPER.readTree(list));
        assertEquals(new Version(2, 1, 0), newest.version());
        assertNotNull(newest.asset().signatureUrl());
        assertThrows(IOException.class, () -> GitHubRelease.parseNewest(Json.MAPPER.readTree("[]")));
    }

    @Test
    void rejectsTagsWithoutVersion() {
        assertThrows(IOException.class, () -> GitHubRelease.parse(Json.MAPPER.readTree("{\"tag_name\": \"nightly\"}")));
    }

    @Test
    void foreignPageUrlFallsBackToReleaseList() throws IOException {
        String json = releaseJson("2.1.0", officialUrl("2.1.0"), "sha256:" + DIGEST)
                .replace("https://github.com/Jxzn03/riftlens/releases/tag/v2.1.0", "https://example.com/x");
        assertEquals(GitHubRelease.RELEASES_PAGE, parse(json).pageUrl());
    }
}
