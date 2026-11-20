package com.leaguetool.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseSignatureTest {

    private static final String NAME = "RiftLens-2.1.0-win64.zip";
    private static final String SHA = "ab".repeat(32);

    @TempDir
    Path tmp;

    @Test
    void builtInKeyIsUsable() {
        assertDoesNotThrow(ReleaseSignature::publicKey, "ohne gültigen Schlüssel gibt es keine Auto-Updates");
        assertDoesNotThrow(ReleaseSignature::selfCheck);
    }

    @Test
    void verifiesOwnSignatureOnly() throws Exception {
        KeyPair pair = ReleaseSignature.generate();
        byte[] sig = ReleaseSignature.sign(pair.getPrivate(), NAME, SHA);

        assertDoesNotThrow(() -> ReleaseSignature.verify(pair.getPublic(), NAME, SHA, sig));
        assertThrows(IOException.class, () -> ReleaseSignature.verify(pair.getPublic(), NAME, "cd".repeat(32), sig),
                "anderes Paket");
        assertThrows(IOException.class, () -> ReleaseSignature.verify(pair.getPublic(), "RiftLens-2.2.0-win64.zip", SHA, sig),
                "altes, echt signiertes Paket unter neuem Namen");
        KeyPair other = ReleaseSignature.generate();
        assertThrows(IOException.class, () -> ReleaseSignature.verify(other.getPublic(), NAME, SHA, sig), "fremder Schlüssel");
    }

    @Test
    void rejectsBrokenSignatureFiles() throws Exception {
        KeyPair pair = ReleaseSignature.generate();
        assertThrows(IOException.class, () -> ReleaseSignature.verify(pair.getPublic(), NAME, SHA, new byte[0]));
        assertThrows(IOException.class, () -> ReleaseSignature.verify(pair.getPublic(), NAME, SHA, "kein base64!".getBytes(StandardCharsets.US_ASCII)));
        byte[] shortSig = Base64.getEncoder().encode(new byte[10]);
        assertThrows(IOException.class, () -> ReleaseSignature.verify(pair.getPublic(), NAME, SHA, shortSig));
        assertThrows(IOException.class, () -> ReleaseSignature.verify(pair.getPublic(), NAME, SHA, new byte[ReleaseSignature.MAX_SIGNATURE_FILE + 1]));
    }

    @Test
    void keyFileRoundTripAndNoOverwrite() throws Exception {
        Path file = tmp.resolve("schluessel").resolve("signing-key.txt");
        String publicKey = ReleaseSignature.writeNewKey(file);

        String text = Files.readString(file);
        assertTrue(text.startsWith("#"), "Kopfzeile erklärt die Datei");
        assertTrue(text.contains(publicKey), "öffentlicher Schlüssel steht als Kommentar dabei");
        byte[] sig = ReleaseSignature.sign(ReleaseSignature.readKey(file), NAME, SHA);
        assertDoesNotThrow(() -> ReleaseSignature.verify(ReleaseSignature.parsePublicKey(publicKey), NAME, SHA, sig));

        assertThrows(FileAlreadyExistsException.class, () -> ReleaseSignature.writeNewKey(file));
        assertEquals(text, Files.readString(file), "vorhandener Schlüssel bleibt unverändert");
    }

    @Test
    void signFileWritesSigAndRefusesForeignKey() throws Exception {
        Path zip = Files.write(tmp.resolve(NAME), UpdatePackageTest.releaseZip("2.1.0"));
        KeyPair pair = ReleaseSignature.generate();

        Path sig = ReleaseSignature.signFile(zip, pair.getPrivate(), pair.getPublic());
        assertEquals(tmp.resolve(NAME + ".sig"), sig);
        assertDoesNotThrow(() -> ReleaseSignature.verifyFile(zip, pair.getPublic()));
        assertDoesNotThrow(() -> ReleaseSignature.verify(pair.getPublic(), NAME, UpdatePackageTest.sha256(Files.readAllBytes(zip)),
                Files.readAllBytes(sig)), "die App prüft genau das, was signFile schreibt");

        Files.delete(sig);
        KeyPair other = ReleaseSignature.generate();
        IOException e = assertThrows(IOException.class, () -> ReleaseSignature.signFile(zip, other.getPrivate(), pair.getPublic()));
        assertTrue(e.getMessage().contains("PUBLIC_KEY"), e.getMessage());
        assertFalse(Files.exists(sig), "mit falschem Schlüssel entsteht keine .sig");
    }

    @Test
    void commandLineReportsUsageAndErrors() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8);
        assertEquals(2, ReleaseSignature.run(new String[0], out, errStream));
        assertEquals(1, ReleaseSignature.run(new String[] {"sign", tmp.resolve("x.zip").toString(), tmp.resolve("fehlt.txt").toString()}, out, errStream));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("fehlt"));
    }
}
