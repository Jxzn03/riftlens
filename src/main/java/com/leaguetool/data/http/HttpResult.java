package com.leaguetool.data.http;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Antwort eines HTTP-GET.
 *
 * @param status  HTTP-Statuscode
 * @param body    Rohdaten der Antwort (nie {@code null})
 * @param headers Response-Header (Schlüssel wie vom Server geliefert)
 */
public record HttpResult(int status, byte[] body, Map<String, List<String>> headers) {

    public HttpResult {
        body = body == null ? new byte[0] : body;
        headers = headers == null ? Map.of() : headers;
    }

    public static HttpResult ok(String text) {
        return new HttpResult(200, text.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    /** Body als UTF-8-Text. */
    public String text() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /** Erster Wert eines Headers, Name ohne Beachtung der Groß-/Kleinschreibung. */
    public Optional<String> header(String name) {
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                return Optional.of(e.getValue().get(0));
            }
        }
        return Optional.empty();
    }
}
