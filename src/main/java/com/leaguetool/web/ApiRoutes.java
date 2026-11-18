package com.leaguetool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leaguetool.calc.AdaptiveType;
import com.leaguetool.calc.BuildCalculator;
import com.leaguetool.calc.BuildTotals;
import com.leaguetool.calc.ChampionBuildCalculator;
import com.leaguetool.calc.FinalStats;
import com.leaguetool.calc.RuneEffects;
import com.leaguetool.calc.StatBlock;
import com.leaguetool.calc.StatCalculator;
import com.leaguetool.config.AppSettings;
import com.leaguetool.data.GameData;
import com.leaguetool.data.lolalytics.LolalyticsBuild;
import com.leaguetool.data.lcu.LcuLocator;
import com.leaguetool.data.lolalytics.LolalyticsClient;
import com.leaguetool.data.lolalytics.LolalyticsFullTierList;
import com.leaguetool.data.lolalytics.LolalyticsPeriod;
import com.leaguetool.data.lolalytics.LolalyticsRoleStrength;
import com.leaguetool.data.riot.Platform;
import com.leaguetool.data.riot.RiotApi;
import com.leaguetool.data.riot.RiotVerifier;
import com.leaguetool.data.riot.VerificationReport;
import com.leaguetool.data.wiki.WikiAbility;
import com.leaguetool.data.wiki.WikiChampionData;
import com.leaguetool.data.wiki.WikiClient;
import com.leaguetool.model.Champion;
import com.leaguetool.model.Item;
import com.leaguetool.model.Role;
import com.leaguetool.util.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Die JSON-Endpunkte unter {@code /api}. */
final class ApiRoutes {

    private static final List<String> LOCALES = List.of("en_US", "de_DE", "en_GB", "fr_FR", "es_ES", "it_IT", "pl_PL");
    private static final List<String> LOLALYTICS_TIERS = List.of("all", "emerald_plus", "diamond_plus", "master_plus",
            "platinum_plus", "gold_plus", "silver_plus", "bronze_plus", "iron");
    private static final int MAX_UI_STATE = 64 * 1024;
    private static final String UI_STATE_FILE = "ui-state.json";
    /** Wählbare Rang-Bereiche der Lolalytics-Statistik; alles andere ergibt den Standard. */
    private static final List<String> LOLALYTICS_STAT_TIERS = List.of("all", "iron", "bronze", "silver", "gold", "gold_plus",
            "platinum", "platinum_plus", "emerald", "emerald_plus", "diamond", "diamond_plus", "d2_plus", "master_plus",
            "grandmaster", "challenger");
    private static final String LOLALYTICS_DEFAULT_TIER = LolalyticsClient.DEFAULT_TIER;

    private final WebContext ctx;
    private final ObjectMapper json;
    private final DraftEndpoint draft;
    private final DraftArchiveEndpoint archive;
    private final FearlessEndpoint fearless;

    ApiRoutes(WebContext ctx, ObjectMapper json) {
        this.ctx = ctx;
        this.json = json;
        this.draft = new DraftEndpoint(ctx);
        this.archive = new DraftArchiveEndpoint(ctx);
        this.fearless = new FearlessEndpoint(ctx, archive.drafts());
    }

    /** Antwort eines Endpunkts: ein zu serialisierendes Objekt oder fertige JSON-Bytes. */
    Object handle(String method, List<String> path, Map<String, String> params, byte[] body) throws Exception {
        String route = method + " " + (path.isEmpty() ? "" : path.get(0));
        return switch (route) {
            case "GET bootstrap" -> bootstrap();
            case "POST reload" -> {
                ctx.reload();
                yield Map.of("ok", true);
            }
            case "GET settings" -> settings();
            case "POST settings" -> saveSettings(body);
            case "GET ui-state" -> readUiState();
            case "PUT ui-state" -> writeUiState(body);
            case "GET build" -> Dto.buildTotals(BuildCalculator.totals(items(params)));
            case "POST verify" -> verify();
            case "GET summoner" -> summoner(params);
            case "GET stats" -> stats(path, params);
            case "GET champions" -> champion(path, params);
            case "POST draft" -> draft(body);
            case "GET draft-archive" -> {
                fearless.backfillArchive(); // Serienspiele aus älteren Dateien zuerst nachtragen, sonst fehlen sie in der Liste
                yield archive.list();
            }
            case "POST draft-archive" -> path.size() == 1 ? archive.record(parse(body)) : archive.restore(path);
            case "DELETE draft-archive" -> archive.delete(path);
            case "PUT draft-archive" -> archive.updateRoles(path, parse(body), fearless::updateRolesMatching);
            case "GET fearless", "DELETE fearless" -> fearless.handle(method, path, json.createObjectNode());
            case "POST fearless", "PUT fearless" -> fearless.handle(method, path, body.length == 0 ? json.createObjectNode() : parse(body));
            case "GET lcu" -> ClientSyncEndpoint.get(ctx);
            case "POST lcu" -> ClientSyncEndpoint.set(ctx, parse(body));
            default -> throw new ApiException(404, "Unbekannter Endpunkt");
        };
    }

    // ------------------------------------------------------------------ Spieldaten

    private byte[] bootstrap() throws IOException {
        GameData data = ctx.awaitData();
        byte[] cached = ctx.bootstrapJson(data);
        if (cached == null) {
            cached = json.writeValueAsBytes(Dto.bootstrap(data, ctx.dataVersion(), ctx.itemPassives().latest(),
                    ctx.runeEffects().latest(), ctx.patchChange()));
            ctx.setBootstrapJson(data, cached);
        }
        return cached;
    }

    private Champion championOf(GameData data, String id) {
        return data.championById(id).orElseThrow(() -> new ApiException(404, "Champion nicht gefunden: " + id));
    }

    private Object champion(List<String> path, Map<String, String> params) throws Exception {
        if (path.size() != 3) {
            throw new ApiException(404, "Unbekannter Endpunkt");
        }
        GameData data = ctx.awaitData();
        Champion champion = championOf(data, path.get(1));
        return switch (path.get(2)) {
            case "detail" -> Dto.championDetail(ctx.gameDataService().championDetail(data.patch(), champion.id()), data.patch());
            case "wiki" -> wiki(champion);
            case "levels" -> levels(champion, params);
            case "insights" -> insights(champion, params);
            default -> throw new ApiException(404, "Unbekannter Endpunkt");
        };
    }

    private Object wiki(Champion champion) throws IOException {
        Optional<WikiClient> wiki = ctx.wiki();
        if (wiki.isEmpty()) {
            return Dto.obj("disabled", true);
        }
        Optional<WikiChampionData> data = wiki.get().champion(champion.id());
        return data.<Object>map(d -> Dto.wiki(champion, d)).orElseGet(() -> Dto.obj("found", false));
    }

    private Object levels(Champion champion, Map<String, String> params) {
        BuildTotals totals = BuildCalculator.totals(items(params));
        RunePage runes = runePage(params);
        double asRatio = doubleParam(params, "asRatio", champion.stats().attackSpeed());
        AdaptiveType adaptiveType = adaptiveType(champion);
        List<List<Map<String, Object>>> finals = new ArrayList<>();
        for (int level = StatCalculator.MIN_LEVEL; level <= StatCalculator.MAX_LEVEL; level++) {
            FinalStats f = ChampionBuildCalculator.compute(champion.stats(), level, totals.stats().plus(runes.at(level)),
                    asRatio, adaptiveType);
            finals.add(Dto.finalRows(f));
        }
        return Dto.obj("base", Dto.baseRows(champion), "finals", finals, "gold", totals.totalGold(),
                "itemCount", totals.itemCount(), "runeCount", runes.size());
    }

    private Object insights(Champion champion, Map<String, String> params) throws IOException {
        Optional<WikiClient> wiki = ctx.wiki();
        List<Item> build = items(params);
        RunePage runes = runePage(params);
        int level = StatCalculator.clampLevel((int) doubleParam(params, "level", 11));
        StatBlock bonus = BuildCalculator.totals(build).stats().plus(runes.at(level));
        if (wiki.isEmpty()) {
            // Haste hängt nicht vom Wiki ab; ohne Wiki fehlen nur Immobilisierungen und statische Abklingzeiten.
            FinalStats stats = ChampionBuildCalculator.compute(champion.stats(), level, bonus,
                    champion.stats().attackSpeed(), adaptiveType(champion));
            return Dto.obj("disabled", true, "itemCount", build.size(), "runeCount", runes.size(),
                    "haste", Dto.hasteBySlot(List.of(), stats),
                    "summonerSpellHaste", stats.summonerSpellHaste(), "staticCooldowns", List.of());
        }
        List<WikiAbility> abilities = wiki.get().abilities(champion.id());
        WikiChampionData wikiChampion = wiki.get().champion(champion.id()).orElse(null);
        double asRatio = wikiChampion == null ? champion.stats().attackSpeed() : wikiChampion.asRatio();
        FinalStats stats = ChampionBuildCalculator.compute(champion.stats(), level, bonus, asRatio, adaptiveType(champion));
        return Dto.obj("itemCount", build.size(), "runeCount", runes.size(), "abilities", Dto.wikiAbilities(abilities, stats),
                "haste", Dto.hasteBySlot(abilities, stats), "summonerSpellHaste", stats.summonerSpellHaste(),
                "staticCooldowns", Dto.staticCooldownSlots(abilities));
    }

    /** Runenseite des Build-Rechners: {@code runes=8010,9111}, {@code shards=5008,5008,5001}, {@code stacks=9105:10}. */
    private record RunePage(List<Integer> runes, List<Integer> shards, Map<Integer, Integer> stacks) {

        StatBlock at(int level) {
            return RuneEffects.stats(runes, shards, stacks, level);
        }

        int size() {
            return runes.size() + shards.size();
        }
    }

    private static final int MAX_RUNES = 6;
    private static final int MAX_SHARDS = 3;

    private static RunePage runePage(Map<String, String> params) {
        List<Integer> runes = ints(params.get("runes"), "Runen-ID").stream().distinct().limit(MAX_RUNES).toList();
        List<Integer> shards = ints(params.get("shards"), "Shard-ID").stream().limit(MAX_SHARDS).toList();
        Map<Integer, Integer> stacks = new HashMap<>();
        String raw = params.getOrDefault("stacks", "");
        for (String part : raw.isBlank() ? new String[0] : raw.split(",")) {
            String[] kv = part.split(":");
            List<Integer> pair = kv.length == 2 ? ints(kv[0] + "," + kv[1], "Stapel") : List.of();
            if (pair.size() != 2) {
                throw new ApiException(400, "Ungültige Stapel-Angabe: " + part);
            }
            if (runes.contains(pair.get(0))) {
                stacks.put(pair.get(0), pair.get(1));
            }
        }
        return new RunePage(runes, shards, stacks);
    }

    private static List<Integer> ints(String raw, String what) {
        List<Integer> values = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        for (String part : raw.split(",")) {
            try {
                values.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException e) {
                throw new ApiException(400, "Ungültige " + what + ": " + part);
            }
        }
        return values;
    }

    /** Adaptiver Typ laut Wiki; ohne Wiki aus den Data-Dragon-Werten des Champions. */
    private AdaptiveType adaptiveType(Champion champion) {
        String wikiType = "";
        Optional<WikiClient> wiki = ctx.wiki();
        if (wiki.isPresent()) {
            try {
                wikiType = wiki.get().champion(champion.id()).map(WikiChampionData::adaptiveType).orElse("");
            } catch (IOException e) {
                // Das Wiki ist nur eine Verfeinerung; ohne es entscheiden die Data-Dragon-Werte.
            }
        }
        return AdaptiveType.of(wikiType, champion.info().attack(), champion.info().magic());
    }

    /** Items aus {@code items=1,2,3}; unbekannte IDs (z. B. nach einem Patch) fallen weg. */
    private List<Item> items(Map<String, String> params) {
        String raw = params.getOrDefault("items", "");
        List<Item> items = new ArrayList<>();
        if (raw.isBlank()) {
            return items;
        }
        GameData data = ctx.awaitData();
        for (String part : raw.split(",")) {
            int id;
            try {
                id = Integer.parseInt(part.trim());
            } catch (NumberFormatException e) {
                throw new ApiException(400, "Ungültige Item-ID: " + part);
            }
            data.item(id).ifPresent(items::add);
            if (items.size() == BuildCalculator.MAX_ITEMS) {
                break;
            }
        }
        return items;
    }

    private static double doubleParam(Map<String, String> params, String name, double fallback) {
        String raw = params.get(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            double value = Double.parseDouble(raw);
            return Double.isFinite(value) ? value : fallback;
        } catch (NumberFormatException e) {
            throw new ApiException(400, "Ungültiger Wert für " + name + ": " + raw);
        }
    }

    // ------------------------------------------------------------------ Statistik aus Lolalytics

    private static Role role(Map<String, String> params) {
        String role = params.get("role");
        try {
            return role == null || role.isBlank() ? null : Role.valueOf(role);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "Ungültige Rolle");
        }
    }

    private Object stats(List<String> path, Map<String, String> params) throws IOException {
        if (path.size() < 2) {
            throw new ApiException(404, "Unbekannter Endpunkt");
        }
        LolalyticsClient client = lolalytics();
        Role role = role(params);
        LolalyticsPeriod period = period(params.get("period"));
        String tier = statTier(params.get("laTier"));
        LolalyticsFullTierList list = client.fullTierList(tier, role == null ? "all" : LolalyticsClient.lane(role), period);
        switch (path.get(1)) {
            case "meta":
                return Dto.obj("roles", Arrays.stream(Role.values()).map(r -> Dto.obj("value", r.name(), "label", r.label())).toList(),
                        "analysed", list.analysed(), "avgWinRate", list.avgWinRate(), "patch", list.patch());
            case "champions": {
                Map<Integer, Map<String, Object>> out = new LinkedHashMap<>();
                // Die Stärke vergleicht innerhalb einer Rolle und ergibt nur mit Rollenfilter Sinn.
                Map<Integer, Double> strength = role == null ? Map.of() : LolalyticsRoleStrength.compute(list.rows());
                list.rows().forEach((key, row) -> out.put(key, Dto.lolalyticsChampionStat(row, strength.get(key))));
                return out;
            }
            case "report":
                if (path.size() == 3) {
                    return lolalyticsReport(client, list, path.get(2), tier, role, period);
                }
                throw new ApiException(404, "Unbekannter Endpunkt");
            default:
                throw new ApiException(404, "Lolalytics liefert diese Statistik nicht.");
        }
    }

    private Object lolalyticsReport(LolalyticsClient client, LolalyticsFullTierList list, String key, String tier, Role role,
                                    LolalyticsPeriod period) throws IOException {
        int championKey;
        try {
            championKey = Integer.parseInt(key);
        } catch (NumberFormatException e) {
            throw new ApiException(400, "Ungültiger Champion-Key: " + key);
        }
        String slug = list.slugs().get(championKey);
        if (slug == null) {
            throw new ApiException(404, "Champion bei Lolalytics nicht gefunden");
        }
        GameData data = ctx.awaitData();
        String lane = role == null ? null : LolalyticsClient.lane(role);
        LolalyticsBuild build = client.build(slug, tier, lane, period);
        LolalyticsFullTierList.Row row = list.rows().get(championKey);
        String opponentLane = lane != null ? lane : row == null ? "" : row.lane();
        return Dto.lolalyticsReport(build, data, opponentLane);
    }

    private LolalyticsClient lolalytics() {
        return ctx.lolalytics().orElseThrow(() -> new ApiException(412, "Lolalytics ist in den Optionen deaktiviert."));
    }

    private static LolalyticsPeriod period(String value) {
        return "patch".equals(value) ? LolalyticsPeriod.CURRENT_PATCH : LolalyticsPeriod.LAST_30_DAYS;
    }

    private static String statTier(String value) {
        return value != null && LOLALYTICS_STAT_TIERS.contains(value) ? value : LOLALYTICS_DEFAULT_TIER;
    }

    private Object draft(byte[] body) throws IOException {
        LolalyticsClient client = lolalytics();
        JsonNode request = parse(body);
        return draft.handle(client, request, statTier(request.path("tier").asText("")), period(request.path("period").asText("")));
    }

    // ------------------------------------------------------------------ Riot-API

    private void requireRiotConfigured() {
        if (ctx.settings().riotApiKey().isEmpty() || ctx.riotApi().isEmpty()) {
            throw new ApiException(412, "Für diese Funktion in den Optionen die Riot-API aktivieren und einen "
                    + "API-Key eintragen (oder die Umgebungsvariable " + AppSettings.ENV_RIOT_KEY
                    + " setzen). Kostenlose Dev-Keys gibt es unter developer.riotgames.com.");
        }
    }

    private Object verify() {
        requireRiotConfigured();
        RiotApi api = ctx.riotApi().get();
        VerificationReport report = new RiotVerifier().verify(api, ctx.awaitData());
        return Dto.obj("allOk", report.allOk(), "checks", report.checks().stream()
                .map(c -> Dto.obj("name", c.name(), "ok", c.ok(), "detail", c.detail())).toList());
    }

    private Object summoner(Map<String, String> params) throws IOException {
        requireRiotConfigured();
        Platform platform = Platform.fromId(params.get("platform"))
                .or(() -> Platform.fromId(ctx.settings().platform())).orElse(Platform.EUW1);
        int count = Math.max(1, Math.min(SummonerLookup.MAX_MATCHES, (int) doubleParam(params, "count", 50)));
        return new SummonerLookup(ctx.riotClient(platform), ctx.cache(), ctx.awaitData().patch(),
                (done, total, cached) -> ctx.publish("summoner-progress",
                        Dto.obj("done", done, "total", total, "cached", cached)))
                .lookup(params.get("riotId"), count);
    }

    // ------------------------------------------------------------------ Einstellungen

    private JsonNode parse(byte[] body) throws IOException {
        JsonNode node = body.length == 0 ? null : json.readTree(body);
        if (node == null || !node.isObject()) {
            throw new ApiException(400, "JSON-Objekt erwartet");
        }
        return node;
    }

    /** Liest bewusst von der Platte, damit auch von Hand geänderte Einstellungen angezeigt werden. */
    private Object settings() throws IOException {
        AppSettings s = AppSettings.load(ctx.settings().home());
        return Dto.obj(
                "locale", s.locale(), "locales", LOCALES,
                "offline", s.offline(), "wikiEnabled", s.wikiEnabled(),
                "lolalyticsEnabled", s.lolalyticsEnabled(), "lolalyticsTier", s.lolalyticsTier(),
                "lolalyticsTiers", LOLALYTICS_TIERS,
                "riotEnabled", s.riotEnabled(),
                // Der Key selbst verlässt das Backend nie.
                "hasStoredKey", !s.storedRiotApiKey().isBlank(), "riotReady", s.riotEnabled() && s.riotApiKey().isPresent(),
                "envKeyName", AppSettings.ENV_RIOT_KEY,
                "platform", Platform.fromId(s.platform()).orElse(Platform.EUW1).id(),
                "platforms", Arrays.stream(Platform.values()).map(Platform::id).toList(),
                "lcuFolder", s.lcuFolder(),
                "draftArchiveLimit", s.draftArchiveLimit(), "draftArchiveLimits", AppSettings.DRAFT_ARCHIVE_LIMITS,
                "draftArchiveDefault", AppSettings.DRAFT_ARCHIVE_DEFAULT,
                "fearlessSeriesLimit", s.fearlessSeriesLimit(), "fearlessSeriesLimits", AppSettings.FEARLESS_SERIES_LIMITS,
                "fearlessSeriesDefault", AppSettings.FEARLESS_SERIES_DEFAULT,
                "lcuDetected", ctx.lcuLocator().detectedInstallFolder().map(Path::toString).orElse(null),
                "file", s.file().toString());
    }

    private Object saveSettings(byte[] body) throws IOException {
        JsonNode n = parse(body);
        AppSettings s = ctx.settings();
        // Erst prüfen, dann ändern: Ein abgelehnter Ordner darf keine anderen Einstellungen verstellen.
        String lcuFolder = lcuFolder(n.get("lcuFolder"));
        String locale = n.path("locale").asText("");
        if (locale.matches("[a-z]{2}_[A-Z]{2}")) {
            s.setLocale(locale);
        }
        s.setOffline(n.path("offline").asBoolean());
        s.setWikiEnabled(n.path("wikiEnabled").asBoolean());
        s.setLolalyticsEnabled(n.path("lolalyticsEnabled").asBoolean());
        String tier = n.path("lolalyticsTier").asText("");
        if (tier.matches("[a-z_]{1,30}")) {
            s.setLolalyticsTier(tier);
        }
        s.setRiotEnabled(n.path("riotEnabled").asBoolean());
        // Fehlendes Feld: Key unverändert lassen (aktuellen Stand von der Platte übernehmen).
        JsonNode key = n.get("riotApiKey");
        if (key != null && !key.isNull()) {
            s.setStoredRiotApiKey(key.asText());
        } else {
            s.setStoredRiotApiKey(AppSettings.load(s.home()).storedRiotApiKey());
        }
        Platform.fromId(n.path("platform").asText("")).ifPresent(p -> s.setPlatform(p.id()));
        if (n.path("draftArchiveLimit").isInt()) {
            s.setDraftArchiveLimit(n.path("draftArchiveLimit").asInt());
        }
        if (n.path("fearlessSeriesLimit").isInt()) {
            s.setFearlessSeriesLimit(n.path("fearlessSeriesLimit").asInt());
        }
        if (lcuFolder != null) {
            s.setLcuFolder(lcuFolder);
        }
        s.save();
        ctx.rebuildServices();
        ctx.reload();
        return settings();
    }

    /**
     * League-Ordner für den Client-Abgleich: {@code null} bei fehlendem Feld (unverändert), leer = automatisch suchen.
     *
     * @throws ApiException wenn im angegebenen Ordner kein Client liegt
     */
    private static String lcuFolder(JsonNode field) {
        if (field == null || field.isNull()) {
            return null;
        }
        String input = field.asText("").trim();
        if (input.isEmpty()) {
            return "";
        }
        Path folder = LcuLocator.normalize(input)
                .orElseThrow(() -> new ApiException(400, "Ungültiger Pfad für den League-Ordner"));
        if (!LcuLocator.isInstallFolder(folder)) {
            throw new ApiException(400, "Im Ordner " + folder + " liegt kein LeagueClient.exe");
        }
        return folder.toString();
    }

    // ------------------------------------------------------------------ Oberflächen-Zustand

    private Path uiStateFile() {
        return ctx.settings().home().resolve("modern-ui").resolve(UI_STATE_FILE);
    }

    private byte[] readUiState() throws IOException {
        Path file = uiStateFile();
        return Files.isRegularFile(file) ? Files.readAllBytes(file) : "{}".getBytes(StandardCharsets.UTF_8);
    }

    private Object writeUiState(byte[] body) throws IOException {
        if (body.length > MAX_UI_STATE) {
            throw new ApiException(413, "Zustand zu groß");
        }
        parse(body);
        AtomicFiles.write(uiStateFile(), body);
        return Map.of("ok", true);
    }
}
