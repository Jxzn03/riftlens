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
import com.leaguetool.data.wiki.WikiItems;
import com.leaguetool.data.wiki.WikiPatchNotes;
import com.leaguetool.model.Ability;
import com.leaguetool.model.Champion;
import com.leaguetool.model.ChampionDetail;
import com.leaguetool.model.Item;
import com.leaguetool.model.Role;
import com.leaguetool.update.ReleaseNotes;
import com.leaguetool.util.AppVersion;
import com.leaguetool.util.AtomicFiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

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
            case "GET items" -> itemWiki(path);
            case "GET patch-notes" -> patchNotes(params);
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
            case "GET update" -> ctx.updates().state();
            case "POST update" -> update(path);
            case "GET release-notes" -> releaseNotes();
            case "POST release-notes" -> releaseNotesSeen(path);
            default -> throw new ApiException(404, "Unbekannter Endpunkt");
        };
    }

    // ------------------------------------------------------------------ Spieldaten

    private byte[] bootstrap() throws IOException {
        GameData data = ctx.awaitData();
        byte[] cached = ctx.bootstrapJson(data);
        if (cached == null) {
            cached = json.writeValueAsBytes(Dto.bootstrap(data, ctx.settings().locale(), ctx.dataVersion(), ctx.itemPassives().latest(),
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
            case "patch-history" -> patchHistory(champion);
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

    /**
     * {@code GET items/<id>/wiki}: Passiven und Aktive des Items laut Wiki mit Abklingzeit ({@code Module:ItemData}, im Speicher
     * gehalten, höchstens einen Tag alt). Ohne Wiki {@code {"disabled": true}}, ohne Eintrag leere Liste.
     */
    private Object itemWiki(List<String> path) throws IOException {
        if (path.size() != 3 || !"wiki".equals(path.get(2))) {
            throw new ApiException(404, "Unbekannter Endpunkt");
        }
        int id = Input.intValue(path.get(1), 400, "Ungültige Item-ID: " + path.get(1));
        Optional<WikiClient> wiki = ctx.wiki();
        if (wiki.isEmpty()) {
            return Dto.obj("disabled", true);
        }
        WikiItems.Entry entry = wiki.get().itemData(false).get(id);
        if (entry == null) {
            return Dto.obj("passives", List.of(), "actives", List.of());
        }
        return Dto.obj("passives", entry.passives().stream().map(p -> Dto.obj("name", p.name(), "cooldown", p.cooldown())).toList(),
                "actives", entry.actives().stream().map(p -> Dto.obj("name", p.name(), "cooldown", p.cooldown())).toList());
    }

    /** Wie viele Patches vor dem aktuellen die Patch-Seite zur Auswahl anbietet. */
    private static final int PREVIOUS_PATCHES = 10;

    /**
     * Summoner's-Rift-Änderungen laut Wiki: der aktuelle Patch oder mit {@code ?patch=V26.22} einer der
     * {@link #PREVIOUS_PATCHES} davor (nur auf Anfrage geladen). {@code patches} listet die Auswahl.
     */
    private Object patchNotes(Map<String, String> params) throws IOException {
        Optional<WikiClient> wiki = ctx.wiki();
        if (wiki.isEmpty()) {
            return Dto.obj("disabled", true);
        }
        GameData data = ctx.awaitData();
        String current = WikiPatchNotes.wikiPatchTitle(data.patch());
        List<String> patches;
        try {
            patches = WikiPatchNotes.recentPatchTitles(ctx.gameDataService().versions(), data.patch(), PREVIOUS_PATCHES);
        } catch (IOException e) {
            patches = List.of(current); // ohne Versionsliste nur der aktuelle Patch
        }
        String title = params.getOrDefault("patch", current);
        if (!patches.contains(title)) {
            throw new ApiException(400, "Patch nicht in der Auswahl: " + title);
        }
        List<WikiPatchNotes.Section> sections = wiki.get().patchNotes(title, title.equals(current));
        List<WikiPatchNotes.Line> lines = sections.stream().flatMap(s -> s.groups().stream())
                .flatMap(g -> g.lines().stream()).toList();
        Map<String, Object> result = Dto.patchNotes(title, data.patch(), sections, wiki.get().isStale(), abilityIcons(data, lines));
        result.put("current", current);
        result.put("patches", patches);
        return result;
    }

    /** Höchste Wartezeit auf Fähigkeits-Icons; die Patch-Notes erscheinen auch ohne sie. */
    private static final long ICON_WAIT_MS = 3000;

    /**
     * Icon-URL zu „Champion|Fähigkeit“ aus den Data-Dragon-Details; Namen ohne Satzzeichen und Groß-/Kleinschreibung
     * verglichen, unbekannt → {@code null}. Die Details der erwähnten Champions laden parallel, gewartet wird höchstens
     * {@link #ICON_WAIT_MS}: Ist Data Dragon langsam, fehlen nur Icons, statt dass die Seite hängt (bis zu 30 s je
     * Abruf). Abrufe, die später fertig werden, liegen danach im Cache und zeigen beim nächsten Öffnen ihr Icon.
     */
    private Function<String, String> abilityIcons(GameData data, List<WikiPatchNotes.Line> lines) {
        Map<String, Champion> champions = new HashMap<>();
        for (WikiPatchNotes.Line line : lines) {
            for (WikiPatchNotes.Part part : line.parts()) {
                if ("ability".equals(part.style()) && part.ref() != null && part.ref().indexOf('|') > 0) {
                    String key = nameKey(part.ref().substring(0, part.ref().indexOf('|')));
                    data.champions().stream().filter(c -> nameKey(c.name()).equals(key)).findFirst()
                            .ifPresent(c -> champions.putIfAbsent(key, c));
                }
            }
        }
        Map<String, CompletableFuture<ChampionDetail>> loads = new HashMap<>();
        champions.forEach((key, c) -> loads.put(key, CompletableFuture.supplyAsync(() -> {
            try {
                return ctx.gameDataService().championDetail(data.patch(), c.id());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        })));
        try {
            CompletableFuture.allOf(loads.values().toArray(CompletableFuture[]::new)).get(ICON_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            // fehlende oder langsame Details: ohne Icon weiter
        }
        Map<String, Map<String, String>> byChampion = new HashMap<>();
        loads.forEach((key, load) -> {
            if (load.isDone() && !load.isCompletedExceptionally()) {
                Map<String, String> icons = new HashMap<>();
                for (Ability a : load.join().abilities()) {
                    String icon = Dto.abilityIcon(a, data.patch());
                    icons.put(nameKey(a.name()), icon);
                    // Formen unter einem Namen (Gnar: "Boomerang Throw / Boulder Toss") auch einzeln
                    for (String form : a.name().split("/")) {
                        icons.putIfAbsent(nameKey(form), icon);
                    }
                }
                byChampion.put(key, icons);
            }
        });
        return ref -> {
            int bar = ref.indexOf('|');
            Map<String, String> icons = bar < 0 ? null : byChampion.get(nameKey(ref.substring(0, bar)));
            return icons == null ? null : icons.get(nameKey(ref.substring(bar + 1)));
        };
    }

    private static String nameKey(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Patch-Historie eines Champions laut Wiki (nur Summoner's Rift, ohne Skins). */
    private Object patchHistory(Champion champion) throws IOException {
        Optional<WikiClient> wiki = ctx.wiki();
        if (wiki.isEmpty()) {
            return Dto.obj("disabled", true);
        }
        GameData data = ctx.awaitData();
        List<WikiPatchNotes.Entry> entries = wiki.get().championPatchHistory(champion.id());
        List<WikiPatchNotes.Line> lines = entries.stream().flatMap(e -> e.lines().stream()).toList();
        return Dto.patchHistory(entries, WikiPatchNotes.wikiPatchTitle(data.patch()), wiki.get().isStale(),
                abilityIcons(data, lines));
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
        WikiChampionData wikiChampion = wikiChampion(champion);
        // Wie in den Basiswerten: Zusatzdaten aus dem Wiki (vom Build unabhängig) auch unter „Werte mit Build“.
        finals.forEach(rows -> rows.addAll(Dto.wikiExtraRows(wikiChampion)));
        return Dto.obj("base", Dto.baseRows(champion, wikiChampion), "wiki", wikiChampion != null,
                "wikiInfo", Dto.wikiInfo(wikiChampion), "finals", finals, "gold", totals.totalGold(),
                "itemCount", totals.itemCount(), "runeCount", runes.size());
    }

    /** Wiki-Daten des Champions für die Basiswerte; ohne Wiki, Eintrag oder Netz und Cache {@code null} (dann Data Dragon). */
    private WikiChampionData wikiChampion(Champion champion) {
        Optional<WikiClient> wiki = ctx.wiki();
        if (wiki.isEmpty()) {
            return null;
        }
        try {
            return wiki.get().champion(champion.id()).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private Object insights(Champion champion, Map<String, String> params) throws IOException {
        Optional<WikiClient> wiki = ctx.wiki();
        List<Item> build = items(params);
        RunePage runes = runePage(params);
        int level = StatCalculator.clampLevel((int) doubleParam(params, "level", 11));
        StatBlock bonus = BuildCalculator.totals(build).stats().plus(runes.at(level));
        List<WikiAbility> abilities = wiki.isEmpty() ? List.of() : wiki.get().abilities(champion.id());
        WikiChampionData wikiChampion = wiki.isEmpty() ? null : wiki.get().champion(champion.id()).orElse(null);
        double asRatio = wikiChampion == null ? champion.stats().attackSpeed() : wikiChampion.asRatio();
        FinalStats stats = ChampionBuildCalculator.compute(champion.stats(), level, bonus, asRatio, adaptiveType(champion, wikiChampion));
        if (wiki.isEmpty()) {
            // Haste hängt nicht vom Wiki ab; ohne Wiki fehlen nur Immobilisierungen und statische Abklingzeiten.
            return Dto.obj("disabled", true, "itemCount", build.size(), "runeCount", runes.size(),
                    "haste", Dto.hasteBySlot(abilities, stats),
                    "summonerSpellHaste", stats.summonerSpellHaste(), "staticCooldowns", List.of());
        }
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
            values.add(Input.intValue(part.trim(), 400, "Ungültige " + what + ": " + part));
        }
        return values;
    }

    /** Adaptiver Typ laut Wiki; ohne Wiki aus den Data-Dragon-Werten des Champions. */
    private AdaptiveType adaptiveType(Champion champion) {
        WikiChampionData wikiChampion = null;
        Optional<WikiClient> wiki = ctx.wiki();
        if (wiki.isPresent()) {
            try {
                wikiChampion = wiki.get().champion(champion.id()).orElse(null);
            } catch (IOException e) {
                // Das Wiki ist nur eine Verfeinerung; ohne es entscheiden die Data-Dragon-Werte.
            }
        }
        return adaptiveType(champion, wikiChampion);
    }

    /** Adaptiver Typ zu schon gelesenen Wiki-Daten ({@code null} = ohne Wiki). */
    private static AdaptiveType adaptiveType(Champion champion, WikiChampionData wikiChampion) {
        String wikiType = Optional.ofNullable(wikiChampion).map(WikiChampionData::adaptiveType).orElse("");
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
            data.item(Input.intValue(part.trim(), 400, "Ungültige Item-ID: " + part)).ifPresent(items::add);
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
        return role == null || role.isBlank() ? null : Input.enumValue(Role.class, role, "Ungültige Rolle");
    }

    private Object stats(List<String> path, Map<String, String> params) throws IOException {
        if (path.size() < 2) {
            throw new ApiException(404, "Unbekannter Endpunkt");
        }
        LolalyticsClient client = lolalytics();
        Role role = role(params);
        LolalyticsPeriod period = period(params.get("period"));
        String tier = statTier(params.get("laTier"));
        String listLane = role == null ? "all" : LolalyticsClient.lane(role);
        if (path.size() == 3 && "report".equals(path.get(1)) && "1".equals(params.get("cachedOnly"))) {
            return cachedReport(client, path.get(2), tier, listLane, role, period);
        }
        // Ungültige Pfade vor dem Abruf abweisen (spart den Lolalytics-Abruf).
        switch (path.get(1)) {
            case "meta", "champions" -> { }
            case "report" -> {
                if (path.size() != 3) {
                    throw new ApiException(404, "Unbekannter Endpunkt");
                }
            }
            default -> throw new ApiException(404, "Lolalytics liefert diese Statistik nicht.");
        }
        LolalyticsFullTierList list = client.fullTierList(tier, listLane, period, staleOk(period));
        return switch (path.get(1)) {
            case "meta" -> Dto.obj("roles", Arrays.stream(Role.values()).map(r -> Dto.obj("value", r.name(), "label", r.label())).toList(),
                        "analysed", list.analysed(), "avgWinRate", list.avgWinRate(), "patch", list.patch());
            case "champions" -> {
                Map<Integer, Map<String, Object>> out = new LinkedHashMap<>();
                // Die Stärke vergleicht innerhalb einer Rolle und ergibt nur mit Rollenfilter Sinn.
                Map<Integer, Double> strength = role == null ? Map.of() : LolalyticsRoleStrength.compute(list.rows());
                list.rows().forEach((key, row) -> out.put(key, Dto.lolalyticsChampionStat(row, strength.get(key))));
                yield out;
            }
            default -> lolalyticsReport(client, list, path.get(2), tier, role, period, false);
        };
    }

    /**
     * 30-Tage-Daten dürfen wie im Draft veraltet sein: Ein vorhandener Eintrag wird sofort gezeigt und im Hintergrund
     * einmal erneuert, mit derselben Drosselung und höchstens einem Abruf je Eintrag in 12 h. „Letzter Patch“ wird nach
     * Ablauf oder einem Patchwechsel weiter frisch geholt, alte Werte wären dort der vorige Patch.
     */
    private static boolean staleOk(LolalyticsPeriod period) {
        return period == LolalyticsPeriod.LAST_30_DAYS;
    }

    /**
     * Report nur aus frischem Cache, ohne Abruf und ohne Erneuerung im Hintergrund: Die Champions-Seite zeigt gecachte
     * Statistik so ohne ihre Wartezeit (`REPORT_DELAY`). Liefert genau dann einen Report, wenn die normale Anfrage ohne
     * Abruf auskäme, sonst {@code {"cached": false}}; an den Abrufen bei Lolalytics ändert sich nichts.
     */
    private Object cachedReport(LolalyticsClient client, String key, String tier, String listLane, Role role,
                                LolalyticsPeriod period) throws IOException {
        LolalyticsFullTierList list = client.fullTierListIfCached(tier, listLane, period);
        Object report = list == null ? null : lolalyticsReport(client, list, key, tier, role, period, true);
        return report != null ? report : Dto.obj("cached", false, "lane", cachedLane(client, key, tier, period));
    }

    /**
     * Rollen-Fallback ohne frischen Report: häufigste Lane des Champions aus einer gecachten Tierliste (alle Rollen),
     * auch veraltet, erst im gewählten, dann im anderen Zeitraum. Kein Abruf, keine Erneuerung; sonst {@code null}.
     */
    private static String cachedLane(LolalyticsClient client, String key, String tier, LolalyticsPeriod period)
            throws IOException {
        int championKey = Input.intValue(key, 400, "Ungültiger Champion-Key: " + key);
        for (LolalyticsPeriod p : List.of(period, period == LolalyticsPeriod.LAST_30_DAYS
                ? LolalyticsPeriod.CURRENT_PATCH : LolalyticsPeriod.LAST_30_DAYS)) {
            LolalyticsFullTierList list = client.fullTierListAnyCached(tier, "all", p);
            LolalyticsFullTierList.Row row = list == null ? null : list.rows().get(championKey);
            String label = row == null ? null : Dto.laneLabel(row.lane());
            if (label != null) {
                return label;
            }
        }
        return null;
    }

    /** @param cachedOnly nur eine frische Seite aus dem Cache; {@code null}, wenn keine vorliegt */
    private Object lolalyticsReport(LolalyticsClient client, LolalyticsFullTierList list, String key, String tier, Role role,
                                    LolalyticsPeriod period, boolean cachedOnly) throws IOException {
        int championKey = Input.intValue(key, 400, "Ungültiger Champion-Key: " + key);
        String slug = list.slugs().get(championKey);
        if (slug == null) {
            throw new ApiException(404, "Champion bei Lolalytics nicht gefunden");
        }
        GameData data = ctx.awaitData();
        LolalyticsFullTierList.Row row = list.rows().get(championKey);
        // Ohne Rolle die Hauptlane aus der Liste (ohne Rolle ist das die Gesamtliste): Die Seite ohne Lane enthält
        // dieselben Daten, wäre aber ein eigener Cache-Eintrag und ein zweiter Abruf neben Draft und Rollenwahl.
        String lane = role != null ? LolalyticsClient.lane(role)
                : row != null && LolalyticsClient.isLane(row.lane()) ? row.lane() : null;
        LolalyticsBuild build = cachedOnly ? client.buildIfCached(slug, tier, lane, period, false) : client.build(slug, tier, lane, period, staleOk(period));
        if (build == null) {
            return null;
        }
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
        Platform platform = Platform.fromId(params.get("platform")).orElseGet(ctx::platform);
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
                "updateCheck", s.updateCheckEnabled(), "updatePrerelease", s.updatePrereleaseEnabled(),
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
        if (n.path("updateCheck").isBoolean()) {
            s.setUpdateCheckEnabled(n.path("updateCheck").asBoolean());
        }
        boolean channelChanged = n.path("updatePrerelease").isBoolean()
                && n.path("updatePrerelease").asBoolean() != s.updatePrereleaseEnabled();
        if (channelChanged) {
            s.setUpdatePrereleaseEnabled(n.path("updatePrerelease").asBoolean());
        }
        s.save();
        ctx.updates().settingsChanged();
        if (channelChanged && !s.offline()) {
            ctx.updates().check(); // Ergebnis des anderen Kanals (stabil bzw. Pre-Release) sofort ersetzen
        }
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

    // ------------------------------------------------------------------ Update

    /** {@code POST update/check} sucht nach einer neuen Version, {@code POST update/install} installiert sie. */
    private Object update(List<String> path) {
        String action = path.size() == 2 ? path.get(1) : "";
        try {
            return switch (action) {
                case "check" -> ctx.updates().check();
                case "install" -> ctx.updates().install();
                default -> throw new ApiException(404, "Unbekannter Endpunkt");
            };
        } catch (IllegalStateException e) {
            throw new ApiException(409, e.getMessage());
        }
    }

    /**
     * {@code GET release-notes}: alle Versionshinweise und die nach einem Update noch nicht gezeigten Versionen
     * ({@code unseen}). Beim ersten Start einer Neuinstallation gilt die aktuelle Version gleich als gezeigt.
     */
    private Object releaseNotes() throws IOException {
        AppSettings s = ctx.settings();
        String current = AppVersion.get();
        List<ReleaseNotes.Release> all = ReleaseNotes.all();
        List<String> unseen = ReleaseNotes.unseen(all, s.releaseNotesSeen(), current, s.existedOnLoad());
        if (unseen.isEmpty() && s.releaseNotesSeen().isEmpty() && !current.isEmpty()) {
            s.setReleaseNotesSeen(current);
            s.save();
        }
        return Map.of("current", current, "releases", all, "unseen", unseen);
    }

    /** {@code POST release-notes/seen}: Hinweise der aktuellen Version wurden gezeigt. */
    private Object releaseNotesSeen(List<String> path) throws IOException {
        if (path.size() != 2 || !"seen".equals(path.get(1))) {
            throw new ApiException(404, "Unbekannter Endpunkt");
        }
        String current = AppVersion.get();
        if (!current.isEmpty()) {
            ctx.settings().setReleaseNotesSeen(current);
            ctx.settings().save();
        }
        return Map.of("ok", true);
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
