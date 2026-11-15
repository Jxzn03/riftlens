package com.leaguetool.data.http;

import java.io.IOException;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Leitet an einen echten Fetcher weiter, solange der Offline-Modus aus ist; sonst {@link OfflineException}. */
public final class OfflineAwareFetcher implements HttpFetcher {

    private final HttpFetcher delegate;
    private final BooleanSupplier offline;

    public OfflineAwareFetcher(HttpFetcher delegate, BooleanSupplier offline) {
        this.delegate = delegate;
        this.offline = offline;
    }

    @Override
    public HttpResult get(String url, Map<String, String> headers) throws IOException {
        if (offline.getAsBoolean()) {
            throw new OfflineException(url);
        }
        return delegate.get(url, headers);
    }

    /** Netzwerkzugriff im Offline-Modus. */
    public static final class OfflineException extends IOException {
        public OfflineException(String url) {
            super("Offline-Modus aktiv, kein Abruf von " + url);
        }
    }
}
