package com.leaguetool.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Server-Sent Events an die offenen Fenster. Alle Schreibzugriffe laufen über einen Thread. Die
 * regelmäßigen Pings erkennen außerdem, dass das letzte Fenster geschlossen wurde.
 */
final class EventHub implements AutoCloseable {

    private static final long PING_SECONDS = 2;
    /** So lange darf kein Fenster verbunden sein (z. B. Neuladen der Seite), bevor die App endet. */
    private static final long IDLE_NANOS = TimeUnit.SECONDS.toNanos(6);
    /** So lange darf das Fenster beim Start brauchen, um sich zu verbinden. */
    private static final long STARTUP_NANOS = TimeUnit.SECONDS.toNanos(120);
    /** Längere Lücken zwischen zwei Pings bedeuten Energiesparen: Fenster bekommen Zeit, neu zu verbinden. */
    private static final long SLEEP_GAP_NANOS = TimeUnit.SECONDS.toNanos(20);

    private final ObjectMapper json;
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lt-events");
        t.setDaemon(true);
        return t;
    });
    private final List<Client> clients = new ArrayList<>();

    private volatile Runnable onLastClientGone;
    private boolean everConnected;
    private long lastActive = System.nanoTime();
    private long lastTick = System.nanoTime();

    EventHub(ObjectMapper json) {
        this.json = json;
        writer.scheduleWithFixedDelay(this::tick, PING_SECONDS, PING_SECONDS, TimeUnit.SECONDS);
    }

    /** Ab jetzt beendet sich die App, sobald kein Fenster mehr verbunden ist. */
    void exitWhenClosed(Runnable action) {
        onLastClientGone = action;
    }

    void add(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        writer.execute(() -> {
            Client client = new Client(exchange, out);
            if (client.send(": verbunden\n\n")) {
                clients.add(client);
                everConnected = true;
                lastActive = System.nanoTime();
            }
        });
    }

    void publish(String event, Object data) {
        String payload;
        try {
            payload = "event: " + event + "\ndata: " + json.writeValueAsString(data) + "\n\n";
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Ereignis nicht serialisierbar: " + event, e);
        }
        if (!writer.isShutdown()) {
            writer.execute(() -> broadcast(payload));
        }
    }

    private void broadcast(String payload) {
        clients.removeIf(client -> !client.send(payload));
    }

    private void tick() {
        try {
            long now = System.nanoTime();
            boolean slept = now - lastTick > SLEEP_GAP_NANOS;
            lastTick = now;
            broadcast(": ping\n\n");
            if (slept || !clients.isEmpty()) {
                lastActive = now;
                return;
            }
            Runnable exit = onLastClientGone;
            if (exit != null && now - lastActive > (everConnected ? IDLE_NANOS : STARTUP_NANOS)) {
                exit.run();
            }
        } catch (RuntimeException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void close() {
        writer.shutdownNow();
    }

    private record Client(HttpExchange exchange, OutputStream out) {

        boolean send(String text) {
            try {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.flush();
                return true;
            } catch (IOException e) {
                exchange.close();
                return false;
            }
        }
    }
}
