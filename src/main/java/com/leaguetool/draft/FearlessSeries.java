package com.leaguetool.draft;

import com.leaguetool.draft.DraftArchive.Draft;
import com.leaguetool.draft.RolePredictor.Slot;
import com.leaguetool.model.Role;

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
 * @param maxGames Höchstzahl der Spiele: bei Bo3/Bo5 aus dem Format, bei „Offen“ beim Start gewählt (1 bis
 *                 {@link #MAX_GAMES}, sonst {@link #MAX_GAMES}); nie kleiner als die Zahl der gespielten Spiele
 * @param endedAt  {@code null}, solange die Serie läuft
 * @param games    gespielte Spiele in Reihenfolge, Nummern ab 1 lückenlos
 */
public record FearlessSeries(String id, String label, Format format, int maxGames, Instant startedAt, Instant endedAt,
                             List<Game> games) {

    public static final int MAX_LABEL_LENGTH = 60;
    /** Obergrenze auch für „Offen“: 10 Spiele sperren schon 100 Champions. */
    public static final int MAX_GAMES = 10;
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /** Länge der Serie; {@code OPEN} endet von Hand oder nach der beim Start gewählten Spielzahl (Standard {@link #MAX_GAMES}). */
    public enum Format {
        BO3(3), BO5(5), OPEN(0);

        private final int maxGames;

        Format(int maxGames) {
            this.maxGames = maxGames;
        }

        /** Standard-Höchstzahl der Spiele, bei {@code OPEN} {@link #MAX_GAMES} (je Serie wählbar, siehe {@link #gameLimit}). */
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

    /**
     * Ein abgeschlossenes Spiel der Serie. {@code result} ist {@code null}, wenn kein Ergebnis eingetragen wurde.
     *
     * @param shownRoles Champion → Rolle, die das Brett beim Abschließen automatisch zeigte (keine feste Rolle; nur zum
     *                   Ordnen Top bis Support, siehe {@link RoleOrder})
     */
    public record Game(int number, Instant playedAt, String patch, Draft draft, Result result, Map<Integer, Role> shownRoles) {

        public Game {
            patch = patch == null ? "" : patch;
            shownRoles = shownRoles == null ? Map.of() : Map.copyOf(shownRoles);
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
        maxGames = Math.max(gameLimit(format, maxGames), games.size());
    }

    /** Serie mit der Standard-Spielzahl des Formats ({@link Format#maxGames()}). */
    public FearlessSeries(String id, String label, Format format, Instant startedAt, Instant endedAt, List<Game> games) {
        this(id, label, format, 0, startedAt, endedAt, games);
    }

    /**
     * Gültige Spielzahl: bei Bo3/Bo5 immer die des Formats, bei „Offen“ {@code requested}, wenn es zwischen 1 und
     * {@link #MAX_GAMES} liegt, sonst {@link #MAX_GAMES}.
     */
    public static int gameLimit(Format format, int requested) {
        return format == Format.OPEN && requested >= 1 && requested <= MAX_GAMES ? requested : format.maxGames();
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
        return games.size() >= maxGames || needed > 0 && (wins() >= needed || losses() >= needed);
    }

    /**
     * Die Serie mit einem weiteren Spiel.
     *
     * @throws IllegalArgumentException wenn der Draft unvollständig ist oder gesperrte Champions enthält
     * @throws IllegalStateException    wenn die Serie schon beendet oder entschieden ist
     */
    public FearlessSeries withGame(Draft draft, Result result, String patch, Instant playedAt) {
        return withGame(draft, result, patch, playedAt, Map.of());
    }

    /** @param shownRoles angezeigte Auto-Rollen beim Abschließen, siehe {@link Game} */
    public FearlessSeries withGame(Draft draft, Result result, String patch, Instant playedAt, Map<Integer, Role> shownRoles) {
        if (!active() || decided()) {
            throw new IllegalStateException("Die Serie ist bereits beendet.");
        }
        return appended(draft, result, patch, playedAt, shownRoles);
    }

    /** Ob alle Spiele gespielt sind ({@link #maxGames}), auch bei einer beendeten Serie. */
    public boolean full() {
        return games.size() >= maxGames;
    }

    /**
     * Die Serie mit einem nachgetragenen Spiel ohne Ergebnis am Ende, z. B. nach einem versehentlich entfernten. Anders als
     * {@link #withGame} auch für beendete oder entschiedene Serien; Grenze ist nur die Spielzahl des Formats.
     *
     * @throws IllegalArgumentException wenn der Draft unvollständig ist oder gesperrte Champions enthält
     * @throws IllegalStateException    wenn die Serie schon alle Spiele hat
     */
    public FearlessSeries withAddedGame(Draft draft, String patch, Instant playedAt, Map<Integer, Role> shownRoles) {
        if (full()) {
            throw new IllegalStateException("Die Serie hat schon alle " + maxGames + " Games.");
        }
        return appended(draft, null, patch, playedAt, shownRoles);
    }

    private FearlessSeries appended(Draft draft, Result result, String patch, Instant playedAt, Map<Integer, Role> shownRoles) {
        if (!draft.complete()) {
            throw new IllegalArgumentException("Nur vollständige Drafts zählen als Game.");
        }
        Game game = new Game(nextGame(), playedAt, patch, draft, result, shownRoles);
        Set<Integer> locked = locked().keySet();
        if (game.picks().stream().anyMatch(locked::contains)) {
            throw new IllegalArgumentException("Der Draft enthält in dieser Serie gesperrte Champions.");
        }
        List<Game> updated = new ArrayList<>(games);
        updated.add(game);
        return new FearlessSeries(id, label, format, maxGames, startedAt, endedAt, updated);
    }

    /** Die Serie ohne ihr letztes Spiel, wieder laufend. */
    public FearlessSeries withoutLastGame() {
        if (games.isEmpty()) {
            throw new IllegalStateException("Die Serie hat noch kein Game.");
        }
        return new FearlessSeries(id, label, format, maxGames, startedAt, null, games.subList(0, games.size() - 1));
    }

    /**
     * Die Serie ohne Spiel {@code number} (z. B. falsch zugeordnet), die übrigen neu durchnummeriert ab 1. Ob sie läuft,
     * bleibt unverändert.
     *
     * @throws IllegalArgumentException wenn es das Spiel nicht gibt
     */
    public FearlessSeries withoutGame(int number) {
        if (number < 1 || number > games.size()) {
            throw new IllegalArgumentException("Game " + number + " gibt es in dieser Serie nicht.");
        }
        List<Game> rest = new ArrayList<>(games);
        rest.remove(number - 1);
        return new FearlessSeries(id, label, format, maxGames, startedAt, endedAt, renumbered(rest));
    }

    /**
     * Die Serie mit geänderten festen Rollen in Spiel {@code number}; Champions, Ergebnis und Sperren bleiben. Angezeigte
     * Auto-Rollen fallen für nun fest belegte Champions weg.
     *
     * @throws IllegalArgumentException wenn es das Spiel nicht gibt oder sich Champions unterscheiden
     */
    public FearlessSeries withGameRoles(int number, List<Slot> ally, List<Slot> enemy) {
        if (number < 1 || number > games.size()) {
            throw new IllegalArgumentException("Game " + number + " gibt es in dieser Serie nicht.");
        }
        Game game = games.get(number - 1);
        Draft draft = DraftArchive.withRoles(game.draft(), ally, enemy);
        Map<Integer, Role> shown = new LinkedHashMap<>(game.shownRoles());
        java.util.stream.Stream.concat(draft.ally().stream(), draft.enemy().stream())
                .filter(slot -> slot.forcedRole() != null).forEach(slot -> shown.remove(slot.champion()));
        List<Game> updated = new ArrayList<>(games);
        updated.set(number - 1, new Game(game.number(), game.playedAt(), game.patch(), draft, game.result(), shown));
        return new FearlessSeries(id, label, format, maxGames, startedAt, endedAt, updated);
    }

    /**
     * Die Serie mit geändertem Draft in Spiel {@code number} (z. B. falsch eingetragener Pick, nachträglich korrigiert);
     * Nummer, Zeitpunkt, Patch und Ergebnis bleiben. Hard Fearless gilt über alle anderen Spiele der Serie, davor wie
     * danach: Kein Champion darf in zwei Spielen vorkommen, sonst stimmten deren Sperren nicht mehr.
     *
     * @throws IllegalArgumentException wenn es das Spiel nicht gibt, der Draft unvollständig ist oder einen Champion
     *                                  aus einem anderen Spiel der Serie enthält
     */
    public FearlessSeries withGameDraft(int number, Draft draft, Map<Integer, Role> shownRoles) {
        if (number < 1 || number > games.size()) {
            throw new IllegalArgumentException("Game " + number + " gibt es in dieser Serie nicht.");
        }
        if (!draft.complete()) {
            throw new IllegalArgumentException("Nur vollständige Drafts zählen als Game.");
        }
        Game game = games.get(number - 1);
        Game changed = new Game(number, game.playedAt(), game.patch(), draft, game.result(), shownRoles);
        for (Game other : games) {
            if (other.number() != number) {
                for (int champion : changed.picks()) {
                    if (other.picks().contains(champion)) {
                        throw new IllegalArgumentException("Ein Champion des Drafts wurde schon in Game " + other.number()
                                + " dieser Serie gespielt.");
                    }
                }
            }
        }
        List<Game> updated = new ArrayList<>(games);
        updated.set(number - 1, changed);
        return new FearlessSeries(id, label, format, maxGames, startedAt, endedAt, updated);
    }

    /**
     * Die Serie mit geändertem Ergebnis in Spiel {@code number} ({@code null} = kein Ergebnis); ob sie läuft, bleibt hier
     * unverändert (das entscheidet {@link FearlessStore#updateGameResult}).
     *
     * @throws IllegalArgumentException wenn es das Spiel nicht gibt
     */
    public FearlessSeries withGameResult(int number, Result result) {
        if (number < 1 || number > games.size()) {
            throw new IllegalArgumentException("Game " + number + " gibt es in dieser Serie nicht.");
        }
        Game game = games.get(number - 1);
        List<Game> updated = new ArrayList<>(games);
        updated.set(number - 1, new Game(game.number(), game.playedAt(), game.patch(), game.draft(), result, game.shownRoles()));
        return new FearlessSeries(id, label, format, maxGames, startedAt, endedAt, updated);
    }

    private static List<Game> renumbered(List<Game> list) {
        List<Game> result = new ArrayList<>();
        for (Game g : list) {
            result.add(new Game(result.size() + 1, g.playedAt(), g.patch(), g.draft(), g.result(), g.shownRoles()));
        }
        return result;
    }

    /**
     * Die Serie mit neu geordneten Spielen, durchnummeriert ab 1; Ergebnis, Draft und Zeitpunkt bleiben beim Spiel. Für
     * Hard Fearless ist jede Reihenfolge gültig, weil kein Champion in zwei Spielen vorkommt; die Sperren je Spiel
     * ({@link #lockedBefore}) folgen der neuen Reihenfolge.
     *
     * @param order bisherige Spielnummern in der neuen Reihenfolge
     * @throws IllegalArgumentException wenn {@code order} nicht jedes Spiel genau einmal enthält
     */
    public FearlessSeries withOrder(List<Integer> order) {
        Set<Integer> expected = new java.util.HashSet<>();
        games.forEach(g -> expected.add(g.number()));
        if (order.size() != games.size() || !expected.equals(new java.util.HashSet<>(order))) {
            throw new IllegalArgumentException("Die Reihenfolge muss jedes Game genau einmal enthalten.");
        }
        List<Game> reordered = new ArrayList<>();
        for (int number : order) {
            reordered.add(games.stream().filter(g -> g.number() == number).findFirst().orElseThrow());
        }
        return new FearlessSeries(id, label, format, maxGames, startedAt, endedAt, renumbered(reordered));
    }

    /**
     * Die offene Serie mit geänderter Spielzahl; ob sie läuft, bleibt hier unverändert (das entscheidet
     * {@link FearlessStore#setMaxGames}).
     *
     * @throws IllegalArgumentException bei Bo3/Bo5, außerhalb 1 bis {@link #MAX_GAMES} oder weniger als gespielt
     */
    public FearlessSeries withMaxGames(int newMaxGames) {
        if (format != Format.OPEN) {
            throw new IllegalArgumentException("Die Anzahl Games lässt sich nur bei offenen Serien ändern.");
        }
        if (newMaxGames < 1 || newMaxGames > MAX_GAMES) {
            throw new IllegalArgumentException("Die Anzahl Games muss zwischen 1 und " + MAX_GAMES + " liegen.");
        }
        if (newMaxGames < games.size()) {
            throw new IllegalArgumentException("Die Serie hat schon " + games.size() + " Games.");
        }
        return new FearlessSeries(id, label, format, newMaxGames, startedAt, endedAt, games);
    }

    public FearlessSeries withLabel(String newLabel) {
        return new FearlessSeries(id, cleanLabel(newLabel), format, maxGames, startedAt, endedAt, games);
    }

    public FearlessSeries endedAt(Instant time) {
        return new FearlessSeries(id, label, format, maxGames, startedAt, time, games);
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
