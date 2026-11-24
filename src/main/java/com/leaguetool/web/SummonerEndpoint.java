package com.leaguetool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.leaguetool.config.AppSettings;
import com.leaguetool.data.GameData;
import com.leaguetool.data.lolalytics.LolalyticsClient;
import com.leaguetool.data.lolalytics.LolalyticsFullTierList;
import com.leaguetool.data.lolalytics.LolalyticsPeriod;
import com.leaguetool.data.riot.LeagueEntry;
import com.leaguetool.data.riot.Platform;
import com.leaguetool.data.riot.RiotAccount;
import com.leaguetool.data.riot.RiotApiClient;
import com.leaguetool.data.riot.RiotApiException;
import com.leaguetool.model.Champion;
import com.leaguetool.model.Role;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Endpunkte des Summoner-Tabs (Riot-API). Getrennt vom Draft und vom League-Client: keine Daten aus der
 * Champion-Auswahl, keine automatischen Abfragen anderer Spieler.
 *
 * <ul>
 *   <li>{@code GET summoner?riotId=&platform=&count=}: Profil, Matches, alle Meisterschaften, Empfehlungen</li>
 *   <li>{@code GET summoner/own}: eigene Konten und ihre gemeinsame Matchup-Bilanz aus dem Cache (kein Abruf, für den Draft)</li>
 *   <li>{@code POST summoner/own}: eigenes Konto hinzufügen ({@code riotId}, {@code platform}) oder mit {@code remove: true} entfernen</li>
 *   <li>{@code GET summoner/live?riotId=&platform=}: laufendes Spiel (Spectator-v5) mit Name und Rang aller Spieler;
 *       ohne Riot-ID das eigene Konto Nr. {@code account} (Standard 0). 1 bis 2 Aufrufe plus einer je Spieler.</li>
 *   <li>{@code GET summoner/live/<n>/champion}: Winrate des Spielers {@code n} mit seinem Champion in den letzten
 *       {@value #CHAMPION_WR_MATCHES} Ranked-Spielen, nur auf Knopfdruck (bis zu 21 Aufrufe)</li>
 * </ul>
 */
final class SummonerEndpoint {

    static final int CHAMPION_WR_MATCHES = 20;
    private static final int RANKED_SOLO = 420;
    private static final int RANKED_FLEX = 440;
    private static final Map<String, String> RANKED_QUEUES = Map.of("RANKED_SOLO_5x5", "Solo/Duo", "RANKED_FLEX_SR", "Flex");

    private final WebContext ctx;
    /** Letztes abgefragtes Live-Spiel; PUUIDs bleiben im Backend, die Oberfläche kennt nur die Platznummer. */
    private volatile LiveSession live;

    SummonerEndpoint(WebContext ctx) {
        this.ctx = ctx;
    }

    private record LiveSession(Platform platform, long gameId, int queueId, List<String> puuids, List<Integer> champions) {
    }

    // ------------------------------------------------------------------ Profil

    Object lookup(Map<String, String> params, int count, String laTier) throws IOException {
        Platform platform = platform(params.get("platform"));
        Map<String, Object> result = new SummonerLookup(ctx.riotClient(platform), ctx.cache(), ctx.awaitData().patch(),
                (done, total, cached) -> ctx.publish("summoner-progress", Dto.obj("done", done, "total", total, "cached", cached)))
                .lookup(params.get("riotId"), count);
        String riotId = result.get("gameName") + "#" + result.get("tagLine");
        List<AppSettings.OwnAccount> accounts = ctx.settings().ownAccounts();
        boolean own = accounts.stream().anyMatch(a -> a.platform().equals(platform.id()) && a.riotId().equalsIgnoreCase(riotId));
        // Weitere eigene Konten fließen nur bei einem eigenen Konto in die Empfehlungen ein (nur Cache), nie bei fremden.
        List<Map<String, Object>> otherRows = new ArrayList<>();
        if (own) {
            for (AppSettings.OwnAccount a : accounts) {
                if (!(a.platform().equals(platform.id()) && a.riotId().equalsIgnoreCase(riotId))) {
                    otherRows.addAll(SummonerLookup.cachedRows(ctx.cache(), a.puuid()));
                }
            }
        }
        result.put("recommendations", recommendations(result, otherRows, laTier));
        result.put("own", own);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> recommendations(Map<String, Object> result, List<Map<String, Object>> otherRows, String laTier) {
        GameData data = ctx.awaitData();
        Map<Integer, List<String>> tags = new HashMap<>();
        for (Champion c : data.champions()) {
            tags.put(c.key(), c.tags());
        }
        Map<Integer, Integer> mastery = new HashMap<>();
        for (Map<String, Object> m : (List<Map<String, Object>>) result.get("mastery")) {
            mastery.put((Integer) m.get("championKey"), (Integer) m.get("level"));
        }
        Optional<LolalyticsClient> client = ctx.lolalytics();
        Map<Role, LolalyticsFullTierList> lists = new EnumMap<>(Role.class);
        if (client.isPresent()) {
            for (Role role : Role.values()) {
                try {
                    // Nur Cache, auch veraltet: Empfehlungen lösen nie einen Lolalytics-Abruf aus.
                    LolalyticsFullTierList list = client.get().fullTierListAnyCached(laTier, LolalyticsClient.lane(role),
                            LolalyticsPeriod.LAST_30_DAYS);
                    if (list == null) {
                        list = client.get().fullTierListAnyCached(laTier, LolalyticsClient.lane(role), LolalyticsPeriod.CURRENT_PATCH);
                    }
                    if (list != null) {
                        lists.put(role, list);
                    }
                } catch (IOException e) {
                    // Beschädigter Eintrag: Rolle ohne Meta-Werte.
                }
            }
        }
        Map<String, Object> out = SummonerRecommendations.compute((List<Map<String, Object>>) result.get("matches"), otherRows,
                mastery, tags, lists);
        out.put("lolalytics", client.isPresent());
        return out;
    }

    // ------------------------------------------------------------------ Alle eigenen Konten

    /**
     * Übersicht über alle eigenen Konten zusammen: Matches und Mitspieler nur aus dem Cache (auch ohne Key). Ist die
     * Riot-API eingerichtet, kommen je Konto Ränge (league-v4) und Meisterschaft (champion-mastery-v4) dazu, also
     * höchstens zwei Aufrufe je Konto; schlägt das fehl, bleibt die Übersicht ohne sie.
     */
    @SuppressWarnings("unchecked")
    Object combined(String laTier) {
        List<AppSettings.OwnAccount> accounts = ctx.settings().ownAccounts();
        if (accounts.isEmpty()) {
            throw new ApiException(400, "Noch keine eigenen Konten festgelegt. Ein Konto abfragen und „Als mein Konto festlegen“ wählen.");
        }
        Map<String, Object> result = new HashMap<>(SummonerLookup.combinedMatches(ctx.cache(),
                accounts.stream().map(AppSettings.OwnAccount::puuid).toList()));
        List<Map<String, Object>> ranked = new ArrayList<>();
        List<List<Map<String, Object>>> masteries = new ArrayList<>();
        String riotNote = null;
        if (ctx.settings().riotApiKey().isEmpty() || ctx.riotApi().isEmpty()) {
            riotNote = "Ränge und Meisterschaft nur mit aktivierter Riot-API; Matches kommen aus dem Speicher.";
        } else {
            for (AppSettings.OwnAccount a : accounts) {
                RiotApiClient client = ctx.riotClient(platform(a.platform()));
                try {
                    for (LeagueEntry e : client.leagueEntriesByPuuid(a.puuid())) {
                        if (e.queueType() != null && e.queueType().startsWith("RANKED_")) {
                            Map<String, Object> entry = new HashMap<>(SummonerLookup.ranked(e));
                            entry.put("account", a.riotId());
                            ranked.add(entry);
                        }
                    }
                    masteries.add(SummonerLookup.mastery(client.championMasteries(a.puuid())));
                } catch (IOException | RuntimeException e) {
                    riotNote = "Ränge und Meisterschaft fehlen teilweise: " + e.getMessage();
                    if (e instanceof RiotApiException r && r.isAuthProblem()) {
                        break;
                    }
                }
            }
        }
        List<Map<String, Object>> accountRows = new ArrayList<>();
        for (int i = 0; i < accounts.size(); i++) {
            int index = i;
            long games = ((List<Map<String, Object>>) result.get("matches")).stream()
                    .filter(m -> Integer.valueOf(index).equals(m.get("account"))).count();
            accountRows.add(Dto.obj("riotId", accounts.get(i).riotId(), "platform", accounts.get(i).platform(), "matches", games));
        }
        result.put("combined", true);
        result.put("accounts", accountRows);
        result.put("gameName", "Alle meine Konten");
        result.put("tagLine", "");
        result.put("platform", "");
        result.put("level", 0);
        result.put("icon", null);
        result.put("ranked", ranked);
        result.put("mastery", SummonerLookup.combinedMastery(masteries));
        result.put("riotNote", riotNote);
        result.put("own", false);
        // Alle Konten gleich gewichtet: es gibt kein „angezeigtes“ Konto, das doppelt zählen könnte.
        result.put("recommendations", recommendations(result, List.of(), laTier));
        return result;
    }

    // ------------------------------------------------------------------ Eigenes Konto

    /**
     * Eigene Konten und die gemeinsame Bilanz aller eigenen Konten (Haupt- und Zweitkonten zusammengezählt), nur aus
     * dem Cache.
     */
    Object own() {
        List<AppSettings.OwnAccount> accounts = ctx.settings().ownAccounts();
        List<JsonNode> matches = new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (AppSettings.OwnAccount a : accounts) {
            List<JsonNode> own = SummonerLookup.loadHistory(ctx.cache(), a.puuid()).matches();
            matches.addAll(own);
            out.add(Dto.obj("riotId", a.riotId(), "platform", a.platform(), "matches", own.size()));
        }
        return Dto.obj("accounts", out, "max", AppSettings.MAX_OWN_ACCOUNTS,
                "matchups", accounts.isEmpty() ? null : OwnMatchups.of(matches));
    }

    /** Body {@code {riotId, platform}} fügt ein Konto hinzu, mit {@code remove: true} wird es entfernt (ohne Abruf). */
    Object setOwn(JsonNode body) throws IOException {
        String raw = body.path("riotId").asText("").trim();
        AppSettings settings = ctx.settings();
        List<AppSettings.OwnAccount> accounts = new ArrayList<>(settings.ownAccounts());
        if (body.path("remove").asBoolean(false)) {
            String platformId = body.path("platform").asText("").toLowerCase(Locale.ROOT);
            accounts.removeIf(a -> a.riotId().equalsIgnoreCase(raw) && (platformId.isEmpty() || a.platform().equals(platformId)));
            settings.setOwnAccounts(accounts);
            settings.save();
            return own();
        }
        String[] id = SummonerLookup.parseRiotId(raw);
        Platform platform = platform(body.path("platform").asText(""));
        RiotAccount account;
        try {
            account = ctx.riotClient(platform).accountByRiotId(id[0], id[1]);
        } catch (RiotApiException e) {
            if (e.isNotFound()) {
                throw new ApiException(404, "Riot-ID „" + raw + "“ wurde nicht gefunden.");
            }
            throw new ApiException(e.isAuthProblem() ? 403 : 502, e.getMessage());
        }
        AppSettings.OwnAccount added = new AppSettings.OwnAccount(account.gameName() + "#" + account.tagLine(), platform.id(), account.puuid());
        // Dasselbe Konto (PUUID) ersetzt seinen alten Eintrag, z. B. nach einer Namensänderung.
        int index = -1;
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).puuid().equals(added.puuid())) {
                index = i;
            }
        }
        if (index >= 0) {
            accounts.set(index, added);
        } else if (accounts.size() >= AppSettings.MAX_OWN_ACCOUNTS) {
            throw new ApiException(400, "Höchstens " + AppSettings.MAX_OWN_ACCOUNTS + " eigene Konten möglich.");
        } else {
            accounts.add(added);
        }
        settings.setOwnAccounts(accounts);
        settings.save();
        return own();
    }

    // ------------------------------------------------------------------ Live-Spiel

    Object live(Map<String, String> params) throws IOException {
        String raw = params.getOrDefault("riotId", "").trim();
        List<AppSettings.OwnAccount> accounts = ctx.settings().ownAccounts();
        Platform platform;
        String puuid;
        String label;
        try {
            if (raw.isEmpty()) {
                int index = (int) Math.max(0, Input.intValue(params.getOrDefault("account", "0"), 400, "Ungültiges Konto"));
                if (index >= accounts.size()) {
                    throw new ApiException(400, "Kein eigenes Konto gespeichert. Bitte eine Riot-ID eingeben oder im "
                            + "Summoner-Tab ein Konto als eigenes festlegen.");
                }
                AppSettings.OwnAccount own = accounts.get(index);
                platform = platform(own.platform());
                puuid = own.puuid();
                label = own.riotId();
            } else {
                String[] id = SummonerLookup.parseRiotId(raw);
                platform = platform(params.get("platform"));
                RiotAccount account = ctx.riotClient(platform).accountByRiotId(id[0], id[1]);
                puuid = account.puuid();
                label = account.gameName() + "#" + account.tagLine();
            }
            RiotApiClient api = ctx.riotClient(platform);
            Optional<JsonNode> game = api.activeGame(puuid);
            if (game.isEmpty()) {
                live = null;
                return Dto.obj("inGame", false, "riotId", label, "platform", platform.id());
            }
            return liveGame(api, platform, game.get(), puuid, accounts, label);
        } catch (RiotApiException e) {
            if (e.isNotFound()) {
                throw new ApiException(404, "Riot-ID „" + raw + "“ wurde nicht gefunden.");
            }
            throw new ApiException(e.isAuthProblem() ? 403 : 502, e.getMessage());
        }
    }

    private Object liveGame(RiotApiClient api, Platform platform, JsonNode game, String puuid,
                            List<AppSettings.OwnAccount> accounts, String label) throws IOException {
        int queueId = game.path("gameQueueConfigId").asInt();
        List<String> puuids = new ArrayList<>();
        List<Integer> champions = new ArrayList<>();
        Map<Integer, List<Map<String, Object>>> teams = new HashMap<>();
        for (JsonNode p : game.path("participants")) {
            String playerPuuid = p.path("puuid").asText("");
            boolean bot = p.path("bot").asBoolean(false) || playerPuuid.isBlank();
            int slot = puuids.size();
            puuids.add(bot ? "" : playerPuuid);
            champions.add(p.path("championId").asInt());
            String riotId = p.path("riotId").asText("");
            int hash = riotId.lastIndexOf('#');
            Map<String, Object> ranked = new HashMap<>();
            if (!bot) {
                for (LeagueEntry e : api.leagueEntriesByPuuid(playerPuuid)) {
                    String queue = RANKED_QUEUES.get(e.queueType());
                    if (queue != null) {
                        int games = e.wins() + e.losses();
                        ranked.put(queue, Dto.obj("tier", e.tier() == null ? "" : e.tier().label(),
                                "division", e.tier() != null && e.tier().isApex() ? "" : e.rank(), "lp", e.leaguePoints(),
                                "wins", e.wins(), "losses", e.losses(), "winRate", games == 0 ? 0 : (double) e.wins() / games));
                    }
                }
            }
            boolean self = playerPuuid.equals(puuid);
            Map<String, Object> player = Dto.obj("slot", slot, "bot", bot,
                    "name", hash > 0 ? riotId.substring(0, hash) : riotId, "tag", hash > 0 ? riotId.substring(hash + 1) : "",
                    "championKey", p.path("championId").asInt(),
                    "spells", List.of(p.path("spell1Id").asInt(), p.path("spell2Id").asInt()),
                    "solo", ranked.get("Solo/Duo"), "flex", ranked.get("Flex"), "self", self);
            if (!bot && accounts.stream().anyMatch(a -> a.puuid().equals(playerPuuid))) {
                // Eigenes Konto: Champion-Winrate gratis aus der gespeicherten Historie.
                player.put("champion", ownChampionStats(playerPuuid, p.path("championId").asInt(), queueId));
            }
            teams.computeIfAbsent(p.path("teamId").asInt(), t -> new ArrayList<>()).add(player);
        }
        live = new LiveSession(platform, game.path("gameId").asLong(), queueId, List.copyOf(puuids), List.copyOf(champions));
        Map<Integer, List<Integer>> bans = new HashMap<>();
        for (JsonNode b : game.path("bannedChampions")) {
            if (b.path("championId").asInt() > 0) {
                bans.computeIfAbsent(b.path("teamId").asInt(), t -> new ArrayList<>()).add(b.path("championId").asInt());
            }
        }
        List<Map<String, Object>> outTeams = new ArrayList<>();
        for (int teamId : List.of(100, 200)) {
            if (teams.containsKey(teamId)) {
                outTeams.add(Dto.obj("side", teamId == 100 ? "blue" : "red", "players", teams.get(teamId),
                        "bans", bans.getOrDefault(teamId, List.of())));
            }
        }
        return Dto.obj("inGame", true, "riotId", label, "platform", platform.id(), "queueId", queueId,
                "mode", SummonerLookup.modeLabel(queueId, game.path("gameMode").asText("")),
                "startedAt", game.path("gameStartTime").asLong(), "length", game.path("gameLength").asLong(),
                "teams", outTeams, "championMatches", CHAMPION_WR_MATCHES);
    }

    /** Eigene Spiele mit dem Champion in der Queue des Live-Spiels (Ranked: Solo/Duo bzw. Flex), aus dem Cache. */
    private Map<String, Object> ownChampionStats(String puuid, int champion, int queueId) {
        int queue = queueId == RANKED_FLEX ? RANKED_FLEX : RANKED_SOLO;
        int games = 0;
        int wins = 0;
        for (JsonNode m : SummonerLookup.loadHistory(ctx.cache(), puuid).matches()) {
            JsonNode p = m.path("p");
            if (m.path("queueId").asInt() == queue && p.path("championId").asInt() == champion && !SummonerLookup.isRemake(m, p)) {
                games++;
                wins += p.path("win").asBoolean() ? 1 : 0;
            }
        }
        return Dto.obj("games", games, "wins", wins, "source", "history", "queue", queue == RANKED_FLEX ? "Flex" : "Solo/Duo");
    }

    Object liveChampion(List<String> path) throws IOException {
        LiveSession session = live;
        int slot = Input.intValue(path.size() > 2 ? path.get(2) : "", 400, "Ungültiger Spieler");
        if (session == null || slot < 0 || slot >= session.puuids().size()) {
            throw new ApiException(409, "Das Live-Spiel ist nicht mehr aktuell. Bitte neu abfragen.");
        }
        String puuid = session.puuids().get(slot);
        if (puuid.isBlank()) {
            throw new ApiException(400, "Für KI-Spieler gibt es keine Statistik.");
        }
        int champion = session.champions().get(slot);
        int queue = session.queueId() == RANKED_FLEX ? RANKED_FLEX : RANKED_SOLO;
        RiotApiClient api = ctx.riotClient(session.platform());
        int games = 0;
        int wins = 0;
        int recentWins = 0;
        int recent = 0;
        try {
            for (String id : api.matchIdsByPuuid(puuid, queue, 0, CHAMPION_WR_MATCHES, null)) {
                JsonNode info = api.match(id).path("info");
                for (JsonNode p : info.path("participants")) {
                    if (!puuid.equals(p.path("puuid").asText()) || SummonerLookup.isRemake(info, p)) {
                        continue;
                    }
                    boolean win = p.path("win").asBoolean();
                    recent++;
                    recentWins += win ? 1 : 0;
                    if (p.path("championId").asInt() == champion) {
                        games++;
                        wins += win ? 1 : 0;
                    }
                }
            }
        } catch (RiotApiException e) {
            throw new ApiException(e.isAuthProblem() ? 403 : 502, e.getMessage());
        }
        return Dto.obj("slot", slot, "games", games, "wins", wins, "recent", recent, "recentWins", recentWins,
                "source", "recent", "queue", queue == RANKED_FLEX ? "Flex" : "Solo/Duo");
    }

    private Platform platform(String id) {
        return Platform.fromId(id == null ? "" : id.toLowerCase(Locale.ROOT)).orElseGet(ctx::platform);
    }
}
