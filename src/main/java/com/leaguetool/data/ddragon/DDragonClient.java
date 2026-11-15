package com.leaguetool.data.ddragon;

import com.leaguetool.cache.FileCache;
import com.leaguetool.data.http.HttpFetcher;
import com.leaguetool.model.Champion;
import com.leaguetool.model.ChampionDetail;
import com.leaguetool.model.Item;
import com.leaguetool.model.RuneTree;
import com.leaguetool.model.SummonerSpell;
import com.leaguetool.util.Patches;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Client für Riots Data Dragon (statische Spieldaten). Patch-Dateien sind unveränderlich und werden
 * cache-first geladen; {@code versions.json} wird netz-first mit Cache-Fallback geladen.
 */
public final class DDragonClient {

    public static final String BASE_URL = "https://ddragon.leagueoflegends.com";

    private static final Duration VERSIONS_MAX_AGE = Duration.ofHours(6);
    private static final String VERSIONS_KEY = "ddragon/versions.json";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_]+");

    private final HttpFetcher http;
    private final FileCache cache;
    private final String locale;
    private final String baseUrl;
    private final DDragonParser parser = new DDragonParser();

    private volatile boolean versionsFromStaleCache;

    public DDragonClient(HttpFetcher http, FileCache cache, String locale) {
        this(http, cache, locale, BASE_URL);
    }

    /** Konstruktor mit abweichender Basis-URL (Tests). */
    public DDragonClient(HttpFetcher http, FileCache cache, String locale, String baseUrl) {
        if (locale == null || !locale.matches("[a-z]{2}_[A-Z]{2}")) {
            throw new IllegalArgumentException("Ungültige Data-Dragon-Sprache: " + locale);
        }
        this.http = http;
        this.cache = cache;
        this.locale = locale;
        this.baseUrl = baseUrl;
    }

    public String locale() {
        return locale;
    }

    /** Alle regulären Versionen, neueste zuerst. */
    public List<String> versions() throws IOException {
        FileCache.CacheResult r = cache.getFreshOrStale(FileCache.GLOBAL, VERSIONS_KEY, VERSIONS_MAX_AGE, () -> {
            String text = http.getText(baseUrl + "/api/versions.json");
            regularVersions(text); // validieren, bevor gecacht wird
            return text;
        });
        versionsFromStaleCache = r.stale();
        try {
            return regularVersions(r.content());
        } catch (IOException e) {
            cache.delete(FileCache.GLOBAL, VERSIONS_KEY);
            throw e;
        }
    }

    private List<String> regularVersions(String json) throws IOException {
        List<String> versions = parser.parseVersions(json).stream().filter(Patches::isRegularVersion).toList();
        if (versions.isEmpty()) {
            throw new IOException("versions.json enthält keine gültigen Versionen");
        }
        return versions;
    }

    /**
     * Aktueller Patch = erste Version in versions.json. Ohne Netz und ohne gecachte versions.json wird
     * der neueste Patch-Ordner im Cache verwendet.
     */
    public String latestPatch() throws IOException {
        try {
            return versions().get(0);
        } catch (IOException e) {
            List<String> cached = cache.cachedPatches();
            if (!cached.isEmpty()) {
                versionsFromStaleCache = true;
                return cached.get(0);
            }
            throw e;
        }
    }

    /** {@code true}, wenn die letzte Versionsabfrage mangels Netz aus dem Cache kam. */
    public boolean isOffline() {
        return versionsFromStaleCache;
    }

    /** {@code true}, wenn die Übersichtsdaten (Champions, Items, Runen, Spells) dieses Patches lokal vorliegen. */
    public boolean isCached(String patch) {
        return Stream.of("champion.json", "item.json", "runesReforged.json", "summoner.json")
                .allMatch(f -> cache.contains(patch, dataKey(f)));
    }

    /** Patches, deren Übersichtsdaten vollständig im Cache liegen (neueste zuerst). */
    public List<String> cachedPatchesWithData() throws IOException {
        return cache.cachedPatches().stream().filter(this::isCached).toList();
    }

    public List<Champion> champions(String patch) throws IOException {
        return patchFile(patch, "champion.json", json -> nonEmpty(parser.parseChampions(json), "champion.json"));
    }

    public ChampionDetail championDetail(String patch, String championId) throws IOException {
        if (!SAFE_ID.matcher(championId).matches()) {
            throw new IllegalArgumentException("Ungültige Champion-ID: " + championId);
        }
        return patchFile(patch, "champion/" + championId + ".json", parser::parseChampionDetail);
    }

    public List<Item> items(String patch) throws IOException {
        return patchFile(patch, "item.json", json -> nonEmpty(parser.parseItems(json), "item.json"));
    }

    public List<RuneTree> runeTrees(String patch) throws IOException {
        return patchFile(patch, "runesReforged.json",
                json -> nonEmpty(parser.parseRuneTrees(json), "runesReforged.json"));
    }

    public List<SummonerSpell> summonerSpells(String patch) throws IOException {
        return patchFile(patch, "summoner.json", json -> nonEmpty(parser.parseSummonerSpells(json), "summoner.json"));
    }

    /**
     * Lädt eine Patch-Datei cache-first. Antworten werden erst nach erfolgreichem Parsen gecacht; ein
     * unbrauchbarer Cache-Eintrag (z. B. abgeschnitten) wird verworfen und neu geladen.
     */
    private <T> T patchFile(String patch, String file, JsonParser<T> parse) throws IOException {
        if (!Patches.isRegularVersion(patch)) {
            throw new IllegalArgumentException("Ungültige Patch-Version: " + patch);
        }
        String key = dataKey(file);
        Optional<String> cached = cache.readText(patch, key);
        if (cached.isPresent()) {
            try {
                return parse.parse(cached.get());
            } catch (IOException | RuntimeException corrupt) {
                cache.delete(patch, key);
            }
        }
        String fresh = http.getText(baseUrl + "/cdn/" + patch + "/data/" + locale + "/" + file);
        T result = parse.parse(fresh);
        cache.writeText(patch, key, fresh);
        return result;
    }

    private static <T> List<T> nonEmpty(List<T> list, String file) throws IOException {
        if (list.isEmpty()) {
            throw new IOException(file + " enthält keine Daten");
        }
        return list;
    }

    @FunctionalInterface
    private interface JsonParser<T> {
        T parse(String json) throws IOException;
    }

    private String dataKey(String file) {
        return "data/" + locale + "/" + file;
    }
}
