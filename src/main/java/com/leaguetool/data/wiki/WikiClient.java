package com.leaguetool.data.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leaguetool.cache.FileCache;
import com.leaguetool.data.http.HttpFetcher;
import com.leaguetool.model.AbilitySlot;
import com.leaguetool.util.Json;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Client für das League-Wiki (MediaWiki-API) für Zusatzdaten: Champion-Basiswerte aus
 * {@code Module:ChampionData/data} und Fähigkeitsdetails aus {@code Template:Data_<Champion>/<Fähigkeit>}.
 *
 * <p>Antworten werden im {@link FileCache#GLOBAL}-Scope gespeichert und sind 3 Tage frisch, höchstens aber bis zum
 * nächsten Patchwechsel (Fähigkeiten und Werte ändern sich mit dem Patch); danach wird
 * netz-first geladen, bei Fehlern mit Rückfall auf den veralteten Cache. Ergebnisse werden zusätzlich im
 * Speicher gehalten. Weiterleitungen (z. B. "The Darkin Blade 2" → "The Darkin Blade") werden von der API
 * aufgelöst ({@code redirects=1}) und nur einmal geliefert.
 */
public final class WikiClient {

    public static final String API_URL = "https://wiki.leagueoflegends.com/en-us/api.php";
    public static final String CHAMPION_DATA_TITLE = "Module:ChampionData/data";

    static final Duration MAX_AGE = Duration.ofDays(3);
    static final String CHAMPION_DATA_KEY = "wiki/championdata.json";
    static final String ITEM_DATA_TITLE = "Module:ItemData/data";
    static final String ITEM_DATA_KEY = "wiki/itemdata.json";
    private static final Duration ITEM_DATA_MAX_AGE = Duration.ofDays(1);
    static final String RUNE_PAGE_TITLE = "Rune";
    static final String RUNE_PAGE_KEY = "wiki/rune.json";
    private static final int MAX_TITLES_PER_QUERY = 50;
    /**
     * Champion-Historien nur einmal je Patch (Nutzervorgabe): Sie gelten bis zum nächsten Patchwechsel
     * ({@code notBefore}), die lange Frist greift praktisch nie.
     */
    private static final Duration ONCE_PER_PATCH = Duration.ofDays(365);
    /**
     * Patch-Seite höchstens einmal täglich, damit nachgetragene Hotfixes erscheinen (Nutzervorgabe); nach einem
     * Patchwechsel sofort neu. Das ist ein Abruf am Tag und nur beim Öffnen der Unterseite, weit unter jeder Drosselung
     * des Wikis.
     */
    private static final Duration PATCH_NOTES_MAX_AGE = Duration.ofHours(24);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern PATCH_TITLE = Pattern.compile("V\\d{1,2}\\.\\d{1,2}");
    private static final String QUERY = "?action=query&prop=revisions&rvprop=content&rvslots=main"
            + "&format=json&formatversion=2&redirects=1&titles=";

    private final HttpFetcher http;
    private final FileCache cache;
    private final String apiUrl;
    private final Supplier<Instant> notBefore;

    private final Object championLock = new Object();
    private volatile Map<String, WikiChampionData> championData;
    private final Map<String, List<WikiAbility>> abilityCache = new ConcurrentHashMap<>();
    private volatile boolean lastLoadStale;

    public WikiClient(HttpFetcher http, FileCache cache) {
        this(http, cache, API_URL);
    }

    /** Konstruktor mit abweichender API-URL (Tests). */
    public WikiClient(HttpFetcher http, FileCache cache, String apiUrl) {
        this(http, cache, apiUrl, () -> Instant.EPOCH);
    }

    /**
     * @param notBefore Zeitpunkt des letzten Patchwechsels: ältere Cache-Einträge gelten als veraltet und werden neu
     *                  geholt (ohne Netz bleiben sie nutzbar)
     */
    public WikiClient(HttpFetcher http, FileCache cache, String apiUrl, Supplier<Instant> notBefore) {
        this.http = http;
        this.cache = cache;
        this.apiUrl = apiUrl;
        this.notBefore = notBefore;
    }

    /**
     * Alle Champions aus dem Wiki, Schlüssel ist {@link WikiChampionData#apiName()} (= Data-Dragon-ID).
     *
     * @throws IOException wenn weder Netz noch Cache verfügbar sind oder das Modul nicht lesbar ist
     */
    public Map<String, WikiChampionData> championData() throws IOException {
        Map<String, WikiChampionData> data = championData;
        if (data != null) {
            return data;
        }
        synchronized (championLock) {
            if (championData == null) {
                championData = Collections.unmodifiableMap(load(CHAMPION_DATA_KEY,
                        () -> http.getText(queryUrl(List.of(CHAMPION_DATA_TITLE))), this::parseChampionModule,
                        parsed -> true));
            }
            return championData;
        }
    }

    /**
     * Item-Passiven aus {@code Module:ItemData/data}, höchstens einen Tag alt.
     *
     * @param refresh {@code true} lädt neu (z. B. bei einem neuen Patch), ohne Netz bleibt der Cache-Stand
     * @throws IOException wenn weder Netz noch Cache verfügbar sind oder das Modul nicht lesbar ist
     */
    public Map<Integer, WikiItems.Entry> itemData(boolean refresh) throws IOException {
        return load(ITEM_DATA_KEY, refresh ? Duration.ZERO : ITEM_DATA_MAX_AGE,
                () -> http.getText(queryUrl(List.of(ITEM_DATA_TITLE))), this::parseItemModule, parsed -> true);
    }

    /**
     * Shards der Wiki-Seite „Rune“ (Werte und Reihen).
     *
     * @throws IOException wenn weder Netz noch Cache verfügbar sind oder die Tabelle nicht lesbar ist
     */
    public WikiRuneShards.Shards runeShards() throws IOException {
        return load(RUNE_PAGE_KEY, () -> http.getText(queryUrl(List.of(RUNE_PAGE_TITLE))),
                json -> WikiRuneShards.parse(moduleContent(json, RUNE_PAGE_TITLE)), parsed -> true);
    }

    /** Wiki-Daten zu einer Data-Dragon-ID (z. B. "MonkeyKing"). */
    public Optional<WikiChampionData> champion(String ddragonId) throws IOException {
        return Optional.ofNullable(championData().get(ddragonId));
    }

    /**
     * Fähigkeiten eines Champions in der Reihenfolge P, Q, W, E, R (mehrere Formen pro Slot in
     * Modul-Reihenfolge). Alle Seiten werden mit einer gebündelten Abfrage geholt; fehlende oder nicht
     * lesbare Seiten werden übersprungen. Unbekannter Champion → leere Liste.
     *
     * @throws IOException wenn weder Netz noch Cache verfügbar sind
     */
    public List<WikiAbility> abilities(String ddragonId) throws IOException {
        if (ddragonId == null || !SAFE_ID.matcher(ddragonId).matches()) {
            throw new IllegalArgumentException("Ungültige Champion-ID: " + ddragonId);
        }
        List<WikiAbility> cached = abilityCache.get(ddragonId);
        if (cached != null) {
            return cached;
        }
        Optional<WikiChampionData> champion = champion(ddragonId);
        if (champion.isEmpty()) {
            return List.of();
        }
        List<Request> requests = requests(champion.get());
        if (requests.isEmpty()) {
            return List.of();
        }
        List<String> titles = requests.stream().map(Request::title).toList();
        WikiChampionData data = champion.get();
        ParsedAbilities parsed = load("wiki/abilities/" + ddragonId + ".json", () -> fetchPages(titles),
                json -> parseAbilities(data, requests, json), ParsedAbilities::complete);
        List<WikiAbility> result = List.copyOf(parsed.abilities());
        abilityCache.put(ddragonId, result);
        return result;
    }

    /**
     * Summoner's-Rift-Abschnitte der Patch-Seite (z. B. {@code V26.23}) samt Hotfixes; leer, solange das Wiki die Seite
     * noch nicht hat. Ein leerer Stand gilt nicht als fertig und wird beim nächsten Aufruf erneut versucht. Ohne Netz
     * bleibt der letzte Stand.
     *
     * @param current {@code true} für den laufenden Patch: höchstens einmal täglich neu (Hotfixes); ältere Patches
     *                ändern sich nicht mehr und werden einmal je Patch geladen
     * @throws IOException wenn weder Netz noch Cache verfügbar sind
     */
    public List<WikiPatchNotes.Section> patchNotes(String patchTitle, boolean current) throws IOException {
        if (patchTitle == null || !PATCH_TITLE.matcher(patchTitle).matches()) {
            throw new IllegalArgumentException("Ungültiger Patch-Titel: " + patchTitle);
        }
        return load("wiki/patch/" + patchTitle + ".json", current ? PATCH_NOTES_MAX_AGE : ONCE_PER_PATCH,
                () -> http.getText(queryUrl(List.of(patchTitle))),
                json -> WikiPatchNotes.parsePatchPage(pageContent(json)), parsed -> !parsed.isEmpty());
    }

    /**
     * Patch-Historie eines Champions ({@code <Wiki-Name>/Patch history}), neuester Patch zuerst; nur auf Anfrage und
     * einmal je Patch geladen. Leer bei unbekanntem Champion oder fehlender Seite.
     *
     * @throws IOException wenn weder Netz noch Cache verfügbar sind
     */
    public List<WikiPatchNotes.Entry> championPatchHistory(String ddragonId) throws IOException {
        if (ddragonId == null || !SAFE_ID.matcher(ddragonId).matches()) {
            throw new IllegalArgumentException("Ungültige Champion-ID: " + ddragonId);
        }
        Optional<WikiChampionData> champion = champion(ddragonId);
        if (champion.isEmpty()) {
            return List.of();
        }
        String title = champion.get().wikiName() + "/Patch history";
        return load("wiki/patchhistory/" + ddragonId + ".json", ONCE_PER_PATCH, () -> http.getText(queryUrl(List.of(title))),
                json -> WikiPatchNotes.parseChampionHistory(pageContent(json)), parsed -> true);
    }

    /** {@code true}, wenn die letzte Abfrage mangels Netz aus einem veralteten Cache-Eintrag kam. */
    public boolean isStale() {
        return lastLoadStale;
    }

    /** URL einer gebündelten Seitenabfrage (Titel URL-kodiert, getrennt durch {@code %7C}). */
    public String queryUrl(List<String> titles) {
        return apiUrl + QUERY + titles.stream().map(WikiClient::encode).collect(Collectors.joining("%7C"));
    }

    /** Seitentitel {@code Template:Data_<Wiki-Name>/<Fähigkeit>}. */
    public static String abilityPageTitle(String wikiName, String skillName) {
        return "Template:Data_" + wikiName + "/" + skillName;
    }

    // ---------------------------------------------------------------- intern

    private record Request(AbilitySlot slot, String skillName, String title) {
    }

    /**
     * Geparste Ability-Antwort.
     *
     * @param complete {@code false}, wenn die Antwort nicht alle angefragten Titel enthält (z. B. Cache-Eintrag
     *                 von vor einer Umbenennung)
     */
    private record ParsedAbilities(List<WikiAbility> abilities, boolean complete) {
    }

    @FunctionalInterface
    private interface IoFunction<T, R> {
        R apply(T value) throws IOException;
    }

    /**
     * Lädt einen Eintrag aus dem Cache (3 Tage frisch, danach netz-first mit Rückfall auf den veralteten
     * Eintrag) und parst ihn. Netzantworten werden vor dem Speichern geparst, damit eine unlesbare Antwort den
     * Cache nicht überschreibt. Ein frischer, aber unlesbarer Eintrag wird verworfen und neu geladen; ein frischer
     * Eintrag, der laut {@code complete} nicht mehr zur Anfrage passt, ebenfalls (ohne Netz bleibt er nutzbar).
     */
    private <T> T load(String key, FileCache.IoSupplier<String> fetch, IoFunction<String, T> parser,
                       Predicate<T> complete) throws IOException {
        return load(key, MAX_AGE, fetch, parser, complete);
    }

    private <T> T load(String key, Duration maxAge, FileCache.IoSupplier<String> fetch, IoFunction<String, T> parser,
                       Predicate<T> complete) throws IOException {
        List<T> fromNetwork = new ArrayList<>(1);
        FileCache.CacheResult r = cache.getFreshOrStale(FileCache.GLOBAL, key, maxAge, notBefore.get(), () -> {
            String content = fetch.get();
            fromNetwork.add(parser.apply(content));
            return content;
        });
        if (!r.fromCache() && !fromNetwork.isEmpty()) {
            lastLoadStale = false;
            return fromNetwork.get(0);
        }
        T cached;
        try {
            cached = parser.apply(r.content());
        } catch (IOException e) {
            if (r.stale()) {
                throw e;
            }
            return refresh(key, fetch, parser);
        }
        if (!r.stale() && !complete.test(cached)) {
            try {
                return refresh(key, fetch, parser);
            } catch (IOException e) {
                lastLoadStale = true;
                return cached;
            }
        }
        lastLoadStale = r.stale();
        return cached;
    }

    /** Lädt einen Eintrag ohne Cache neu und speichert ihn, wenn er lesbar ist. */
    private <T> T refresh(String key, FileCache.IoSupplier<String> fetch, IoFunction<String, T> parser)
            throws IOException {
        String content = fetch.get();
        T value = parser.apply(content);
        cache.writeText(FileCache.GLOBAL, key, content);
        lastLoadStale = false;
        return value;
    }

    private static List<Request> requests(WikiChampionData champion) {
        List<Request> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (AbilitySlot slot : AbilitySlot.values()) {
            for (String skill : champion.skillNames(slot)) {
                String title = abilityPageTitle(champion.wikiName(), skill);
                if (seen.add(title)) {
                    out.add(new Request(slot, skill, title));
                }
            }
        }
        return out;
    }

    /** Holt alle Titel (in Blöcken zu max. 50) und fasst die Antworten zu einer zusammen. */
    private String fetchPages(List<String> titles) throws IOException {
        ObjectNode merged = Json.MAPPER.createObjectNode();
        ObjectNode query = merged.putObject("query");
        ArrayNode normalized = query.putArray("normalized");
        ArrayNode redirects = query.putArray("redirects");
        ArrayNode pages = query.putArray("pages");
        for (int i = 0; i < titles.size(); i += MAX_TITLES_PER_QUERY) {
            List<String> chunk = titles.subList(i, Math.min(titles.size(), i + MAX_TITLES_PER_QUERY));
            JsonNode q = queryNode(http.getText(queryUrl(chunk)));
            q.path("normalized").forEach(normalized::add);
            q.path("redirects").forEach(redirects::add);
            q.path("pages").forEach(pages::add);
        }
        return Json.MAPPER.writeValueAsString(merged);
    }

    private ParsedAbilities parseAbilities(WikiChampionData champion, List<Request> requests, String json)
            throws IOException {
        JsonNode query = queryNode(json);
        Map<String, String> normalized = titleMap(query.path("normalized"));
        Map<String, String> redirects = titleMap(query.path("redirects"));
        Map<String, JsonNode> pagesByTitle = new HashMap<>();
        for (JsonNode page : query.path("pages")) {
            pagesByTitle.put(page.path("title").asText(""), page);
        }

        Map<String, WikiAbility> byFinalTitle = new LinkedHashMap<>();
        boolean complete = true;
        for (Request req : requests) {
            String title = normalized.getOrDefault(req.title(), req.title().replace('_', ' '));
            title = redirects.getOrDefault(title, title);
            if (byFinalTitle.containsKey(title)) {
                continue;
            }
            JsonNode page = pagesByTitle.get(title);
            if (page == null) {
                complete = false; // Titel wurde nie abgefragt (z. B. umbenannte Fähigkeit)
                continue;
            }
            if (page.path("missing").asBoolean(false) || page.path("invalid").asBoolean(false)) {
                continue;
            }
            String content = page.path("revisions").path(0).path("slots").path("main").path("content").asText("");
            if (content.isBlank() || content.stripLeading().regionMatches(true, 0, "#REDIRECT", 0, 9)) {
                continue;
            }
            try {
                byFinalTitle.put(title, WikiAbilityParser.parse(content, champion.wikiName(), req.slot(),
                        req.skillName()));
            } catch (RuntimeException e) {
                // Eine nicht lesbare Seite soll die übrigen Fähigkeiten nicht verhindern.
            }
        }
        List<WikiAbility> result = new ArrayList<>(byFinalTitle.values());
        // stabil nach Slot sortieren (Formen innerhalb eines Slots bleiben in Modul-Reihenfolge)
        result.sort((a, b) -> Integer.compare(a.slot().ordinal(), b.slot().ordinal()));
        return new ParsedAbilities(result, complete);
    }

    private static Map<String, String> titleMap(JsonNode array) {
        Map<String, String> out = new HashMap<>();
        for (JsonNode n : array) {
            out.put(n.path("from").asText(""), n.path("to").asText(""));
        }
        return out;
    }

    /** {@code query}-Knoten einer API-Antwort; IOException bei Fehlerantworten. */
    private JsonNode queryNode(String json) throws IOException {
        JsonNode root;
        try {
            root = Json.MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IOException("Ungültige Wiki-Antwort (kein JSON): " + e.getMessage(), e);
        }
        if (root.has("error")) {
            throw new IOException("Wiki-API-Fehler: " + root.path("error").path("info").asText(
                    root.path("error").toString()));
        }
        JsonNode query = root.path("query");
        if (!query.path("pages").isArray()) {
            throw new IOException("Ungültige Wiki-Antwort: 'query.pages' fehlt");
        }
        return query;
    }

    private Map<Integer, WikiItems.Entry> parseItemModule(String json) throws IOException {
        String lua = moduleContent(json, ITEM_DATA_TITLE);
        try {
            Map<Integer, WikiItems.Entry> parsed = WikiItems.parseModule(lua);
            if (parsed.isEmpty()) {
                throw new IOException("Wiki-Itemdaten enthalten keine Items");
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            throw new IOException("Wiki-Itemdaten sind nicht lesbar: " + e.getMessage(), e);
        }
    }

    private Map<String, WikiChampionData> parseChampionModule(String json) throws IOException {
        String lua = moduleContent(json, CHAMPION_DATA_TITLE);
        try {
            Map<String, WikiChampionData> parsed = WikiChampionData.parseModule(lua);
            if (parsed.isEmpty()) {
                throw new IOException("Wiki-Championdaten enthalten keine Champions");
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            throw new IOException("Wiki-Championdaten sind nicht lesbar: " + e.getMessage(), e);
        }
    }

    private String moduleContent(String json, String title) throws IOException {
        JsonNode query = queryNode(json);
        for (JsonNode page : query.path("pages")) {
            JsonNode content = page.path("revisions").path(0).path("slots").path("main").path("content");
            if (content.isTextual() && !content.asText().isBlank()) {
                return content.asText();
            }
        }
        throw new IOException("Wiki-Seite " + title + " fehlt oder ist leer");
    }

    /** Inhalt der (einzigen) Seite einer Antwort; leer, wenn die Seite fehlt. */
    private String pageContent(String json) throws IOException {
        for (JsonNode page : queryNode(json).path("pages")) {
            JsonNode content = page.path("revisions").path(0).path("slots").path("main").path("content");
            if (content.isTextual()) {
                return content.asText();
            }
        }
        return "";
    }

    private static String encode(String title) {
        return URLEncoder.encode(title, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
