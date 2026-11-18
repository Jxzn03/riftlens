package com.leaguetool.data.http;

import java.io.InterruptedIOException;

/** Wandelt eine Unterbrechung in eine {@link InterruptedIOException} um und setzt das Interrupt-Flag erneut. */
public final class Interrupts {

    private Interrupts() {
    }

    public static InterruptedIOException toIo(InterruptedException e, String message) {
        Thread.currentThread().interrupt();
        InterruptedIOException ex = new InterruptedIOException(message);
        ex.initCause(e);
        return ex;
    }
}
