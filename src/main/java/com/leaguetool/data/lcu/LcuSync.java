package com.leaguetool.data.lcu;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Liest bei eingeschaltetem Abgleich regelmäßig die Champion-Auswahl des League-Clients und meldet Änderungen. Nur
 * lesend und nur über {@link LcuEndpoint}; das Tempo entspricht DraftGap (in der Auswahl jede Sekunde, sonst seltener).
 */
public final class LcuSync implements AutoCloseable {

    public enum Status {
        /** Abgleich ausgeschaltet. */
        OFF,
        /** Gerade eingeschaltet, die erste Abfrage läuft noch. */
        CONNECTING,
        /** Kein laufender League-Client gefunden. */
        NO_CLIENT,
        /** Client gefunden, aber (noch) nicht erreichbar. */
        UNREACHABLE,
        /** Verbunden, keine Champion-Auswahl. */
        IDLE,
        CHAMP_SELECT
    }

    /**
     * @param pickable Champions, die der eigene Account in dieser Auswahl spielen darf; leer, wenn unbekannt
     */
    public record State(Status status, String message, ChampSelect champSelect, List<Integer> pickable) {

        public State {
            pickable = List.copyOf(pickable);
        }

        static State of(Status status, String message) {
            return new State(status, message, null, List.of());
        }
    }

    static final long CHAMP_SELECT_DELAY_MS = 1000;
    static final long IDLE_DELAY_MS = 2000;
    static final long NO_CLIENT_DELAY_MS = 4000;

    private final Supplier<Optional<LcuLockfile>> locator;
    private final Function<LcuLockfile, LcuTransport> connect;
    private final Consumer<State> listener;
    private final boolean background;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("lt-lcu").factory());

    private volatile State state = State.of(Status.OFF, "Abgleich mit dem League-Client ist aus.");
    private boolean enabled;
    private ScheduledFuture<?> next;
    private LcuTransport transport;
    private long pickableGameId = -1;
    private List<Integer> pickable = List.of();

    /**
     * @param locator  findet den Zugang des laufenden Clients
     * @param connect  baut zu einem Zugang die Verbindung
     * @param listener erhält jeden geänderten Zustand
     */
    public LcuSync(Supplier<Optional<LcuLockfile>> locator, Function<LcuLockfile, LcuTransport> connect,
                   Consumer<State> listener) {
        this(locator, connect, listener, true);
    }

    /** @param background {@code false}: kein Abgleich im Hintergrund, nur über {@link #pollOnce()} (Tests) */
    LcuSync(Supplier<Optional<LcuLockfile>> locator, Function<LcuLockfile, LcuTransport> connect,
            Consumer<State> listener, boolean background) {
        this.locator = locator;
        this.connect = connect;
        this.listener = listener;
        this.background = background;
    }

    private void resetPickable() {
        pickableGameId = -1;
        pickable = List.of();
    }

    public State state() {
        return state;
    }

    public synchronized void setEnabled(boolean value) {
        if (value == enabled) {
            return;
        }
        enabled = value;
        if (next != null) {
            next.cancel(false);
            next = null;
        }
        if (value) {
            // Sofort als eingeschaltet melden, sonst springt der Schalter bis zur ersten Abfrage zurück.
            update(State.of(Status.CONNECTING, "Suche den League-Client …"));
            if (background) {
                next = scheduler.schedule(this::tick, 0, TimeUnit.MILLISECONDS);
            }
        } else {
            transport = null;
            resetPickable();
            update(State.of(Status.OFF, "Abgleich mit dem League-Client ist aus."));
        }
    }

    private void tick() {
        State current = pollOnce();
        synchronized (this) {
            if (enabled && background) {
                long delay = switch (current.status()) {
                    case CHAMP_SELECT -> CHAMP_SELECT_DELAY_MS;
                    case IDLE -> IDLE_DELAY_MS;
                    default -> NO_CLIENT_DELAY_MS;
                };
                next = scheduler.schedule(this::tick, delay, TimeUnit.MILLISECONDS);
            }
        }
    }

    /** Ein Abgleich: fragt den Client einmal ab und meldet den Zustand, falls er sich geändert hat. */
    synchronized State pollOnce() {
        if (!enabled) {
            return state;
        }
        update(poll());
        return state;
    }

    private State poll() {
        if (transport == null) {
            Optional<LcuLockfile> lockfile = locator.get();
            if (lockfile.isEmpty()) {
                resetPickable();
                return State.of(Status.NO_CLIENT, "Kein laufender League-Client gefunden. Liegt League in einem anderen Ordner, ihn in den Optionen eintragen.");
            }
            transport = connect.apply(lockfile.get());
        }
        try {
            Optional<JsonNode> session = transport.get(LcuEndpoint.CHAMP_SELECT_SESSION);
            if (session.isEmpty()) {
                resetPickable();
                return State.of(Status.IDLE, "Verbunden, keine Champion-Auswahl.");
            }
            ChampSelect champSelect = ChampSelect.parse(session.get());
            if (champSelect.gameId() != pickableGameId) {
                pickable = pickable();
                pickableGameId = champSelect.gameId();
            }
            return new State(Status.CHAMP_SELECT, "Champion-Auswahl läuft.", champSelect, pickable);
        } catch (IOException | RuntimeException e) {
            // Neu gestarteter Client hat einen neuen Port und ein neues Passwort: beim nächsten Mal neu suchen.
            transport = null;
            return State.of(Status.UNREACHABLE, "League-Client nicht erreichbar: " + message(e));
        }
    }

    private List<Integer> pickable() throws IOException {
        List<Integer> ids = new ArrayList<>();
        transport.get(LcuEndpoint.PICKABLE_CHAMPIONS).ifPresent(list -> list.forEach(id -> {
            if (id.asInt(0) > 0) {
                ids.add(id.asInt());
            }
        }));
        return ids;
    }

    private static String message(Exception e) {
        String text = e.getMessage();
        return text == null || text.isBlank() ? e.getClass().getSimpleName() : text;
    }

    private void update(State value) {
        if (!value.equals(state)) {
            state = value;
            listener.accept(value);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
