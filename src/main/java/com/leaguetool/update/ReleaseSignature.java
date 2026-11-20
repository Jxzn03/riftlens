package com.leaguetool.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;

/**
 * Ed25519-Signatur der Release-Pakete. Die App installiert nur ZIPs, deren {@code .sig} zum fest eingebauten
 * öffentlichen Schlüssel passt; so reicht ein Zugriff auf das GitHub-Konto allein nicht, um Nutzern Code
 * unterzuschieben.
 *
 * <p>Signiert wird nicht die Datei selbst, sondern {@link #message(String, String)}: Asset-Name (enthält die Version)
 * und SHA-256 des ZIPs. Ein älteres, echt signiertes Paket lässt sich so nicht unter einem neuen Tag ausliefern.
 *
 * <p>Werkzeug für Releases (nur JDK-Klassen, läuft mit {@code java -cp <jar> com.leaguetool.update.ReleaseSignature}):
 * {@code keygen [datei]}, {@code sign <zip> [datei]}, {@code verify <zip>}. Ohne Angabe liegt der private Schlüssel
 * in {@link #defaultKeyFile()}.
 */
public final class ReleaseSignature {

    /** Öffentlicher Release-Schlüssel (X.509, Base64). Gegenstück: privater Schlüssel des Maintainers, nie im Repo. */
    static final String PUBLIC_KEY = "MCowBQYDK2VwAyEAEbIVSHBYzqUkLRJV0D0tbi3TKILTAg2nAk7ccJkw/WY=";

    static final String ALGORITHM = "Ed25519";
    /** Obergrenze für eine {@code .sig}-Datei (Base64 einer 64-Byte-Signatur, mit Zeilenende). */
    static final int MAX_SIGNATURE_FILE = 1024;
    private static final String CONTEXT = "RiftLens-Update-Signatur v1";
    private static final int SIGNATURE_BYTES = 64;

    private ReleaseSignature() {
    }

    /** Der eingebaute öffentliche Schlüssel. */
    public static PublicKey publicKey() throws IOException {
        return parsePublicKey(PUBLIC_KEY);
    }

    /** Prüft beim Smoke-Test, dass die gepackte Laufzeit Ed25519 kann und der eingebaute Schlüssel lesbar ist. */
    public static void selfCheck() throws IOException {
        KeyPair pair = generate();
        byte[] sig = sign(pair.getPrivate(), "probe.zip", "0".repeat(64));
        verify(pair.getPublic(), "probe.zip", "0".repeat(64), sig);
        publicKey();
    }

    static PublicKey parsePublicKey(String base64) throws IOException {
        try {
            return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64.trim())));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException("Öffentlicher Release-Schlüssel fehlt oder ist ungültig", e);
        }
    }

    /** Signierter Inhalt: fester Kontext, Asset-Name und SHA-256 (hex, klein). */
    static byte[] message(String assetName, String sha256) {
        return (CONTEXT + "\n" + assetName + "\n" + sha256 + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Prüft eine {@code .sig}-Datei (Base64) zu einem Asset.
     *
     * @throws IOException wenn die Signatur fehlt, kaputt ist oder nicht passt
     */
    static void verify(PublicKey key, String assetName, String sha256, byte[] signatureFile) throws IOException {
        byte[] sig = decodeSignature(signatureFile);
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(key);
            verifier.update(message(assetName, sha256));
            if (!verifier.verify(sig)) {
                throw new IOException("Signatur des Update-Pakets ist ungültig – Update abgebrochen");
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("Signatur des Update-Pakets nicht prüfbar: " + e.getMessage(), e);
        }
    }

    private static byte[] decodeSignature(byte[] file) throws IOException {
        if (file == null || file.length == 0 || file.length > MAX_SIGNATURE_FILE) {
            throw new IOException("Signatur des Update-Pakets fehlt oder ist zu groß");
        }
        try {
            byte[] sig = Base64.getDecoder().decode(new String(file, StandardCharsets.US_ASCII).trim());
            if (sig.length != SIGNATURE_BYTES) {
                throw new IOException("Signatur des Update-Pakets hat ein falsches Format");
            }
            return sig;
        } catch (IllegalArgumentException e) {
            throw new IOException("Signatur des Update-Pakets hat ein falsches Format", e);
        }
    }

    /** Signatur als Inhalt einer {@code .sig}-Datei (Base64 mit Zeilenende). */
    static byte[] sign(PrivateKey key, String assetName, String sha256) throws IOException {
        try {
            Signature signer = Signature.getInstance(ALGORITHM);
            signer.initSign(key);
            signer.update(message(assetName, sha256));
            return (Base64.getEncoder().encodeToString(signer.sign()) + "\n").getBytes(StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException e) {
            throw new IOException("Signieren fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    static KeyPair generate() throws IOException {
        try {
            return KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IOException("Ed25519 nicht verfügbar", e);
        }
    }

    // ------------------------------------------------------------------ Schlüsseldatei

    /** {@code RIFTLENS_SIGNING_KEY} oder {@code %USERPROFILE%\.riftlens-release\signing-key.txt}. */
    static Path defaultKeyFile() {
        String env = System.getenv("RIFTLENS_SIGNING_KEY");
        return env != null && !env.isBlank() ? Path.of(env) : Path.of(System.getProperty("user.home"), ".riftlens-release", "signing-key.txt");
    }

    /**
     * Erzeugt ein Schlüsselpaar und schreibt den privaten Schlüssel nach {@code file} (nie überschreiben, Zugriff nur
     * für das eigene Konto).
     *
     * @return der öffentliche Schlüssel (Base64) zum Eintragen in {@link #PUBLIC_KEY}
     */
    static String writeNewKey(Path file) throws IOException {
        if (Files.exists(file)) {
            throw new FileAlreadyExistsException(file.toString(), null, "Schlüsseldatei existiert schon, wird nicht überschrieben");
        }
        KeyPair pair = generate();
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        String text = "# RiftLens-Release-Signaturschluessel (Ed25519, PRIVAT). Nicht weitergeben, sicher aufbewahren.\n"
                + "# Oeffentlicher Schluessel: " + publicKey + "\n"
                + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()) + "\n";
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, text, StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        restrictToOwner(file);
        return publicKey;
    }

    /** Liest den privaten Schlüssel (erste Zeile ohne {@code #}). */
    static PrivateKey readKey(Path file) throws IOException {
        for (String line : Files.readAllLines(file, StandardCharsets.US_ASCII)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                try {
                    return KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(trimmed)));
                } catch (GeneralSecurityException | IllegalArgumentException e) {
                    throw new IOException("Schlüsseldatei " + file + " ist ungültig", e);
                }
            }
        }
        throw new IOException("Schlüsseldatei " + file + " enthält keinen Schlüssel");
    }

    /**
     * Signiert ein Release-ZIP und schreibt {@code <zip>.sig}; prüft danach gegen {@code expected} (in der App der
     * eingebaute Schlüssel), damit kein Release mit einem fremden Schlüssel entsteht.
     */
    static Path signFile(Path zip, PrivateKey key, PublicKey expected) throws IOException {
        String name = zip.getFileName().toString();
        String sha = sha256(zip);
        byte[] sig = sign(key, name, sha);
        try {
            verify(expected, name, sha, sig);
        } catch (IOException e) {
            throw new IOException("Schlüssel passt nicht zum öffentlichen Schlüssel im Programm (ReleaseSignature.PUBLIC_KEY)", e);
        }
        Path out = zip.resolveSibling(name + ".sig");
        Files.write(out, sig);
        return out;
    }

    /** Prüft {@code <zip>.sig} gegen {@code key}. */
    static void verifyFile(Path zip, PublicKey key) throws IOException {
        Path sig = zip.resolveSibling(zip.getFileName() + ".sig");
        if (!Files.isRegularFile(sig)) {
            throw new IOException("Signaturdatei fehlt: " + sig);
        }
        verify(key, zip.getFileName().toString(), sha256(zip), Files.readAllBytes(sig));
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                sha.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Nur das eigene Konto darf die Schlüsseldatei lesen (Windows-ACL); ohne ACL-Unterstützung bleibt es beim Standard. */
    private static void restrictToOwner(Path file) {
        try {
            AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
            if (view != null) {
                AclEntry owner = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(view.getOwner())
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
                view.setAcl(List.of(owner));
            }
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // Datei liegt ohnehin im Benutzerprofil
        }
    }

    // ------------------------------------------------------------------ Kommandozeile (Release-Bau)

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        String command = args.length == 0 ? "" : args[0];
        String[] rest = Arrays.copyOfRange(args, Math.min(1, args.length), args.length);
        try {
            switch (command) {
                case "keygen" -> {
                    Path file = rest.length > 0 ? Path.of(rest[0]) : defaultKeyFile();
                    String publicKey = writeNewKey(file);
                    out.println("Privater Schluessel geschrieben: " + file.toAbsolutePath());
                    out.println("Sichern (z. B. Passwortmanager) und nie weitergeben.");
                    out.println("Oeffentlicher Schluessel fuer ReleaseSignature.PUBLIC_KEY:");
                    out.println(publicKey);
                }
                case "sign" -> {
                    if (rest.length < 1) {
                        throw new IllegalArgumentException("sign <zip> [schluesseldatei]");
                    }
                    Path key = rest.length > 1 ? Path.of(rest[1]) : defaultKeyFile();
                    if (!Files.isRegularFile(key)) {
                        throw new IOException("Schluesseldatei fehlt: " + key);
                    }
                    Path sig = signFile(Path.of(rest[0]), readKey(key), publicKey());
                    out.println("Signatur geschrieben: " + sig);
                }
                case "verify" -> {
                    if (rest.length < 1) {
                        throw new IllegalArgumentException("verify <zip>");
                    }
                    verifyFile(Path.of(rest[0]), publicKey());
                    out.println("Signatur OK: " + rest[0]);
                }
                default -> throw new IllegalArgumentException("keygen [datei] | sign <zip> [datei] | verify <zip>");
            }
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("Aufruf: ReleaseSignature " + e.getMessage());
            return 2;
        } catch (IOException e) {
            err.println("FEHLER: " + e.getMessage());
            return 1;
        }
    }
}
