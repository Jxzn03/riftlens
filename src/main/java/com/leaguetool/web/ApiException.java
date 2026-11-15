package com.leaguetool.web;

/** Fehler, der mit Statuscode und verständlicher Meldung an die Oberfläche geht. */
final class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;

    ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    int status() {
        return status;
    }
}
