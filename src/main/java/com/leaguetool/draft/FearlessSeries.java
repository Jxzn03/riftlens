package com.leaguetool.draft;

import com.leaguetool.draft.DraftArchive.Draft;
import com.leaguetool.draft.RolePredictor.Slot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Eine Fearless-Serie (Hard Fearless): Jeder Champion, den ein Team in einem früheren Spiel der Serie gepickt hat, ist
 * in den folgenden Spielen für beide Teams gesperrt. Bans werden nicht übertragen.
 *
 * <p>Gespeichert werden wie im Draft-Archiv nur Champion-Keys, feste Rollen und Bans vom Brett, dazu eine freie
 * Bezeichnung, die der Nutzer selbst eingibt. Sie bleibt lokal und wird nie mit Summoner-Daten verknüpft.
 *
 * @param endedAt {@code null}, solange die Serie läuft
 * @param games   gespielte Spiele in Reihenfolge, Nummern ab 1 lückenlos
 */
public record FearlessSeries(String id, String label, Format format, Instant startedAt, Instant endedAt, List<Game> games) {

    public static final int MAX_LABEL_LENGTH = 60;
    /** Obergrenze auch für „Offen“: 10 Spiele sperren schon 100 Champions. */
    public static final int MAX_GAMES = 10;
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /** Länge der Serie; {@code OPEN} endet nur von Hand (oder nach {@link #MAX_GAMES} Spielen). */
    public enum Format {
        BO3(3), BO5(5), OPEN(0);

        private final int maxGames;

        Format(int maxGames) {
            this.maxGames = maxGames;
        }

        /** Höchstzahl der Spiele, bei {@code OPEN} {@link #MAX_GAMES}. */
        public int maxGames() {
            return maxGames == 0 ? MAX_GAMES : maxGames;
        }

        /** Siege, die die Serie entscheiden; 0 bei {@code OPEN}. */
        public int winsNeeded() {
            return maxGames == 0 ? 0 : maxGames / 2 + 1;
        }
    }

    /** Ergebnis aus Sicht von „Dein Team“; optional, der League-Client liefert es nicht. */
    public enum Result { WIN, LOSS }

    /** Ein abgeschlossenes Spiel der Serie. {@code result} ist {@code null}, wenn kein Ergebnis eingetragen wurde. */
    public record Game(int number, Instant playedAt, String patch, Draft draft, Result result) {

        public Game {
            patch = patch == null ? "" : patch;
        }

        /** Die zehn Picks, zuerst dein Team. */
        public List<Integer> picks() {
            List<Integer> picks = new ArrayList<>();
            draft.ally().stream().map(Slot::champion).forEach(picks::add);
            draft.enemy().stream().map(Slot::champion).forEach(picks::add);
            return picks;
        }
    }

    public FearlessSeries {
        games = List.copyOf(games);
    }

    public boolean active() {
        return endedAt == null;
    }

    /** Nummer des nächsten Spiels. */
    public int nextGame() {
        return games.size() + 1;
    }

    /** Alle in der Serie gespielten Champions → Nummer des Spiels, in dem sie gespielt wurden. */
    public Map<Integer, Integer> locked() {
        return lockedBefore(Integer.MAX_VALUE);
    }

    /** Die Sperren, die vor Spiel {@code game} galten: alle Picks der Spiele davor. */
    public Map<Integer, Integer> lockedBefore(int game) {
        Map<Integer, Integer> locked = new LinkedHashMap<>();
        for (Game g : games) {
            if (g.number() < game) {
                g.picks().forEach(champion -> locked.putIfAbsent(champion, g.number()));
            }
        }
        return locked;
    }

    public int wins() {
        return (int) games.stream().filter(g -> g.result() == Result.WIN).count();
    }

    public int losses() {
        return (int) games.stream().filter(g -> g.result() == Result.LOSS).count();
    }

    /** Ob die Serie nach Format entschieden ist: genug Siege einer Seite oder alle Spiele gespielt. */
    public boolean decided() {
        int needed = format.winsNeeded();
        return games.size() >= format.maxGames() || needed > 0 && (wins() >= needed || losses() >= needed);
    }

    /**
     * Die Serie mit einem weiteren Spiel.
     *
     * @throws IllegalArgumentException wenn der Draft unvollständig ist oder gesperrte Champions enthält
     * @throws IllegalStateException    wenn die Serie schon beendet oder entschieden ist
     */
    public FearlessSeries withGame(Draft draft, Result result, String patch, Instant playedAt) {
        if (!active() || decided()) {
            throw new IllegalStateException("Die Serie ist bereits beendet.");
        }
        if (!draft.complete()) {
            throw new IllegalArgumentException("Nur vollständige Drafts zählen als Spiel.");
        }
        Game game = new Game(nextGame(), playedAt, patch, draft, result);
        Set<Integer> locked = locked().keySet();
        if (game.picks().stream().anyMatch(locked::contains)) {
            throw new IllegalArgumentException("Der Draft enthält in dieser Serie gesperrte Champions.");
        }
        List<Game> updated = new ArrayList<>(games);
        updated.add(game);
        return new FearlessSeries(id, label, format, startedAt, endedAt, updated);
    }

    /** Die Serie ohne ihr letztes Spiel, wieder laufend. */
    public FearlessSeries withoutLastGame() {
        if (games.isEmpty()) {
            throw new IllegalStateException("Die Serie hat noch kein Spiel.");
        }
        return new FearlessSeries(id, label, format, startedAt, null, games.subList(0, games.size() - 1));
    }

    public FearlessSeries withLabel(String newLabel) {
        return new FearlessSeries(id, cleanLabel(newLabel), format, startedAt, endedAt, games);
    }

    public FearlessSeries endedAt(Instant time) {
        return new FearlessSeries(id, label, format, startedAt, time, games);
    }

    /**
     * Bereinigte Bezeichnung: ohne Steuerzeichen, Leerraum zusammengefasst und gekürzt; {@code null}, wenn leer.
     *
     * @throws IllegalArgumentException wenn sie länger als {@link #MAX_LABEL_LENGTH} Zeichen ist
     */
    public static String cleanLabel(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = SPACES.matcher(CONTROL.matcher(raw).replaceAll(" ")).replaceAll(" ").strip();
        if (cleaned.codePointCount(0, cleaned.length()) > MAX_LABEL_LENGTH) {
            throw new IllegalArgumentException("Die Bezeichnung hat höchstens " + MAX_LABEL_LENGTH + " Zeichen.");
        }
        return cleaned.isEmpty() ? null : cleaned;
    }
}
