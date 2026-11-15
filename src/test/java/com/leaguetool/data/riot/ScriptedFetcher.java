package com.leaguetool.data.riot;

import com.leaguetool.data.http.HttpFetcher;
import com.leaguetool.data.http.HttpResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-Fetcher mit Antwortfolgen je URL (z. B. erst 429, dann 200) und Aufzeichnung der Header.
 * Die letzte Antwort einer Folge wird wiederholt; unbekannte URLs ergeben 404.
 */
final class ScriptedFetcher implements HttpFetcher {

    record Request(String url, Map<String, String> headers) {
    }

    private final Map<String, Deque<HttpResult>> responses = new HashMap<>();
    private final List<Request> requests = new ArrayList<>();

    ScriptedFetcher respond(String url, HttpResult... results) {
        responses.computeIfAbsent(url, u -> new ArrayDeque<>()).addAll(List.of(results));
        return this;
    }

    ScriptedFetcher respond(String url, String body) {
        return respond(url, HttpResult.ok(body));
    }

    static HttpResult status(int status) {
        return new HttpResult(status, ("{\"status\":{\"status_code\":" + status + "}}").getBytes(StandardCharsets.UTF_8),
                Map.of());
    }

    static HttpResult status(int status, String header, String value) {
        return new HttpResult(status, new byte[0], Map.of(header, List.of(value)));
    }

    synchronized List<Request> requests() {
        return List.copyOf(requests);
    }

    synchronized List<String> urls() {
        return requests.stream().map(Request::url).toList();
    }

    @Override
    public synchronized HttpResult get(String url, Map<String, String> headers) {
        requests.add(new Request(url, Map.copyOf(headers)));
        Deque<HttpResult> queue = responses.get(url);
        if (queue == null || queue.isEmpty()) {
            return new HttpResult(404, "not found".getBytes(StandardCharsets.UTF_8), Map.of());
        }
        return queue.size() > 1 ? queue.pollFirst() : queue.peekFirst();
    }
}
