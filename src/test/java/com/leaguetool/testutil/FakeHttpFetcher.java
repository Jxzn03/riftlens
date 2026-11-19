package com.leaguetool.testutil;

import com.leaguetool.data.http.HttpFetcher;
import com.leaguetool.data.http.HttpResult;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * Test-Fetcher: liefert hinterlegte Antworten je URL; unbekannte URLs ergeben 404.
 * Mit {@link #setOffline(boolean)} wird jeder Abruf zu einer {@link ConnectException}.
 */
public final class FakeHttpFetcher implements HttpFetcher {

    private final Map<String, HttpResult> responses = new LinkedHashMap<>();
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean offline;
    private final Map<String, CountDownLatch> holds = new ConcurrentHashMap<>();

    public FakeHttpFetcher respond(String url, String body) {
        responses.put(url, new HttpResult(200, body.getBytes(StandardCharsets.UTF_8), Map.of()));
        return this;
    }

    public FakeHttpFetcher respond(String url, HttpResult result) {
        responses.put(url, result);
        return this;
    }

    /** Hält Abrufe dieser URL an, bis der zurückgegebene Latch heruntergezählt ist (prüft, wer auf einen Abruf wartet). */
    public CountDownLatch hold(String url) {
        CountDownLatch latch = new CountDownLatch(1);
        holds.put(url, latch);
        return latch;
    }

    public void setOffline(boolean offline) {
        this.offline = offline;
    }

    /** Alle angefragten URLs in Reihenfolge. */
    public List<String> requests() {
        return List.copyOf(requests);
    }

    @Override
    public HttpResult get(String url, Map<String, String> headers) throws IOException {
        requests.add(url);
        CountDownLatch hold = holds.get(url);
        if (hold != null) {
            try {
                hold.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Abgebrochen (Test)");
            }
        }
        if (offline) {
            throw new ConnectException("offline (Test): " + url);
        }
        HttpResult r = responses.get(url);
        return r != null ? r : new HttpResult(404, "not found".getBytes(StandardCharsets.UTF_8), Map.of());
    }
}
