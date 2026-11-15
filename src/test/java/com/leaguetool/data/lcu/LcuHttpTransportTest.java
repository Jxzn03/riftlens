package com.leaguetool.data.lcu;

import org.junit.jupiter.api.Test;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LcuHttpTransportTest {

    @Test
    void bundlesRiotsPublicCertificate() throws Exception {
        X509Certificate riot = LcuHttpTransport.riotCertificate();
        assertTrue(riot.getSubjectX500Principal().getName().contains("LoL Game Engineering Certificate Authority"));
        String sha256 = HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(riot.getEncoded()));
        assertEquals("CA8C9D325B4CDC464C6C94A585C85E91EC23D40BA5BF3AE2822B951A4A504EA3", sha256,
                "Fingerabdruck von https://static.developer.riotgames.com/docs/lol/riotgames.pem");
    }

    @Test
    void trustsOnlyCertificatesFromRiot() throws Exception {
        assertDoesNotThrow(LcuHttpTransport::riotContext, "TLS-Kontext lässt sich bauen");
        X509TrustManager trust = chainOnly();
        assertDoesNotThrow(() -> trust.checkServerTrusted(new X509Certificate[]{LcuHttpTransport.riotCertificate()}, "RSA"));

        X509Certificate foreign = anyPublicCaCertificate();
        assertThrows(CertificateException.class, () -> trust.checkServerTrusted(new X509Certificate[]{foreign}, "RSA"),
                "Ein Zertifikat, das nicht von Riot stammt, wird abgelehnt");
    }

    private static X509TrustManager chainOnly() throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        store.setCertificateEntry("riotgames", LcuHttpTransport.riotCertificate());
        TrustManagerFactory factory = TrustManagerFactory.getInstance("PKIX");
        factory.init(store);
        return new LcuHttpTransport.ChainOnlyTrustManager((X509TrustManager) factory.getTrustManagers()[0]);
    }

    /** Irgendein Zertifikat einer öffentlichen CA aus dem Truststore des JDK. */
    private static X509Certificate anyPublicCaCertificate() throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        return ((X509TrustManager) factory.getTrustManagers()[0]).getAcceptedIssuers()[0];
    }
}
