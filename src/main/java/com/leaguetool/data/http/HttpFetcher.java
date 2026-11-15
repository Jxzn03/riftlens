package com.leaguetool.data.http;

import java.io.IOException;
import java.util.Map;

/**
 * Minimale HTTP-Abstraktion, damit Clients in Tests mit Fixtures statt echtem Netz laufen.
 * Unterbrechungen werden als {@link java.io.InterruptedIOException} gemeldet (Interrupt-Flag bleibt gesetzt).
 */
@FunctionalInterface
public interface HttpFetcher {

    /** Führt ein GET aus. Fehlerstatus (4xx/5xx) wird <em>nicht</em> als Exception gemeldet. */
    HttpResult get(String url, Map<String, String> headers) throws IOException;

    default HttpResult get(String url) throws IOException {
        return get(url, Map.of());
    }

    /** GET, das bei Nicht-2xx eine {@link HttpStatusException} wirft. */
    default String getText(String url) throws IOException {
        HttpResult r = get(url, Map.of());
        if (!r.isSuccess()) {
            throw new HttpStatusException(url, r.status(), r.text());
        }
        return r.text();
    }

    /** Wie {@link #getText(String)}, aber für Binärdaten (Bilder). */
    default byte[] getBytes(String url) throws IOException {
        HttpResult r = get(url, Map.of());
        if (!r.isSuccess()) {
            throw new HttpStatusException(url, r.status(), null);
        }
        return r.body();
    }
}
