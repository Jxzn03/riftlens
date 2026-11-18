package com.leaguetool.web;

import com.leaguetool.data.ddragon.DDragonClient;
import com.leaguetool.data.ddragon.ImageRef;
import com.leaguetool.cache.FileCache;
import com.leaguetool.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Lokaler HTTP-Server der modernen Oberfläche (JDK-Server, keine zusätzliche Bibliothek).
 *
 * <p>Andere Programme und Webseiten dürfen nicht zugreifen: Der Server hört nur auf 127.0.0.1, prüft den
 * Host-Header (gegen DNS-Rebinding), verlangt ein zufälliges Sitzungs-Token als Cookie und bei
 * schreibenden Anfragen eine passende {@code Origin}.
 */
public final class WebServer implements AutoCloseable {

    private static final int THREADS = 32;
    private static final int MAX_BODY = 1024 * 1024;
    private static final String RESOURCE_ROOT = "/com/leaguetool/web";
    private static final String PREACT_RESOURCE = "/META-INF/resources/webjars/htm/3.1.1/preact/standalone.module.js";
    private static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
    private static final Pattern STATIC_PATH = Pattern.compile("/(?:js/)?[a-z0-9-]+\\.(?:js|css|svg)");
    private static final Pattern PATCH = Pattern.compile("\\d+\\.\\d+\\.\\d+");
    private static final Pattern IMAGE_FILE = Pattern.compile("[A-Za-z0-9._-]+\\.(?:png|jpg|webp)");
    private static final Pattern RUNE_ICON = Pattern.compile("[A-Za-z0-9._/-]+\\.(?:png|jpg|webp)");
    private static final Pattern SPLASH = Pattern.compile("[A-Za-z0-9]+_0\\.jpg");
    private static final List<String> IMAGE_GROUPS = List.of("champion", "item", "spell", "passive", "profileicon");

    private final WebContext ctx;
    private final ApiRoutes routes;
    private final EventHub events;
    private final HttpServer server;
    private final ExecutorService executor;
    private final String token;
    private final String host;
    private final String cookieName;
    private final Map<String, byte[]> resources = new HashMap<>();

    private WebServer(WebContext ctx, int port) throws IOException {
        this.ctx = ctx;
        this.routes = new ApiRoutes(ctx, Json.MAPPER);
        this.events = new EventHub(Json.MAPPER);
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        this.token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        this.server = HttpServer.create(new InetSocketAddress(loopback, port), 64);
        this.executor = Executors.newFixedThreadPool(THREADS, Thread.ofPlatform().daemon().name("lt-http").factory());
        this.host = "127.0.0.1:" + server.getAddress().getPort();
        this.cookieName = "lt_session_" + server.getAddress().getPort();
        server.setExecutor(executor);
        server.createContext("/", this::dispatch);
    }

    /** Startet den Server; {@code port == 0} wählt einen freien Port (mehrere Instanzen sind möglich). */
    public static WebServer start(WebContext ctx, int port) throws IOException {
        WebServer web = new WebServer(ctx, port);
        ctx.setEvents(web.events::publish);
        web.server.start();
        return web;
    }

    /** URL, mit der ein Fenster die Sitzung beginnt (enthält das Token). */
    public String entryUrl() {
        return "http://" + host + "/?t=" + token;
    }

    public String baseUrl() {
        return "http://" + host;
    }

    /** Wert des Cookie-Headers einer angemeldeten Sitzung (für Prüfungen). */
    public String sessionCookie() {
        return cookieName + "=" + token;
    }

    /** Beendet die App über {@code action}, sobald das letzte Fenster geschlossen wurde. */
    public void exitWhenClosed(Runnable action) {
        events.exitWhenClosed(action);
    }

    private void dispatch(HttpExchange ex) {
        try {
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            if (!host.equals(ex.getRequestHeaders().getFirst("Host"))) {
                sendText(ex, 403, "Forbidden");
                return;
            }
            String path = ex.getRequestURI().getPath();
            String entryToken = "/".equals(path) ? queryParams(ex.getRequestURI().getRawQuery()).get("t") : null;
            if (entryToken != null && matchesToken(entryToken)) {
                ex.getResponseHeaders().add("Set-Cookie", sessionCookie() + "; Path=/; HttpOnly; SameSite=Strict");
                ex.getResponseHeaders().set("Location", "/");
                ex.sendResponseHeaders(302, -1);
                ex.close();
                return;
            }
            if (!hasSession(ex)) {
                sendText(ex, 401, "Bitte die App über ihr Startprogramm öffnen.");
                return;
            }
            boolean reading = "GET".equals(ex.getRequestMethod());
            String origin = ex.getRequestHeaders().getFirst("Origin");
            if (!reading && origin != null && !origin.equals(baseUrl())) {
                sendText(ex, 403, "Forbidden");
                return;
            }
            if (path.equals("/api/events")) {
                events.add(ex);
            } else if (path.startsWith("/api/")) {
                api(ex, path);
            } else if (reading && path.startsWith("/img/")) {
                image(ex, path.substring("/img/".length()));
            } else if (reading) {
                staticFile(ex, path);
            } else {
                sendText(ex, 405, "Method Not Allowed");
            }
        } catch (ApiException e) {
            sendError(ex, e.status(), e.getMessage());
        } catch (IOException e) {
            // Meist: Fenster während der Antwort geschlossen
            ex.close();
        } catch (Exception e) {
            e.printStackTrace();
            sendError(ex, 500, WebContext.message(e));
        }
    }

    private boolean matchesToken(String candidate) {
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
    }

    private boolean hasSession(HttpExchange ex) {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers == null) {
            return false;
        }
        for (String header : headers) {
            for (String part : header.split(";")) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length == 2 && pair[0].equals(cookieName) && matchesToken(pair[1])) {
                    return true;
                }
            }
        }
        return false;
    }

    private void api(HttpExchange ex, String path) throws Exception {
        List<String> segments = new ArrayList<>();
        for (String s : path.substring("/api/".length()).split("/")) {
            if (!s.isEmpty()) {
                segments.add(s);
            }
        }
        byte[] body;
        try (InputStream in = ex.getRequestBody()) {
            body = in.readNBytes(MAX_BODY + 1);
        }
        if (body.length > MAX_BODY) {
            throw new ApiException(413, "Anfrage zu groß");
        }
        Object result;
        try {
            result = routes.handle(ex.getRequestMethod(), segments, queryParams(ex.getRequestURI().getRawQuery()), body);
        } catch (IOException e) {
            // Fehler beim Abruf externer Daten: Meldung an die Oberfläche statt abgebrochener Verbindung.
            throw new ApiException(502, WebContext.message(e));
        }
        byte[] bytes = result instanceof byte[] raw ? raw : Json.MAPPER.writeValueAsBytes(result);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, 200, "application/json; charset=utf-8", bytes);
    }

    private static Map<String, String> queryParams(String raw) {
        Map<String, String> params = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return params;
        }
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            try {
                params.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        kv.length == 2 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            } catch (IllegalArgumentException e) {
                throw new ApiException(400, "Ungültige Anfrage");
            }
        }
        return params;
    }

    private void image(HttpExchange ex, String path) throws IOException {
        ImageRef ref = imageRef(path);
        if (ref == null) {
            sendText(ex, 404, "Not Found");
            return;
        }
        Path file;
        try {
            file = ctx.image(ref);
        } catch (IOException e) {
            // Ohne Netz bleiben nicht gecachte Bilder leer (die Oberfläche blendet sie aus).
            sendText(ex, 404, "Not Found");
            return;
        }
        ex.getResponseHeaders().set("Content-Type", path.endsWith(".jpg") ? "image/jpeg"
                : path.endsWith(".webp") ? "image/webp" : "image/png");
        // URLs enthalten Patch bzw. Dateinamen und ändern sich nicht: der Browser muss nie nachfragen.
        ex.getResponseHeaders().set("Cache-Control", "private, max-age=31536000, immutable");
        long size = Files.size(file);
        ex.sendResponseHeaders(200, size == 0 ? -1 : size);
        try (OutputStream out = ex.getResponseBody()) {
            Files.copy(file, out);
        }
    }

    /** Übersetzt den Bildpfad in eine Data-Dragon-Referenz; nur bekannte Formen sind erlaubt. */
    private static ImageRef imageRef(String path) {
        if (path.contains("..")) {
            return null;
        }
        if (path.startsWith("rune/")) {
            String icon = path.substring("rune/".length());
            return RUNE_ICON.matcher(icon).matches() ? ImageRef.rune(icon) : null;
        }
        if (path.startsWith("splash/")) {
            String file = path.substring("splash/".length());
            return SPLASH.matcher(file).matches()
                    ? new ImageRef(DDragonClient.BASE_URL + "/cdn/img/champion/splash/" + file, FileCache.GLOBAL, "img/splash/" + file)
                    : null;
        }
        String[] parts = path.split("/");
        if (parts.length != 3 || !PATCH.matcher(parts[0]).matches() || !IMAGE_GROUPS.contains(parts[1])
                || !IMAGE_FILE.matcher(parts[2]).matches()) {
            return null;
        }
        return switch (parts[1]) {
            case "champion" -> ImageRef.champion(parts[0], parts[2]);
            case "item" -> ImageRef.item(parts[0], parts[2]);
            case "spell" -> ImageRef.spell(parts[0], parts[2]);
            case "profileicon" -> new ImageRef(DDragonClient.BASE_URL + "/cdn/" + parts[0] + "/img/profileicon/" + parts[2],
                    parts[0], "img/profileicon/" + parts[2]);
            default -> ImageRef.passive(parts[0], parts[2]);
        };
    }

    private void staticFile(HttpExchange ex, String path) throws IOException {
        String resource;
        if (path.equals("/") || path.equals("/index.html")) {
            resource = RESOURCE_ROOT + "/index.html";
        } else if (path.equals("/vendor/htm-preact.js")) {
            resource = PREACT_RESOURCE;
        } else if (STATIC_PATH.matcher(path).matches()) {
            resource = RESOURCE_ROOT + path;
        } else {
            sendText(ex, 404, "Not Found");
            return;
        }
        byte[] bytes = resource(resource);
        if (bytes == null) {
            sendText(ex, 404, "Not Found");
            return;
        }
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("Content-Security-Policy", CSP);
        send(ex, 200, contentType(resource), bytes);
    }

    private synchronized byte[] resource(String name) throws IOException {
        byte[] cached = resources.get(name);
        if (cached != null) {
            return cached;
        }
        try (InputStream in = WebServer.class.getResourceAsStream(name)) {
            if (in == null) {
                return null;
            }
            byte[] bytes = in.readAllBytes();
            resources.put(name, bytes);
            return bytes;
        }
    }

    private static String contentType(String resource) {
        String name = resource.toLowerCase(Locale.ROOT);
        if (name.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (name.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (name.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "text/javascript; charset=utf-8";
    }

    private void sendError(HttpExchange ex, int status, String message) {
        try {
            send(ex, status, "application/json; charset=utf-8", Json.MAPPER.writeValueAsBytes(Map.of("error", message)));
        } catch (IOException e) {
            ex.close();
        }
    }

    private void sendText(HttpExchange ex, int status, String text) throws IOException {
        send(ex, status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        events.close();
        server.stop(0);
        executor.shutdownNow();
    }
}
