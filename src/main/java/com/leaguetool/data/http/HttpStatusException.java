package com.leaguetool.data.http;

import java.io.IOException;

/** HTTP-Antwort mit Fehlerstatus (nicht 2xx). */
public class HttpStatusException extends IOException {

    private final int status;
    private final String url;

    public HttpStatusException(String url, int status, String bodySnippet) {
        super("HTTP " + status + " für " + url + (bodySnippet == null || bodySnippet.isBlank()
                ? "" : ": " + abbreviate(bodySnippet)));
        this.status = status;
        this.url = url;
    }

    public int status() {
        return status;
    }

    public String url() {
        return url;
    }

    private static String abbreviate(String s) {
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "…" : oneLine;
    }
}
