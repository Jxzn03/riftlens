package com.leaguetool.data.lolalytics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.leaguetool.util.Json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest den Seitenzustand, den Lolalytics in jede Seite einbettet ({@code <script type="qwik/json">}). Er enthält
 * alle Champions, auch die, deren Zeilen die Seite erst beim Scrollen rendert.
 *
 * <p>Aufbau: {@code objs} ist eine flache Liste, Werte in Objekten und Listen sind Base-36-Verweise auf andere
 * Einträge. {@code extract…} löst die benötigten Teile auf und erzeugt ein kompaktes JSON für den Cache,
 * {@code parse…} liest dieses Json.MAPPER. Ändert die Seite ihren Aufbau, gibt es eine klare Fehlermeldung statt
 * falscher Werte.
 */
final class LolalyticsState {

    private static final Pattern STATE = Pattern.compile("<script type=\"qwik/json\">(.*?)</script>", Pattern.DOTALL);
    private static final Pattern REFERENCE = Pattern.compile("[0-9a-z]+");
    private static final int MAX_DEPTH = 12;
    /** Mehr Einträge pro Liste braucht keine Rangliste; hält den Cache klein. */
    private static final int MAX_ENTRIES = 60;
    /** Optional, damit eine geänderte Seite nur die Delta-Anzeige kostet und nicht Build und Draft. */
    private static final List<String> DELTA_COLUMNS = List.of("d1", "d2");
    /** Tier-Nummer des Seitenzustands → Anzeige; 0 = ohne Tier. */
    private static final List<String> TIERS = List.of("", "S+", "S", "S-", "A+", "A", "A-", "B+", "B", "B-", "C+", "C",
            "C-", "D+", "D", "D-");

    private LolalyticsState() {
    }

    // ------------------------------------------------------------------ Tierliste

    static String extractTierList(String html) throws IOException {
        JsonNode objects = objects(html);
        JsonNode list = find(objects, "cid", "analysed", "avgWr");
        JsonNode page = find(objects, "champPath", "patch");
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.set("patch", resolve(objects, page.get("patch"), 0));
        out.set("analysed", resolve(objects, list.get("analysed"), 0));
        out.set("avgWr", resolve(objects, list.get("avgWr"), 0));
        out.set("slugs", resolve(objects, page.get("champPath"), 0));
        ObjectNode rows = out.putObject("rows");
        JsonNode resolved = resolve(objects, list.get("cid"), 0);
        for (Map.Entry<String, JsonNode> e : resolved.properties()) {
            JsonNode row = e.getValue();
            ObjectNode compact = rows.putObject(e.getKey());
            for (String field : List.of("tier", "lane", "pctLane", "wr", "pr", "br", "pbi", "games")) {
                compact.set(field, row.path(field));
            }
        }
        if (rows.isEmpty()) {
            throw new IOException("Lolalytics: Tierliste enthält keine Champions (Seite geändert?)");
        }
        return Json.MAPPER.writeValueAsString(out);
    }

    static LolalyticsFullTierList parseTierList(String json) throws IOException {
        JsonNode root = Json.MAPPER.readTree(json);
        Map<Integer, LolalyticsFullTierList.Row> rows = new HashMap<>();
        for (Map.Entry<String, JsonNode> e : root.path("rows").properties()) {
            JsonNode r = e.getValue();
            int tierRank = r.path("tier").asInt(0);
            String tier = tierRank > 0 && tierRank < TIERS.size() ? TIERS.get(tierRank) : "";
            rows.put(Integer.valueOf(e.getKey()), new LolalyticsFullTierList.Row(tier.isEmpty() ? 0 : tierRank, tier,
                    r.path("lane").asText(""), r.path("pctLane").asDouble() / 100.0, r.path("wr").asDouble() / 100.0,
                    r.path("pr").asDouble() / 100.0,
                    r.path("br").asDouble() / 100.0, r.path("pbi").asInt(), r.path("games").asInt()));
        }
        Map<Integer, String> slugs = new HashMap<>();
        for (Map.Entry<String, JsonNode> e : root.path("slugs").properties()) {
            slugs.put(Integer.valueOf(e.getKey()), e.getValue().asText());
        }
        return new LolalyticsFullTierList(root.path("patch").asText(""), root.path("analysed").asLong(),
                root.path("avgWr").asDouble() / 100.0, rows, slugs);
    }

    // ------------------------------------------------------------------ Champion-Seite

    private static final List<String> BUILD_LISTS = List.of("startSet", "builtBootSet3", "boots", "skillOrder", "skill6",
            "skill15", "spells");
    /**
     * Itemplätze (Boots zählen nicht mit): 1–2 für „Erstes/Zweites Item“, 3–5 für „Items nach dem Core-Build“. Optional,
     * damit eine geänderte Seite nur diese Anzeige kostet; ältere Cache-Einträge ohne 1–2 bleiben gültig.
     */
    private static final List<Integer> ITEM_SLOTS = List.of(1, 2, 3, 4, 5);

    static String extractBuild(String html) throws IOException {
        JsonNode objects = objects(html);
        JsonNode build = find(objects, "skillOrder", "itemSets", "header");
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        JsonNode header = resolve(objects, build.get("header"), 0);
        for (String field : List.of("n", "wr", "pr", "br")) {
            out.set(field, header.path(field));
        }
        for (String name : BUILD_LISTS) {
            ArrayNode limited = out.putArray(name);
            int count = 0;
            for (JsonNode entry : resolve(objects, build.get(name), 0)) {
                if (count++ == MAX_ENTRIES) {
                    break;
                }
                limited.add(entry);
            }
        }
        for (int slot : ITEM_SLOTS) {
            // Vollständig: Die Summe über die Itemplätze soll alle Zeilen enthalten.
            out.set("item" + slot, build.has("item" + slot) ? resolve(objects, build.get("item" + slot), 0) : JsonNodeFactory.instance.arrayNode());
        }
        JsonNode summary = resolve(objects, build.get("summary"), 0);
        out.set("pagePick", summary.path("pick").path("runes"));
        out.set("pageWin", summary.path("win").path("runes"));
        out.set("runes", resolve(objects, build.get("runes"), 0).path("stats"));
        out.set("enemy", compactPairs(resolve(objects, build.get("enemy_h"), 0), resolve(objects, build.get("enemy"), 0), DELTA_COLUMNS));
        out.set("damage", header.path("damage"));
        JsonNode time = resolve(objects, build.get("sidebar"), 0).path("time");
        out.set("time", time.path("time"));
        out.set("timeWin", time.path("timeWin"));
        return Json.MAPPER.writeValueAsString(out);
    }

    /** Antwort der Team-Schnittstelle; sie liefert dieselben Spalten wie die Matchups der Champion-Seite. */
    static String extractTeam(String body) throws IOException {
        JsonNode root;
        try {
            root = Json.MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IOException("Lolalytics: Antwort der Team-Schnittstelle ist kein JSON (geändert oder blockiert?)");
        }
        if (root == null || !root.has("team")) {
            throw new IOException("Lolalytics: Team-Daten fehlen in der Antwort (Schnittstelle geändert?)");
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.set("team", compactPairs(root.path("team_h"), root.path("team"), List.of()));
        return Json.MAPPER.writeValueAsString(out);
    }

    static LolalyticsPairs parseTeam(String json) throws IOException {
        return pairs(Json.MAPPER.readTree(json).path("team"));
    }

    /**
     * Hält je Paarung Champion, Winrate und Spiele; die Spalten werden über die Kopfzeile gefunden.
     *
     * @param optional weitere Spalten, die hinten angehängt werden; fehlt eine, steht dort {@code null}
     */
    private static ObjectNode compactPairs(JsonNode header, JsonNode lanes, List<String> optional) throws IOException {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        if (!lanes.isObject() || lanes.isEmpty()) {
            return out;
        }
        int id = column(header, "id");
        int winRate = column(header, "wr");
        int games = column(header, "n");
        List<Integer> extra = optional.stream().map(name -> indexOf(header, name)).toList();
        for (Map.Entry<String, JsonNode> lane : lanes.properties()) {
            ArrayNode rows = out.putArray(lane.getKey());
            for (JsonNode row : lane.getValue()) {
                ArrayNode compact = rows.addArray().add(row.path(id)).add(row.path(winRate)).add(row.path(games));
                extra.forEach(column -> compact.add(column < 0 ? NullNode.instance : row.path(column)));
            }
        }
        return out;
    }

    private static int column(JsonNode header, String name) throws IOException {
        int index = indexOf(header, name);
        if (index < 0) {
            throw new IOException("Lolalytics: Spalte fehlt in den Paarungen (Seite geändert?): " + name);
        }
        return index;
    }

    private static int indexOf(JsonNode header, String name) {
        for (int i = 0; i < header.size(); i++) {
            if (name.equals(header.path(i).asText())) {
                return i;
            }
        }
        return -1;
    }

    private static Double fractionOrNull(JsonNode value) {
        return value.isNumber() ? value.asDouble() / 100.0 : null;
    }

    private static LolalyticsPairs pairs(JsonNode lanes) {
        Map<String, Map<Integer, LolalyticsPairs.Stat>> byLane = new HashMap<>();
        for (Map.Entry<String, JsonNode> lane : lanes.properties()) {
            Map<Integer, LolalyticsPairs.Stat> rows = new HashMap<>();
            for (JsonNode row : lane.getValue()) {
                rows.put(row.path(0).asInt(), new LolalyticsPairs.Stat(row.path(1).asDouble() / 100.0, row.path(2).asInt(),
                        fractionOrNull(row.path(3)), fractionOrNull(row.path(4))));
            }
            byLane.put(lane.getKey(), rows);
        }
        return new LolalyticsPairs(byLane);
    }

    private static List<LolalyticsBuild.LengthBucket> gameLength(JsonNode games, JsonNode wins) {
        List<LolalyticsBuild.LengthBucket> buckets = new ArrayList<>();
        for (int bucket = 1; games.has(String.valueOf(bucket)); bucket++) {
            String key = String.valueOf(bucket);
            buckets.add(new LolalyticsBuild.LengthBucket(wins.path(key).asInt(), games.path(key).asInt()));
        }
        return buckets;
    }

    static LolalyticsBuild parseBuild(String json) throws IOException {
        JsonNode root = Json.MAPPER.readTree(json);
        Map<Integer, LolalyticsBuild.Entry> runes = new HashMap<>();
        Map<Integer, LolalyticsBuild.Entry> subRunes = new HashMap<>();
        List<Map<Integer, LolalyticsBuild.Entry>> shardRows = new ArrayList<>();
        for (int row = 0; row < LolalyticsBuild.SHARD_ROWS; row++) {
            shardRows.add(new HashMap<>());
        }
        for (Map.Entry<String, JsonNode> e : root.path("runes").properties()) {
            // Je Rune: [Pickrate, Winrate, Spiele]; der erste Eintrag gilt im Hauptbaum, der zweite im Sekundärbaum
            // (Keystones haben nur den ersten). Shards (IDs 5000–5999) stehen im selben Block: die mittlere Reihe mit
            // Endung f (z. B. 5010f), Offensive und Defensive als reine Zahl (z. B. 5005, 5013).
            String key = e.getKey();
            if (key.matches("5\\d{3}f?") && e.getValue().path(0).isArray()) {
                String id = key.substring(0, 4);
                shardRows.get(shardRow(key)).put(Integer.valueOf(id), runeEntry(id, e.getValue().path(0)));
                continue;
            }
            if (!key.matches("\\d+")) {
                continue;
            }
            JsonNode first = e.getValue().path(0);
            if (first.isArray()) {
                runes.put(Integer.valueOf(e.getKey()), runeEntry(e.getKey(), first));
            }
            JsonNode second = e.getValue().path(1);
            if (second.isArray()) {
                subRunes.put(Integer.valueOf(e.getKey()), runeEntry(e.getKey(), second));
            }
        }
        List<LolalyticsBuild.RunePage> pages = new ArrayList<>();
        for (String name : List.of("pagePick", "pageWin")) {
            JsonNode page = root.path(name);
            if (page.path("set").isObject()) {
                pages.add(new LolalyticsBuild.RunePage(ints(page.path("set").path("pri")),
                        ints(page.path("set").path("sec")), ints(page.path("set").path("mod")),
                        page.path("wr").asDouble() / 100.0, page.path("n").asInt(), "pagePick".equals(name)));
            }
        }
        return new LolalyticsBuild(root.path("n").asInt(), root.path("wr").asDouble() / 100.0,
                root.path("pr").asDouble() / 100.0, root.path("br").asDouble() / 100.0,
                entries(root.path("startSet")), entries(root.path("builtBootSet3")), entries(root.path("boots")),
                runes, subRunes, shardRows, pages, entries(root.path("skillOrder")), entries(root.path("skill6")),
                entries(root.path("spells")), pairs(root.path("enemy")),
                new LolalyticsBuild.Damage(root.path("damage").path("physical").asDouble(),
                        root.path("damage").path("magic").asDouble(), root.path("damage").path("true").asDouble()),
                gameLength(root.path("time"), root.path("timeWin")),
                ITEM_SLOTS.stream().map(slot -> new LolalyticsBuild.ItemSlot(slot, entries(root.path("item" + slot)))).toList(),
                entries(root.path("skill15")));
    }

    /** Shards der Offensive als reine Zahl (Aatrox: 5008 92 %, 5005 7 %, 5007 1,1 %, zusammen 100 %). */
    private static final Set<String> OFFENSE_SHARDS = Set.of("5005", "5007", "5008");

    /**
     * Reihe eines Shard-Schlüssels: Endung f ist die mittlere Reihe (Flex), die Offensiv-Shards als reine Zahl die obere,
     * alle übrigen reinen Zahlen die untere (Defensive; 5001, 5011, 5013).
     */
    static int shardRow(String key) {
        if (key.endsWith("f")) {
            return 1;
        }
        return OFFENSE_SHARDS.contains(key) ? 0 : 2;
    }

    /** Eintrag einer Rune aus {@code [Pickrate, Winrate, Spiele]} (Prozent). */
    private static LolalyticsBuild.Entry runeEntry(String key, JsonNode stat) {
        return new LolalyticsBuild.Entry(key, stat.path(1).asDouble() / 100.0, stat.path(0).asDouble() / 100.0, stat.path(2).asInt());
    }

    /** Listen der Form {@code [Schlüssel, Winrate, Pickrate, Spiele, …]}. Leere Schlüssel fallen weg. */
    private static List<LolalyticsBuild.Entry> entries(JsonNode list) {
        List<LolalyticsBuild.Entry> entries = new ArrayList<>();
        for (JsonNode e : list) {
            String key = e.path(0).asText("");
            if (!key.isEmpty()) {
                // Item-Listen haben als 5. Spalte die durchschnittliche Kaufminute.
                entries.add(new LolalyticsBuild.Entry(key, e.path(1).asDouble() / 100.0, e.path(2).asDouble() / 100.0,
                        e.path(3).asInt(), e.path(4).asInt(0)));
            }
        }
        return entries;
    }

    private static List<Integer> ints(JsonNode list) {
        List<Integer> values = new ArrayList<>();
        list.forEach(v -> values.add(v.asInt()));
        return values;
    }

    // ------------------------------------------------------------------ Seitenzustand

    private static JsonNode objects(String html) throws IOException {
        Matcher m = STATE.matcher(html);
        if (!m.find()) {
            throw new IOException("Lolalytics: Seitenzustand nicht gefunden (Seite geändert oder Zugriff blockiert)");
        }
        JsonNode objects = Json.MAPPER.readTree(m.group(1).replace("\\x3C", "<")).path("objs");
        if (!objects.isArray() || objects.isEmpty()) {
            throw new IOException("Lolalytics: Seitenzustand ist leer (Seite geändert?)");
        }
        return objects;
    }

    private static JsonNode find(JsonNode objects, String... fields) throws IOException {
        for (JsonNode candidate : objects) {
            boolean match = candidate.isObject();
            for (String field : fields) {
                match = match && candidate.has(field);
            }
            if (match) {
                return candidate;
            }
        }
        throw new IOException("Lolalytics: erwartete Daten nicht gefunden (Seite geändert?): " + String.join(", ", fields));
    }

    private static JsonNode resolve(JsonNode objects, JsonNode reference, int depth) {
        if (reference == null || !reference.isTextual() || depth > MAX_DEPTH) {
            return reference == null || depth > MAX_DEPTH ? NullNode.getInstance() : reference;
        }
        Matcher m = REFERENCE.matcher(reference.textValue());
        if (!m.lookingAt()) {
            return reference;
        }
        JsonNode target = objects.path(Integer.parseInt(m.group(), 36));
        if (target.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> e : target.properties()) {
                copy.set(e.getKey(), resolve(objects, e.getValue(), depth + 1));
            }
            return copy;
        }
        if (target.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            target.forEach(v -> copy.add(resolve(objects, v, depth + 1)));
            return copy;
        }
        return target.isMissingNode() ? NullNode.getInstance() : target;
    }
}
