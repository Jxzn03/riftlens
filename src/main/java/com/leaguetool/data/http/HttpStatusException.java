package com.leaguetool.data.http;

import java.io.IOException;

/** HTTP-Antwort mit Fehlerstatus (nicht 2xx). */
public class HttpStatusException extends IOException {

    public HttpStatusException(String url, int status, String bodySnippet) {
        super("HTTP " + status + " für " + url + (bodySnippet == null || bodySnippet.isBlank()
                ? "" : ": " + abbreviate(bodySnippet)));
    }

    private static String abbreviate(String s) {
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "…" : oneLine;
    }
}
