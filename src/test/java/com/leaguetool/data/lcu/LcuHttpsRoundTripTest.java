package com.leaguetool.data.lcu;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nachbau des League-Clients: HTTPS auf 127.0.0.1, Zertifikat auf {@code CN=rclient} (passt nicht zur Adresse), signiert
 * von einer eigenen Test-CA, Basic-Auth mit Benutzer {@code riot}. So wird geprüft, dass die Kette zählt, nicht der
 * Hostname, und dass fremde Zertifikate abgelehnt werden.
 */
class LcuHttpsRoundTripTest {

    private static final String PASSWORD = "test-passwort";
    private static final String STORE_PASSWORD = "changeit";

    private HttpsServer server;
    private final List<String> requests = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = getClass().getResourceAsStream("test-server.p12")) {
            keys.load(in, STORE_PASSWORD.toCharArray());
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, STORE_PASSWORD.toCharArray());
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(keyManagers.getKeyManagers(), null, null);

        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            String expected = "Basic " + Base64.getEncoder().encodeToString(("riot:" + PASSWORD).getBytes(StandardCharsets.UTF_8));
            int status;
            String body = "";
            if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                status = 401;
            } else if (exchange.getRequestURI().getPath().equals("/lol-champ-select/v1/session")) {
                status = 200;
                body = "{\"gameId\": 42}";
            } else {
                status = 404;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private LcuLockfile lockfile(String password) {
        return new LcuLockfile(server.getAddress().getPort(), password);
    }

    private static SSLContext trustingTestCa() throws Exception {
        try (InputStream in = LcuHttpsRoundTripTest.class.getResourceAsStream("test-ca.pem")) {
            X509Certificate ca = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
            return LcuHttpTransport.contextTrusting(ca);
        }
    }

    @Test
    void readsFromAClientWhoseCertificateNamesAnotherHost() throws Exception {
        LcuHttpTransport transport = new LcuHttpTransport(lockfile(PASSWORD), trustingTestCa());

        Optional<JsonNode> session = transport.get(LcuEndpoint.CHAMP_SELECT_SESSION);
        assertEquals(42, session.orElseThrow().path("gameId").asInt());
        assertTrue(transport.get(LcuEndpoint.PICKABLE_CHAMPIONS).isEmpty(), "404 heißt: gerade nicht verfügbar");
        assertEquals(List.of("GET /lol-champ-select/v1/session", "GET /lol-champ-select/v1/pickable-champion-ids"), requests,
                "nur lesende Abfragen");
    }

    @Test
    void rejectsAWrongPassword() throws Exception {
        LcuHttpTransport transport = new LcuHttpTransport(lockfile("falsch"), trustingTestCa());
        IOException error = assertThrows(IOException.class, () -> transport.get(LcuEndpoint.CHAMP_SELECT_SESSION));
        assertTrue(error.getMessage().contains("lehnt den Zugang ab"), error.getMessage());
    }

    @Test
    void rejectsServersNotSignedByRiot() {
        // In der App gilt nur Riots CA: Ein anderes Zertifikat auf dem Port wird nicht angesprochen.
        LcuHttpTransport transport = new LcuHttpTransport(lockfile(PASSWORD));
        assertThrows(IOException.class, () -> transport.get(LcuEndpoint.CHAMP_SELECT_SESSION));
        assertTrue(requests.isEmpty(), "Ohne gültiges Zertifikat geht kein Passwort an den Server");
    }
}
