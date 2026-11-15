package com.leaguetool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.leaguetool.data.GameData;
import com.leaguetool.data.lolalytics.LolalyticsBuild;
import com.leaguetool.data.lolalytics.LolalyticsClient;
import com.leaguetool.data.lolalytics.LolalyticsPeriod;
import com.leaguetool.draft.DraftAnalyzer;
import com.leaguetool.draft.DraftAnalyzer.ChampionResult;
import com.leaguetool.draft.DraftAnalyzer.PairResult;
import com.leaguetool.draft.DraftAnalyzer.Result;
import com.leaguetool.draft.DraftAnalyzer.Suggestion;
import com.leaguetool.draft.DraftDataLoader;
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
    private static final int MAX_MIN_GAMES = 100_000;
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
        DraftAnalyzer.Risk risk = risk(body.path("risk").asText(""));
        int requestId = body.path("requestId").asInt();

        DraftDataLoader.Loaded loaded;
        try {
            loaded = loader.load(client, new DraftDataLoader.Request(ally, enemy, tier, period),
                    key -> data.championByKey(key).map(Champion::name).orElse("Champion " + key),
                    (done, total) -> ctx.publish("draft-progress", Dto.obj("requestId", requestId, "done", done, "total", total)),
                    registerRequest(body.path("client").asText(""), requestId));
        } catch (DraftDataLoader.SupersededException e) {
            throw new ApiException(409, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }

        JsonNode requestedMinGames = body.path("minGames");
        int minGames = requestedMinGames.isNumber()
                ? Math.max(0, Math.min(MAX_MIN_GAMES, requestedMinGames.asInt()))
                : DraftAnalyzer.defaultMinGames(loaded.analysedPicks());
        DraftAnalyzer.Config config = new DraftAnalyzer.Config(risk, body.path("ignoreChampionWinRates").asBoolean(), minGames);

        Map<Role, Integer> allyTeam = team(loaded.allyRoles());
        Map<Role, Integer> enemyTeam = team(loaded.enemyRoles());
        DraftAnalyzer analyzer = new DraftAnalyzer(loaded.dataset(), config);
        boolean forEnemy = "enemy".equals(body.path("side").asText());
        Map<Role, Integer> suggestedTeam = forEnemy ? enemyTeam : allyTeam;
        List<Suggestion> suggestions = forEnemy
                ? analyzer.suggestions(enemyTeam, allyTeam, bans)
                : analyzer.suggestions(allyTeam, enemyTeam, bans);

        return Dto.obj(
                "patch", loaded.patch(),
                "side", forEnemy ? "enemy" : "ally",
                "minGames", minGames,
                "slots", Dto.obj("ally", slotResults(ally, loaded.allyRoles()), "enemy", slotResults(enemy, loaded.enemyRoles())),
                "analysis", analysis(analyzer.analyze(allyTeam, enemyTeam)),
                "profiles", Dto.obj(
                        "ally", profile(allyTeam, loaded, config),
                        "enemy", profile(enemyTeam, loaded, config)),
                "openRoles", Arrays.stream(Role.values()).filter(r -> !suggestedTeam.containsKey(r)).map(Role::name).toList(),
                "suggestions", suggestions.stream().map(DraftEndpoint::suggestion).toList(),
                "warnings", loaded.warnings());
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

    private static List<Slot> slots(JsonNode team, GameData data) {
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

    private static Set<Integer> bans(JsonNode list, GameData data) {
        if (list.size() > MAX_BANS) {
            throw new ApiException(400, "Höchstens " + MAX_BANS + " Bans.");
        }
        Set<Integer> bans = new HashSet<>();
        list.forEach(node -> bans.add(championKey(node, data)));
        return bans;
    }

    private static int championKey(JsonNode node, GameData data) {
        int key = node.asInt(-1);
        if (data.championByKey(key).isEmpty()) {
            throw new ApiException(400, "Unbekannter Champion: " + node.asText());
        }
        return key;
    }

    private static void requireDistinct(List<Slot> ally, List<Slot> enemy) {
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

    private static DraftAnalyzer.Risk risk(String value) {
        if (value.isEmpty()) {
            return DraftAnalyzer.Risk.MEDIUM;
        }
        try {
            return DraftAnalyzer.Risk.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "Ungültige Risikostufe");
        }
    }

    private static Map<Role, Integer> team(Prediction prediction) {
        Map<Role, Integer> team = new EnumMap<>(Role.class);
        prediction.roles().forEach((champion, role) -> team.put(role, champion));
        return team;
    }

    // ------------------------------------------------------------------ Antwort

    private static List<Map<String, Object>> slotResults(List<Slot> slots, Prediction prediction) {
        return slots.stream().map(slot -> {
            Map<String, Double> probabilities = new LinkedHashMap<>();
            prediction.probabilities().getOrDefault(slot.champion(), Map.of())
                    .forEach((role, value) -> probabilities.put(role.name(), value));
            return Dto.obj("champion", slot.champion(), "role", prediction.roles().get(slot.champion()).name(),
                    "probabilities", probabilities);
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
        TeamProfile profile = TeamProfile.of(builds, config.risk().priorGames());
        return Dto.obj("champions", profile.champions(), "physical", profile.physical(), "magic", profile.magic(), "true", profile.trueDamage(),
                "ratingByLength", profile.ratingByLength());
    }

    /** Die Summen enthalten nur, was dieser Pick selbst beiträgt. */
    private static Map<String, Object> suggestion(Suggestion s) {
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
                "details", details);
    }

    private static Map<String, Object> detail(String kind, Pick other, PairResult pair) {
        return Dto.obj("kind", kind, "champion", other.champion(), "role", other.role().name(), "rating", pair.rating(),
                "winRate", winRate(pair.stats()), "games", pair.stats().games());
    }
}
