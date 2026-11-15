package com.leaguetool.data.http;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** {@link HttpFetcher} auf Basis von {@link java.net.http.HttpClient}. */
public final class JavaHttpFetcher implements HttpFetcher {

    public static final String USER_AGENT = "RiftLens (open-source desktop app)";

    private final HttpClient client;
    private final Duration requestTimeout;

    public JavaHttpFetcher() {
        this(Duration.ofSeconds(10), Duration.ofSeconds(30));
    }

    public JavaHttpFetcher(Duration connectTimeout, Duration requestTimeout) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.requestTimeout = requestTimeout;
    }

    @Override
    public HttpResult get(String url, Map<String, String> headers) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("User-Agent", USER_AGENT)
                .GET();
        headers.forEach(builder::header);
        try {
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new HttpResult(response.statusCode(), response.body(), response.headers().map());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            InterruptedIOException ex = new InterruptedIOException("Abgebrochen: " + url);
            ex.initCause(e);
            throw ex;
        }
    }
}
