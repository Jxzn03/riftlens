package com.leaguetool.data.lcu;

import com.leaguetool.data.http.Interrupts;
import com.fasterxml.jackson.databind.JsonNode;

import com.leaguetool.util.Json;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * Lesende HTTPS-Abfragen an den League-Client auf {@code 127.0.0.1}. Das Zertifikat des Clients muss von Riots
 * öffentlicher CA ({@code riotgames.pem}) signiert sein; nur der Hostname wird nicht geprüft, weil der Client auf
 * einer IP-Adresse lauscht und sein Zertifikat auf einen Riot-eigenen Namen lautet.
 */
public final class LcuHttpTransport implements LcuTransport {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final String CERTIFICATE = "riotgames.pem";
    private static volatile SSLContext riotContext;

    private final HttpClient http;
    private final String baseUrl;
    private final String authorization;

    public LcuHttpTransport(LcuLockfile lockfile) {
        this(lockfile, riotContext());
    }

    /** @param tls vertraut genau einer CA, in der App {@link #riotContext()} */
    LcuHttpTransport(LcuLockfile lockfile, SSLContext tls) {
        this.http = HttpClient.newBuilder()
                .sslContext(tls)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.baseUrl = "https://127.0.0.1:" + lockfile.port();
        this.authorization = "Basic " + Base64.getEncoder()
                .encodeToString(("riot:" + lockfile.password()).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Optional<JsonNode> get(LcuEndpoint endpoint) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + endpoint.path()))
                .timeout(TIMEOUT)
                .header("Authorization", authorization)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            throw Interrupts.toIo(e, "Abfrage des League-Clients abgebrochen");
        }
        int status = response.statusCode();
        // 404 bzw. 403: Der Endpunkt ist außerhalb der Champion-Auswahl nicht verfügbar.
        if (status == 404 || status == 403) {
            return Optional.empty();
        }
        if (status == 401) {
            throw new IOException("Der League-Client lehnt den Zugang ab (neu gestartet?)");
        }
        if (status < 200 || status >= 300) {
            throw new IOException("Der League-Client antwortet mit HTTP " + status);
        }
        String body = response.body();
        return body == null || body.isBlank() ? Optional.empty() : Optional.of(Json.MAPPER.readTree(body));
    }

    /** TLS-Kontext, der nur Riots CA vertraut. */
    static SSLContext riotContext() {
        SSLContext context = riotContext;
        if (context == null) {
            synchronized (LcuHttpTransport.class) {
                if (riotContext == null) {
                    riotContext = contextTrusting(riotCertificate());
                }
                context = riotContext;
            }
        }
        return context;
    }

    static X509Certificate riotCertificate() {
        try (InputStream in = LcuHttpTransport.class.getResourceAsStream(CERTIFICATE)) {
            if (in == null) {
                throw new IllegalStateException("Riot-Zertifikat fehlt im Jar");
            }
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        } catch (IOException | CertificateException e) {
            throw new IllegalStateException("Riot-Zertifikat nicht lesbar", e);
        }
    }

    /** TLS-Kontext, der nur Ketten von {@code authority} annimmt, ohne Hostnamen-Abgleich. */
    static SSLContext contextTrusting(X509Certificate authority) {
        try {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            store.setCertificateEntry("authority", authority);
            TrustManagerFactory factory = TrustManagerFactory.getInstance("PKIX");
            factory.init(store);
            X509TrustManager pkix = null;
            for (TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof X509TrustManager x509) {
                    pkix = x509;
                }
            }
            if (pkix == null) {
                throw new IllegalStateException("Kein X.509-TrustManager verfügbar");
            }
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new ChainOnlyTrustManager(pkix)}, null);
            return context;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("TLS für den League-Client nicht einrichtbar", e);
        }
    }

    /**
     * Prüft die Zertifikatskette gegen Riots CA, aber nicht den Hostnamen. Als {@link X509ExtendedTrustManager}
     * übernimmt er die Prüfung vollständig; Java ergänzt dann keinen eigenen Hostnamen-Abgleich.
     */
    static final class ChainOnlyTrustManager extends X509ExtendedTrustManager {

        private final X509TrustManager pkix;

        ChainOnlyTrustManager(X509TrustManager pkix) {
            this.pkix = pkix;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            pkix.checkServerTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            pkix.checkServerTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            pkix.checkServerTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("Keine Client-Zertifikate");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            throw new CertificateException("Keine Client-Zertifikate");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            throw new CertificateException("Keine Client-Zertifikate");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return pkix.getAcceptedIssuers();
        }
    }
}
