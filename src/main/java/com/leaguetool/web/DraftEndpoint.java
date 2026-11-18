package com.leaguetool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.leaguetool.data.GameData;
import com.leaguetool.data.lolalytics.LolalyticsBuild;
import com.leaguetool.data.lolalytics.LolalyticsClient;
import com.leaguetool.data.lolalytics.LolalyticsPeriod;
import com.leaguetool.draft.DraftArchive;
import com.leaguetool.draft.DraftArchive.Draft;
import com.leaguetool.draft.DraftAnalyzer;
import com.leaguetool.draft.DraftAnalyzer.ChampionResult;
import com.leaguetool.draft.DraftAnalyzer.PairResult;
import com.leaguetool.draft.DraftAnalyzer.Result;
import com.leaguetool.draft.DraftAnalyzer.Suggestion;
import com.leaguetool.draft.DraftDataLoader;
import com.leaguetool.draft.DraftDataset;
import com.leaguetool.draft.DraftDataset.Pick;
import com.leaguetool.draft.DraftDataset.Stat;
import com.leaguetool.draft.RolePredictor.Prediction;
import com.leaguetool.draft.RolePredictor.Slot;
import com.leaguetool.draft.TeamProfile;
import com.leaguetool.model.Champion;
import com.leaguetool.model.Role;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** Der Endpunkt {@code POST /api/draft}: bewertet den Draft und alle möglichen nächsten Picks. */
final class DraftEndpoint {

    private static final int TEAM_SIZE = 5;
    private static final int MAX_BANS = 10;
    /** Sperren einer Fearless-Serie: höchstens zehn Picks je Spiel und {@code FearlessSeries.MAX_GAMES} Spiele. */
    private static final int MAX_LOCKED = 10 * com.leaguetool.draft.FearlessSeries.MAX_GAMES;
    private static final int MAX_MIN_GAMES = 100_000;
    private static final double MAX_MIN_PICK_RATE = 0.2;
    private static final int MAX_CLIENT_ID_LENGTH = 64;

    private final WebContext ctx;
    private final DraftDataLoader loader = new DraftDataLoader();
    /** Fenster → seine neueste Anfrage. */
    private final Map<String, Integer> latestRequests = new ConcurrentHashMap<>();

    DraftEndpoint(WebContext ctx) {
        this.ctx = ctx;
    }

    Object handle(LolalyticsClient client, JsonNode body, String tier, LolalyticsPeriod period) throws IOException {
        GameData data = ctx.awaitData();
        List<Slot> ally = slots(body.path("ally"), data);
        List<Slot> enemy = slots(body.path("enemy"), data);
        Set<Integer> bans = bans(body.path("bans"), data);
        requireDistinct(ally, enemy);
        // Fearless: in früheren Spielen der Serie gespielte Champions fehlen in den Vorschlägen wie Bans.
        Set<Integer> locked = locked(body.path("locked"), data);
        Set<Integer> excluded = new HashSet<>(bans);
        excluded.addAll(locked);
        int requestId = body.path("requestId").asInt();
        boolean forEnemy = "enemy".equals(body.path("side").asText());
        // Schneller Modus: erst die Daten für die Vorschläge, das Frontend fragt danach vollständig nach.
        boolean quick = body.path("quick").asBoolean();

        DraftDataLoader.Loaded loaded;
        try {
            loaded = loader.load(client, new DraftDataLoader.Request(ally, enemy, tier, period, forEnemy, quick),
                    data::championName,
                    (done, total) -> ctx.publish("draft-progress", Dto.obj("requestId", requestId, "done", done, "total", total)),
                    registerRequest(body.path("client").asText(""), requestId));
        } catch (DraftDataLoader.SupersededException e) {
            throw new ApiException(409, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }

        // Standard sind gängige Picks mit Mindest-Pickrate; "rarePicks" schaltet auf das alte Verhalten mit absoluten
        // Mindestspielen zurück. Die automatischen Mindestspiele bleiben als Untergrenze für kleine Rang-Bereiche.
        boolean rarePicks = body.path("rarePicks").asBoolean();
        JsonNode requestedMinGames = body.path("minGames");
        int minGames = rarePicks && requestedMinGames.isNumber()
                ? Math.max(0, Math.min(MAX_MIN_GAMES, requestedMinGames.asInt()))
                : DraftAnalyzer.defaultMinGames(loaded.analysedPicks());
        JsonNode requestedPickRate = body.path("minPickRate");
        double minPickRate = rarePicks ? 0
                : requestedPickRate.isNumber() ? Math.max(0, Math.min(MAX_MIN_PICK_RATE, requestedPickRate.asDouble()))
                : DraftAnalyzer.COMMON_MIN_PICK_RATE;
        double minLaneShare = rarePicks ? 0 : DraftAnalyzer.COMMON_MIN_LANE_SHARE;
        DraftAnalyzer.Config config = new DraftAnalyzer.Config(body.path("ignoreChampionWinRates").asBoolean(), minGames,
                minPickRate, minLaneShare);

        Map<Role, Integer> allyTeam = team(loaded.allyRoles());
        Map<Role, Integer> enemyTeam = team(loaded.enemyRoles());
        DraftAnalyzer analyzer = new DraftAnalyzer(loaded.dataset(), config);
        Map<Role, Integer> suggestedTeam = forEnemy ? enemyTeam : allyTeam;
        List<Suggestion> suggestions = forEnemy
                ? analyzer.suggestions(enemyTeam, allyTeam, excluded)
                : analyzer.suggestions(allyTeam, enemyTeam, excluded);

        return Dto.obj(
                "patch", loaded.patch(),
                "side", forEnemy ? "enemy" : "ally",
                "complete", loaded.deferred() == 0,
                "minGames", minGames,
                "minPickRate", minPickRate,
                "defaultMinPickRate", DraftAnalyzer.COMMON_MIN_PICK_RATE,
                "minLaneShare", minLaneShare,
                "slots", Dto.obj("ally", slotResults(ally, loaded.allyRoles(), loaded.dataset()),
                        "enemy", slotResults(enemy, loaded.enemyRoles(), loaded.dataset())),
                "analysis", analysis(analyzer.analyze(allyTeam, enemyTeam)),
                "profiles", Dto.obj(
                        "ally", profile(allyTeam, loaded, config),
                        "enemy", profile(enemyTeam, loaded, config)),
                "openRoles", Arrays.stream(Role.values()).filter(r -> !suggestedTeam.containsKey(r)).map(Role::name).toList(),
                "suggestions", suggestions.stream().map(s -> suggestion(s, loaded.dataset())).toList(),
                "warnings", warnings(loaded.warnings(), ally, enemy, locked, data));
    }

    // ------------------------------------------------------------------ Anfrage

    /** Merkt sich die neueste Anfrage eines Fensters; ältere desselben Fensters gelten danach als überholt. */
    private BooleanSupplier registerRequest(String client, int requestId) {
        if (client.isEmpty() || client.length() > MAX_CLIENT_ID_LENGTH) {
            return () -> false;
        }
        latestRequests.put(client, requestId);
        return () -> latestRequests.get(client) != requestId;
    }

    static List<Slot> slots(JsonNode team, GameData data) {
        List<Slot> slots = new ArrayList<>();
        if (team.size() > TEAM_SIZE) {
            throw new ApiException(400, "Ein Team hat höchstens " + TEAM_SIZE + " Champions.");
        }
        for (JsonNode node : team) {
            String role = node.path("role").asText("");
            slots.add(new Slot(championKey(node.path("champion"), data), role.isEmpty() ? null : role(role)));
        }
        return slots;
    }

    /**
     * Plätze zum Überschreiben der Rollen eines gespeicherten Drafts. Ohne Abgleich mit den Spieldaten: Erlaubt sind
     * ohnehin nur die Champions, die schon im gespeicherten Draft stehen ({@code DraftArchive.withRoles}).
     */
    static List<Slot> storedSlots(JsonNode team) {
        if (!team.isArray() || team.size() > TEAM_SIZE) {
            throw new ApiException(400, "Ein Team hat höchstens " + TEAM_SIZE + " Champions.");
        }
        List<Slot> slots = new ArrayList<>();
        for (JsonNode node : team) {
            if (!node.path("champion").canConvertToInt()) {
                throw new ApiException(400, "Ungültiger Champion");
            }
            String role = node.path("role").asText("");
            slots.add(new Slot(node.path("champion").asInt(), role.isEmpty() ? null : role(role)));
        }
        return slots;
    }

    private static Set<Integer> bans(JsonNode list, GameData data) {
        if (list.size() > MAX_BANS) {
            throw new ApiException(400, "Höchstens " + MAX_BANS + " Bans.");
        }
        Set<Integer> bans = new HashSet<>();
        list.forEach(node -> bans.add(championKey(node, data)));
        return bans;
    }

    private static Set<Integer> locked(JsonNode list, GameData data) {
        if (list.size() > MAX_LOCKED) {
            throw new ApiException(400, "Höchstens " + MAX_LOCKED + " gesperrte Champions.");
        }
        Set<Integer> locked = new HashSet<>();
        list.forEach(node -> locked.add(championKey(node, data)));
        return locked;
    }

    /** Hinweise des Ladens plus ein Hinweis je Pick, der in der Fearless-Serie schon gespielt wurde. */
    private static List<String> warnings(List<String> loading, List<Slot> ally, List<Slot> enemy, Set<Integer> locked,
            GameData data) {
        List<String> warnings = new ArrayList<>(loading);
        for (List<Slot> team : List.of(ally, enemy)) {
            for (Slot slot : team) {
                if (locked.contains(slot.champion())) {
                    String name = data.championName(slot.champion());
                    warnings.add(name + " wurde in dieser Fearless-Serie schon gespielt und ist eigentlich gesperrt.");
                }
            }
        }
        return warnings;
    }

    /**
     * Ein vollständiges Brett aus {@code ally}, {@code enemy} und {@code bans}: Teamgröße, doppelte Champions und
     * Bans werden geprüft (400 mit {@code incompleteMessage} bei unvollständigen Teams).
     */
    static Draft board(JsonNode body, GameData data, String incompleteMessage) {
        List<Slot> ally = slots(body.path("ally"), data);
        List<Slot> enemy = slots(body.path("enemy"), data);
        if (ally.size() != DraftArchive.TEAM_SIZE || enemy.size() != DraftArchive.TEAM_SIZE) {
            throw new ApiException(400, incompleteMessage);
        }
        requireDistinct(ally, enemy);
        JsonNode banList = body.path("bans");
        if (banList.size() > DraftArchive.MAX_BANS) {
            throw new ApiException(400, "Höchstens " + DraftArchive.MAX_BANS + " Bans.");
        }
        Set<Integer> bans = new LinkedHashSet<>();
        banList.forEach(node -> bans.add(championKey(node, data)));
        return new Draft(ally, enemy, new ArrayList<>(bans));
    }

    /** Slots für eine Antwort: Champion und feste Rolle. */
    static List<Map<String, Object>> slotsJson(List<Slot> slots) {
        return slots.stream().map(slot -> Dto.obj("champion", slot.champion(),
                "role", slot.forcedRole() == null ? null : slot.forcedRole().name())).toList();
    }

    static int championKey(JsonNode node, GameData data) {
        int key = node.asInt(-1);
        if (data.championByKey(key).isEmpty()) {
            throw new ApiException(400, "Unbekannter Champion: " + node.asText());
        }
        return key;
    }

    static void requireDistinct(List<Slot> ally, List<Slot> enemy) {
        Set<Integer> seen = new HashSet<>();
        for (List<Slot> team : List.of(ally, enemy)) {
            for (Slot slot : team) {
                if (!seen.add(slot.champion())) {
                    throw new ApiException(400, "Ein Champion kann nur einmal gewählt werden.");
                }
            }
        }
    }

    private static Role role(String value) {
        try {
            return Role.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "Ungültige Rolle");
        }
    }

    private static Map<Role, Integer> team(Prediction prediction) {
        Map<Role, Integer> team = new EnumMap<>(Role.class);
        prediction.roles().forEach((champion, role) -> team.put(role, champion));
        return team;
    }

    // ------------------------------------------------------------------ Antwort

    /**
     * Je Slot die verwendete Rolle, ob sie automatisch gewählt wurde, die Wahrscheinlichkeit der Zuteilung und der
     * Anteil der Spiele des Champions je Rolle (30 Tage) für die Rollenauswahl.
     */
    private static List<Map<String, Object>> slotResults(List<Slot> slots, Prediction prediction, DraftDataset dataset) {
        return slots.stream().map(slot -> {
            Map<String, Double> probabilities = new LinkedHashMap<>();
            prediction.probabilities().getOrDefault(slot.champion(), Map.of())
                    .forEach((role, value) -> probabilities.put(role.name(), value));
            double total = Arrays.stream(Role.values()).mapToDouble(r -> dataset.full(new Pick(slot.champion(), r)).games()).sum();
            Map<String, Double> shares = new LinkedHashMap<>();
            for (Role role : Role.values()) {
                shares.put(role.name(), total > 0 ? dataset.full(new Pick(slot.champion(), role)).games() / total : 0);
            }
            return Dto.obj("champion", slot.champion(), "role", prediction.roles().get(slot.champion()).name(),
                    "auto", slot.forcedRole() == null, "probabilities", probabilities, "shares", shares);
        }).toList();
    }

    private static Map<String, Object> analysis(Result r) {
        return Dto.obj(
                "winRate", r.winRate(), "totalRating", r.totalRating(), "championRating", r.championRating(),
                "duoRating", r.duoRating(), "matchupRating", r.matchupRating(),
                "allyChampions", r.champions().stream().map(DraftEndpoint::champion).toList(),
                "enemyChampions", r.opponentChampions().stream().map(DraftEndpoint::champion).toList(),
                "allyDuos", r.duos().stream().map(DraftEndpoint::pair).toList(),
                "enemyDuos", r.opponentDuos().stream().map(DraftEndpoint::pair).toList(),
                "matchups", r.matchups().stream().map(DraftEndpoint::pair).toList());
    }

    private static Map<String, Object> champion(ChampionResult c) {
        return Dto.obj("champion", c.pick().champion(), "role", c.pick().role().name(), "rating", c.rating(),
                "winRate", winRate(c.stats()), "games", c.stats().games());
    }

    private static Map<String, Object> pair(PairResult p) {
        return Dto.obj("first", pick(p.first()), "second", pick(p.second()), "rating", p.rating(),
                "winRate", winRate(p.stats()), "games", p.stats().games());
    }

    private static Map<String, Object> pick(Pick pick) {
        return Dto.obj("champion", pick.champion(), "role", pick.role().name());
    }

    private static Double winRate(Stat stat) {
        return stat.games() > 0 ? stat.wins() / stat.games() : null;
    }

    private static Map<String, Object> profile(Map<Role, Integer> team, DraftDataLoader.Loaded loaded, DraftAnalyzer.Config config) {
        List<LolalyticsBuild> builds = team.entrySet().stream()
                .map(e -> loaded.builds().get(new Pick(e.getValue(), e.getKey())))
                .filter(Objects::nonNull)
                .toList();
        TeamProfile profile = TeamProfile.of(builds, TeamProfile.PRIOR_GAMES);
        return Dto.obj("champions", profile.champions(), "physical", profile.physical(), "magic", profile.magic(), "true", profile.trueDamage(),
                "ratingByLength", profile.ratingByLength(),
                "lengthStartMinutes", LolalyticsBuild.LengthBucket.startMinutes(profile.ratingByLength().size()));
    }

    /** Die Summen enthalten nur, was dieser Pick selbst beiträgt. */
    private static Map<String, Object> suggestion(Suggestion s, DraftDataset dataset) {
        Result r = s.result();
        Pick pick = s.pick();
        ChampionResult own = r.champions().stream().filter(c -> c.pick().equals(pick)).findFirst().orElse(null);
        List<Map<String, Object>> details = new ArrayList<>();
        double duoRating = 0;
        for (PairResult duo : r.duos()) {
            if (duo.first().equals(pick) || duo.second().equals(pick)) {
                duoRating += duo.rating();
                details.add(detail("duo", duo.first().equals(pick) ? duo.second() : duo.first(), duo));
            }
        }
        double matchupRating = 0;
        for (PairResult matchup : r.matchups()) {
            if (matchup.first().equals(pick)) {
                matchupRating += matchup.rating();
                details.add(detail("matchup", matchup.second(), matchup));
            }
        }
        return Dto.obj("champion", pick.champion(), "role", pick.role().name(), "winRate", r.winRate(), "delta", s.delta(),
                "championRating", own == null ? 0.0 : own.rating(), "duoRating", duoRating, "matchupRating", matchupRating,
                "championWinRate", own == null ? null : winRate(own.stats()), "games", own == null ? 0.0 : own.stats().games(),
                "pickRate", dataset.pickRate(pick), "laneShare", dataset.laneShare(pick), "details", details);
    }

    private static Map<String, Object> detail(String kind, Pick other, PairResult pair) {
        return Dto.obj("kind", kind, "champion", other.champion(), "role", other.role().name(), "rating", pair.rating(),
                "winRate", winRate(pair.stats()), "games", pair.stats().games());
    }
}
