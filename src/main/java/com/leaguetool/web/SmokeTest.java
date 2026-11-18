package com.leaguetool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.leaguetool.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/** {@code --smoke-test}: ruft die wichtigsten Endpunkte der laufenden Instanz ab und meldet das Ergebnis. */
public final class SmokeTest {

    private SmokeTest() {
    }

    /** @return Exit-Code: 0 bei Erfolg, 1 bei einem Fehler */
    public static int run(WebServer server) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        ObjectMapper json = Json.MAPPER;
        try {
            JsonNode data = json.readTree(get(http, server, "/api/bootstrap"));
            String champion = data.path("champions").path(0).path("id").asText();
            int key = data.path("champions").path(0).path("key").asInt();
            int item = data.path("items").path(0).path("id").asInt();
            for (String path : List.of("/", "/app.css", "/js/main.js", "/vendor/htm-preact.js", "/api/settings",
                    "/api/stats/meta", "/api/stats/champions", "/api/stats/report/" + key, "/api/build?items=" + item,
                    "/api/champions/" + champion + "/detail", "/api/champions/" + champion + "/levels?items=" + item,
                    "/api/champions/" + champion + "/wiki", "/api/champions/" + champion + "/insights?level=11")) {
                get(http, server, path);
            }
            System.out.println("SMOKE OK patch=" + data.path("patch").asText() + " offline=" + data.path("offline").asBoolean()
                    + " champions=" + data.path("champions").size() + " items=" + data.path("items").size()
                    + " runeTrees=" + data.path("runeTrees").size() + " spells=" + data.path("spells").size());
            return 0;
        } catch (IOException | RuntimeException e) {
            System.out.println("SMOKE FAILED " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("SMOKE FAILED unterbrochen");
            return 1;
        }
    }

    private static String get(HttpClient http, WebServer server, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
                .timeout(Duration.ofMinutes(2)).header("Cookie", server.sessionCookie()).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException(path + " → HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }
}
