package com.leaguetool.draft;

import com.leaguetool.data.lolalytics.LolalyticsBuild;
import com.leaguetool.data.lolalytics.LolalyticsClient;
import com.leaguetool.data.lolalytics.LolalyticsFullTierList;
import com.leaguetool.data.lolalytics.LolalyticsPairs;
import com.leaguetool.data.lolalytics.LolalyticsPeriod;
import com.leaguetool.draft.DraftDataset.Pick;
import com.leaguetool.draft.DraftDataset.Stat;
import com.leaguetool.draft.RolePredictor.Prediction;
import com.leaguetool.draft.RolePredictor.Slot;
import com.leaguetool.model.Role;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

/**
 * Lädt die Daten eines Drafts. Dank der Paarungen auf den Seiten der gewählten Champions braucht jeder Pick nur eigene
 * Abrufe; die übrigen Champions werden aus diesen Daten bewertet.
 *
 * <p>Matchups und Synergien stammen immer aus den letzten 30 Tagen, weil der einzelne Patch dafür zu wenige Spiele hat.
 * Nur die Champion-Winrates folgen dem gewählten Zeitraum. Fehlt die Seite eines Picks, fällt nur dessen Beitrag weg
 * und die Antwort enthält einen Hinweis.
 */
public final class DraftDataLoader {

    /** Alle Duos von fünf Champions sind schon mit den Daten von vier abgedeckt. */
    static final int MAX_TEAMMATE_REQUESTS_PER_TEAM = 4;
    /** Darunter hat Lolalytics für diese Rolle keine brauchbare Seite. */
    private static final int MIN_GAMES_FOR_REQUEST = 50;
    private static final long FAILURE_PAUSE_NANOS = Duration.ofMinutes(10).toNanos();
    /**
     * 30-Tage-Daten (Tierlisten, Matchups, Synergien) dürfen veraltet sein: Ein vorhandener Eintrag wird sofort genutzt
     * und im Hintergrund erneuert. Ein paar Stunden mehr verschieben ein 30-Tage-Fenster kaum. Die Tierlisten des
     * aktuellen Patches werden nach einem Patchwechsel dagegen immer frisch geholt.
     */
    private static final boolean STALE_OK = true;

    /**
     * @param forEnemy Vorschläge gelten dem Gegner (sonst dem eigenen Team)
     * @param quick    nur abrufen, was die Reihenfolge der Vorschläge bestimmt: Team-Werte des vorschlagenden Teams und
     *                 Seiten seiner Gegner. Die übrigen Daten (Seiten des eigenen Teams, Synergien der Gegner) zählen nur
     *                 für die Gesamtanalyse; sie kommen hier nur aus dem Cache, sonst zählt {@link Loaded#deferred}.
     */
    public record Request(List<Slot> ally, List<Slot> enemy, String tier, LolalyticsPeriod period, boolean forEnemy,
                          boolean quick) {

        public Request(List<Slot> ally, List<Slot> enemy, String tier, LolalyticsPeriod period) {
            this(ally, enemy, tier, period, false, false);
        }
    }

    /**
     * @param builds        Seite je Pick, soweit sie geladen werden konnte
     * @param analysedPicks analysierte Picks des Rang-Bereichs in den letzten 30 Tagen
     * @param deferred      im schnellen Modus zurückgestellte Abrufe; 0 heißt vollständig
     */
    public record Loaded(DraftDataset dataset, String patch, long analysedPicks, Prediction allyRoles, Prediction enemyRoles,
                         Map<Pick, LolalyticsBuild> builds, List<String> warnings, int deferred) {
    }

    /** Eine neuere Anfrage hat diese ersetzt; weitere Abrufe dafür wären verschwendet. */
    public static final class SupersededException extends InterruptedIOException {

        SupersededException() {
            super("Durch eine neuere Anfrage ersetzt");
        }
    }

    @FunctionalInterface
    private interface Fetch<T> {
        T get() throws IOException;
    }

    private record Failure(long untilNanos, String message) {
    }

    /** Merkt sich Fehlschläge: Eine gesperrte oder geänderte Quelle soll nicht bei jedem Klick erneut angefragt werden. */
    private final Map<String, Failure> failures = new ConcurrentHashMap<>();

    /**
     * @param names    Anzeigename zu einem Champion-Key
     * @param progress   erledigte und gesamte Schritte
     * @param superseded meldet, dass das Ergebnis nicht mehr gebraucht wird
     * @throws SupersededException wenn {@code superseded} vor einem Abruf zutrifft
     */
    public Loaded load(LolalyticsClient client, Request request, IntFunction<String> names,
                       BiConsumer<Integer, Integer> progress, BooleanSupplier superseded) throws IOException {
        boolean patchOnly = request.period() == LolalyticsPeriod.CURRENT_PATCH;
        int listSteps = Role.values().length * (patchOnly ? 2 : 1);
        Progress steps = new Progress(progress, listSteps + requests(request.ally()) + requests(request.enemy()));

        Map<Pick, Stat> full = new HashMap<>();
        Map<Pick, Stat> selected = new HashMap<>();
        Map<Integer, String> slugs = new HashMap<>();
        double fullAverage = 0;
        double selectedAverage = 0;
        long analysedPicks = 0;
        String patch = "";
        for (Role role : Role.values()) {
            String lane = LolalyticsClient.lane(role);
            requireCurrent(superseded);
            LolalyticsFullTierList list = client.fullTierList(request.tier(), lane, LolalyticsPeriod.LAST_30_DAYS, STALE_OK);
            steps.advance();
            addRows(list, role, full);
            slugs.putAll(list.slugs());
            fullAverage = list.avgWinRate();
            analysedPicks = list.analysed();
            patch = list.patch();
            if (patchOnly) {
                requireCurrent(superseded);
                LolalyticsFullTierList current = client.fullTierList(request.tier(), lane, LolalyticsPeriod.CURRENT_PATCH);
                steps.advance();
                addRows(current, role, selected);
                selectedAverage = current.avgWinRate();
            }
        }
        if (!patchOnly) {
            selected = full;
            selectedAverage = fullAverage;
        }

        Map<Pick, Stat> games = full;
        Prediction allyRoles = RolePredictor.predict(request.ally(), p -> games.getOrDefault(p, Stat.NONE).games());
        Prediction enemyRoles = RolePredictor.predict(request.enemy(), p -> games.getOrDefault(p, Stat.NONE).games());

        TeamLoad load = new TeamLoad(client, request.tier(), request.quick(), full, slugs, names, steps, superseded);
        // Erst jetzt steht fest, welche Picks wirklich Abrufe brauchen.
        steps.setTotal(listSteps + load.plannedSteps(request.ally(), allyRoles, !request.forEnemy())
                + load.plannedSteps(request.enemy(), enemyRoles, request.forEnemy()));
        load.team(request.ally(), allyRoles, !request.forEnemy());
        load.team(request.enemy(), enemyRoles, request.forEnemy());
        DraftDataset dataset = new DraftDataset(selectedAverage, fullAverage, selected, full, load.matchups, load.synergies);
        return new Loaded(dataset, patch, analysedPicks, allyRoles, enemyRoles, load.builds, load.warnings, load.deferred);
    }

    private static int requests(List<Slot> team) {
        return team.size() + Math.min(team.size(), MAX_TEAMMATE_REQUESTS_PER_TEAM);
    }

    private static void requireCurrent(BooleanSupplier superseded) throws SupersededException {
        if (superseded.getAsBoolean()) {
            throw new SupersededException();
        }
    }

    private static void addRows(LolalyticsFullTierList list, Role role, Map<Pick, Stat> target) {
        String lane = LolalyticsClient.lane(role);
        list.rows().forEach((champion, row) -> {
            if (lane.equals(row.lane()) && row.games() > 0) {
                target.put(new Pick(champion, role), new Stat(row.winRate() * row.games(), row.games()));
            }
        });
    }

    private static Map<Pick, Stat> stats(LolalyticsPairs pairs) {
        Map<Pick, Stat> stats = new HashMap<>();
        for (Role role : Role.values()) {
            pairs.byLane().getOrDefault(LolalyticsClient.lane(role), Map.of()).forEach((champion, stat) ->
                    stats.put(new Pick(champion, role), new Stat(stat.winRate() * stat.games(), stat.games())));
        }
        return stats;
    }

    private static final class Progress {

        private final BiConsumer<Integer, Integer> listener;
        private int total;
        private int done;

        Progress(BiConsumer<Integer, Integer> listener, int total) {
            this.listener = listener;
            this.total = total;
        }

        void setTotal(int total) {
            this.total = total;
        }

        void advance() {
            listener.accept(++done, total);
        }
    }

    private final class TeamLoad {

        final Map<Pick, Map<Pick, Stat>> matchups = new HashMap<>();
        final Map<Pick, Map<Pick, Stat>> synergies = new HashMap<>();
        final Map<Pick, LolalyticsBuild> builds = new HashMap<>();
        final List<String> warnings = new ArrayList<>();
        int deferred;
        private final LolalyticsClient client;
        private final String tier;
        private final boolean quick;
        private final Map<Pick, Stat> full;
        private final Map<Integer, String> slugs;
        private final IntFunction<String> names;
        private final Progress steps;
        private final BooleanSupplier superseded;

        TeamLoad(LolalyticsClient client, String tier, boolean quick, Map<Pick, Stat> full, Map<Integer, String> slugs,
                 IntFunction<String> names, Progress steps, BooleanSupplier superseded) {
            this.client = client;
            this.tier = tier;
            this.quick = quick;
            this.full = full;
            this.slugs = slugs;
            this.names = names;
            this.steps = steps;
            this.superseded = superseded;
        }

        /**
         * Für die Vorschläge zählen nur die Team-Werte des vorschlagenden Teams (Synergien mit dem Kandidaten) und die
         * Seiten der Gegner (Matchups gegen ihn). Alles andere ist im schnellen Modus zurückgestellt.
         */
        private boolean deferBuild(boolean suggesting) {
            return quick && suggesting;
        }

        private boolean deferTeammates(boolean suggesting) {
            return quick && !suggesting;
        }

        /** Schritte mit Abruf oder Hinweis; zurückgestellte Abrufe zählen nicht. */
        int plannedSteps(List<Slot> team, Prediction prediction, boolean suggesting) {
            int planned = 0;
            int teammateRequests = 0;
            for (Slot slot : team) {
                Pick pick = pickOf(slot, prediction);
                if (!available(pick)) {
                    planned++;
                    continue;
                }
                planned += deferBuild(suggesting) ? 0 : 1;
                if (teammateRequests < MAX_TEAMMATE_REQUESTS_PER_TEAM) {
                    teammateRequests++;
                    planned += deferTeammates(suggesting) ? 0 : 1;
                }
            }
            return planned;
        }

        /** Das Limit zählt nur echte Abrufe: Ein übersprungener Pick darf dem fünften nicht die Synergien nehmen. */
        void team(List<Slot> team, Prediction prediction, boolean suggesting) throws IOException {
            int teammateRequests = 0;
            for (Slot slot : team) {
                Pick pick = pickOf(slot, prediction);
                boolean withTeammates = available(pick) && teammateRequests < MAX_TEAMMATE_REQUESTS_PER_TEAM;
                if (withTeammates) {
                    teammateRequests++;
                }
                pick(pick, withTeammates, suggesting);
            }
        }

        private static Pick pickOf(Slot slot, Prediction prediction) {
            return new Pick(slot.champion(), prediction.roles().get(slot.champion()));
        }

        private boolean available(Pick pick) {
            return slugs.containsKey(pick.champion()) && full.getOrDefault(pick, Stat.NONE).games() >= MIN_GAMES_FOR_REQUEST;
        }

        private void pick(Pick pick, boolean withTeammates, boolean suggesting) throws IOException {
            String label = names.apply(pick.champion()) + " (" + pick.role().label() + ")";
            if (!available(pick)) {
                warnings.add(label + ": zu wenige Spiele in diesem Rang-Bereich, Matchups und Synergien fehlen.");
                steps.advance();
                return;
            }
            String slug = slugs.get(pick.champion());
            String lane = LolalyticsClient.lane(pick.role());
            String id = slug + ":" + tier + ":" + lane;
            LolalyticsBuild build = attempt("build:" + id, "Matchups für " + label, deferBuild(suggesting),
                    () -> client.build(slug, tier, lane, LolalyticsPeriod.LAST_30_DAYS, STALE_OK),
                    () -> client.buildIfCached(slug, tier, lane, LolalyticsPeriod.LAST_30_DAYS, STALE_OK));
            if (build != null) {
                builds.put(pick, build);
                matchups.put(pick, stats(build.matchups()));
            }
            if (withTeammates) {
                LolalyticsPairs teammates = attempt("team:" + id, "Synergien für " + label, deferTeammates(suggesting),
                        () -> client.teammates(slug, tier, lane, STALE_OK),
                        () -> client.teammatesIfCached(slug, tier, lane, STALE_OK));
                if (teammates != null) {
                    synergies.put(pick, stats(teammates));
                }
            }
        }

        /**
         * @param defer  nur aus dem Cache lesen; fehlt der Eintrag, zählt der Abruf als zurückgestellt
         * @param cached Cache-Zugriff ohne Abruf, {@code null} ohne gültigen Eintrag
         */
        private <T> T attempt(String id, String label, boolean defer, Fetch<T> fetch, Fetch<T> cached) throws IOException {
            Failure failure = failures.get(id);
            if (failure != null && failure.untilNanos() - System.nanoTime() > 0) {
                warnings.add(label + " nicht verfügbar: " + failure.message());
                if (!defer) {
                    steps.advance();
                }
                return null;
            }
            if (defer) {
                T value = null;
                try {
                    value = cached.get();
                } catch (InterruptedIOException e) {
                    throw e;
                } catch (IOException e) {
                    // Unlesbarer Eintrag: Die vollständige Anfrage holt ihn neu.
                }
                if (value == null) {
                    deferred++;
                }
                return value;
            }
            requireCurrent(superseded);
            try {
                T value = fetch.get();
                failures.remove(id);
                return value;
            } catch (InterruptedIOException e) {
                throw e;
            } catch (IOException e) {
                failures.put(id, new Failure(System.nanoTime() + FAILURE_PAUSE_NANOS, e.getMessage()));
                warnings.add(label + " nicht verfügbar: " + e.getMessage());
                return null;
            } finally {
                steps.advance();
            }
        }
    }
}
