package com.leaguetool.data.riot;

import java.io.IOException;

/** Fehlerantwort der Riot-API (z. B. 401/403 ungültiger Key, 404, 429 nach allen Wiederholungen). */
public class RiotApiException extends IOException {

    private final int status;

    public RiotApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    /** 401/403: Key fehlt, ist abgelaufen (Dev-Keys nach 24 h) oder ungültig. */
    public boolean isAuthProblem() {
        return status == 401 || status == 403;
    }

    public boolean isNotFound() {
        return status == 404;
    }
}
