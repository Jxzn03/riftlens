package com.leaguetool.data.lcu;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LcuSyncTest {

    private static final LcuLockfile LOCKFILE = new LcuLockfile(50_000, "geheim");

    private Optional<LcuLockfile> found = Optional.of(LOCKFILE);
    private final AtomicInteger lookups = new AtomicInteger();
    private final FakeClient client = new FakeClient();
    private final List<LcuSync.State> published = new ArrayList<>();
    private final LcuSync sync = new LcuSync(() -> {
        lookups.incrementAndGet();
        return found;
    }, lockfile -> client, published::add, false);

    /** Antwortet wie der Client; merkt sich jede Abfrage. */
    private static final class FakeClient implements LcuTransport {
        JsonNode session;
        JsonNode pickable = ChampSelectTest.json("[103, 59, 412]");
        IOException failure;
        final List<LcuEndpoint> calls = new ArrayList<>();

        @Override
        public Optional<JsonNode> get(LcuEndpoint endpoint) throws IOException {
            calls.add(endpoint);
            if (failure != null) {
                throw failure;
            }
            return Optional.ofNullable(endpoint == LcuEndpoint.CHAMP_SELECT_SESSION ? session : pickable);
        }
    }

    @AfterEach
    void close() {
        sync.close();
    }

    /** Ohne Hintergrund-Thread: jeder Abgleich läuft synchron im Test. */
    private LcuSync.State poll() {
        return sync.pollOnce();
    }

    @Test
    void offUntilEnabledAndNoRequestsWhileOff() {
        assertEquals(LcuSync.Status.OFF, sync.state().status());
        assertEquals(LcuSync.Status.OFF, poll().status());
        assertTrue(client.calls.isEmpty());
        assertEquals(0, lookups.get());
    }

    @Test
    void followsTheClientThroughChampSelect() {
        found = Optional.empty();
        sync.setEnabled(true);
        assertEquals(LcuSync.Status.NO_CLIENT, poll().status());

        found = Optional.of(LOCKFILE);
        assertEquals(LcuSync.Status.IDLE, poll().status());

        client.session = ChampSelectTest.json(ChampSelectTest.DRAFT);
        LcuSync.State state = poll();
        assertEquals(LcuSync.Status.CHAMP_SELECT, state.status());
        assertEquals(103, state.champSelect().ally().get(1).champion());
        assertEquals(List.of(103, 59, 412), state.pickable());

        client.session = null;
        assertEquals(LcuSync.Status.IDLE, poll().status());
        assertTrue(poll().pickable().isEmpty());
    }

    @Test
    void enablingReportsConnectingRightAway() {
        sync.setEnabled(true);
        assertEquals(LcuSync.Status.CONNECTING, sync.state().status(), "Der Schalter bleibt sofort an");
        assertEquals(LcuSync.Status.CONNECTING, published.get(published.size() - 1).status());
        assertTrue(client.calls.isEmpty(), "Abgefragt wird erst im nächsten Abgleich");
    }

    @Test
    void onlyChangesArePublished() {
        sync.setEnabled(true);
        client.session = ChampSelectTest.json(ChampSelectTest.DRAFT);
        poll();
        int count = published.size();
        poll();
        poll();
        assertEquals(count, published.size(), "Gleicher Stand, keine neue Meldung");
    }

    @Test
    void pickableChampionsAreReadOncePerChampSelect() {
        sync.setEnabled(true);
        client.session = ChampSelectTest.json(ChampSelectTest.DRAFT);
        poll();
        poll();
        poll();
        assertEquals(1, client.calls.stream().filter(e -> e == LcuEndpoint.PICKABLE_CHAMPIONS).count());
    }

    @Test
    void unreachableClientIsLookedUpAgain() {
        sync.setEnabled(true);
        poll();
        client.failure = new IOException("Verbindung abgelehnt");
        LcuSync.State state = poll();
        assertEquals(LcuSync.Status.UNREACHABLE, state.status());
        assertTrue(state.message().contains("Verbindung abgelehnt"));
        assertTrue(!state.message().contains("geheim"), "Das Passwort erscheint nie in Meldungen");

        int before = lookups.get();
        client.failure = null;
        assertEquals(LcuSync.Status.IDLE, poll().status());
        assertEquals(before + 1, lookups.get(), "Neuer Start des Clients: Zugang neu lesen");
    }

    @Test
    void disablingStopsAndForgetsTheConnection() {
        sync.setEnabled(true);
        client.session = ChampSelectTest.json(ChampSelectTest.DRAFT);
        poll();
        sync.setEnabled(false);
        assertEquals(LcuSync.Status.OFF, sync.state().status());
        int calls = client.calls.size();
        poll();
        assertEquals(calls, client.calls.size());
    }

    @Test
    void onlyTheAllowedReadOnlyEndpointsAreUsed() {
        sync.setEnabled(true);
        client.session = ChampSelectTest.json(ChampSelectTest.DRAFT);
        poll();
        assertTrue(EnumSet.allOf(LcuEndpoint.class).containsAll(client.calls));
        assertEquals(List.of("/lol-champ-select/v1/session", "/lol-champ-select/v1/pickable-champion-ids"),
                EnumSet.allOf(LcuEndpoint.class).stream().map(LcuEndpoint::path).toList(),
                "Neue Endpunkte nur nach Prüfung, siehe docs/client-sync.md");
    }
}
