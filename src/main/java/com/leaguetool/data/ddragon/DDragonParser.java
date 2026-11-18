package com.leaguetool.data.ddragon;

import com.fasterxml.jackson.databind.JsonNode;
import com.leaguetool.model.Ability;
import com.leaguetool.model.AbilitySlot;
import com.leaguetool.model.BaseStats;
import com.leaguetool.model.Champion;
import com.leaguetool.model.ChampionDetail;
import com.leaguetool.model.ChampionInfo;
import com.leaguetool.model.Item;
import com.leaguetool.model.ItemGold;
import com.leaguetool.model.Rune;
import com.leaguetool.model.RuneTree;
import com.leaguetool.model.SummonerSpell;
import com.leaguetool.util.Text;
import com.leaguetool.util.Json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Wandelt Data-Dragon-JSON in Modell-Records um. Zustandslos und threadsicher. */
public final class DDragonParser {

    public DDragonParser() {
    }

    /** {@code api/versions.json}: neueste Version zuerst. */
    public List<String> parseVersions(String json) throws IOException {
        List<String> versions = new ArrayList<>();
        for (JsonNode v : Json.MAPPER.readTree(json)) {
            versions.add(v.asText());
        }
        return versions;
    }

    /** {@code champion.json}: alle Champions, nach Name sortiert. */
    public List<Champion> parseChampions(String json) throws IOException {
        JsonNode data = Json.MAPPER.readTree(json).path("data");
        List<Champion> result = new ArrayList<>();
        for (JsonNode c : data) {
            result.add(parseChampion(c));
        }
        result.sort(Comparator.comparing(Champion::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /** {@code champion/{id}.json}: Champion mit Passiv und Q/W/E/R. */
    public ChampionDetail parseChampionDetail(String json) throws IOException {
        JsonNode data = Json.MAPPER.readTree(json).path("data");
        Iterator<JsonNode> it = data.elements();
        if (!it.hasNext()) {
            throw new IOException("Champion-Detail ohne Daten");
        }
        JsonNode c = it.next();
        Champion champion = parseChampion(c);

        List<Ability> abilities = new ArrayList<>();
        JsonNode passive = c.path("passive");
        if (!passive.isMissingNode()) {
            String file = passive.path("image").path("full").asText("");
            abilities.add(new Ability(champion.id(), AbilitySlot.P,
                    file.endsWith(".png") ? file.substring(0, file.length() - 4) : file,
                    passive.path("name").asText(""),
                    Text.htmlToPlain(passive.path("description").asText("")),
                    1, List.of(), List.of(), "", List.of(),
                    file, passive.path("image").path("group").asText("passive")));
        }
        JsonNode spells = c.path("spells");
        AbilitySlot[] slots = {AbilitySlot.Q, AbilitySlot.W, AbilitySlot.E, AbilitySlot.R};
        for (int i = 0; i < spells.size() && i < slots.length; i++) {
            JsonNode s = spells.get(i);
            List<Double> costs = doubles(s.path("cost"));
            abilities.add(new Ability(champion.id(), slots[i],
                    s.path("id").asText(""),
                    s.path("name").asText(""),
                    Text.htmlToPlain(s.path("description").asText("")),
                    s.path("maxrank").asInt(costs.size()),
                    doubles(s.path("cooldown")),
                    costs,
                    resolveCostType(s.path("costType").asText(""), costs, champion.resource()),
                    doubles(s.path("range")),
                    s.path("image").path("full").asText(""),
                    s.path("image").path("group").asText("spell")));
        }
        return new ChampionDetail(champion,
                Text.htmlToPlain(c.path("lore").asText("")),
                abilities,
                Json.strings(c.path("allytips")),
                Json.strings(c.path("enemytips")));
    }

    /** {@code item.json}: alle Items, nach Name sortiert. */
    public List<Item> parseItems(String json) throws IOException {
        JsonNode data = Json.MAPPER.readTree(json).path("data");
        List<Item> result = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : data.properties()) {
            int id;
            try {
                id = Integer.parseInt(e.getKey());
            } catch (NumberFormatException ex) {
                continue;
            }
            result.add(parseItem(id, e.getValue()));
        }
        result.sort(Comparator.comparing(Item::name, String.CASE_INSENSITIVE_ORDER).thenComparingInt(Item::id));
        return result;
    }

    /** {@code runesReforged.json}: Runenbäume in Data-Dragon-Reihenfolge. */
    public List<RuneTree> parseRuneTrees(String json) throws IOException {
        List<RuneTree> trees = new ArrayList<>();
        for (JsonNode t : Json.MAPPER.readTree(json)) {
            int treeId = t.path("id").asInt();
            List<List<Rune>> slots = new ArrayList<>();
            JsonNode slotsNode = t.path("slots");
            for (int slot = 0; slot < slotsNode.size(); slot++) {
                List<Rune> runes = new ArrayList<>();
                for (JsonNode r : slotsNode.get(slot).path("runes")) {
                    runes.add(new Rune(r.path("id").asInt(), r.path("key").asText(""), r.path("name").asText(""),
                            r.path("shortDesc").asText(""), r.path("longDesc").asText(""),
                            r.path("icon").asText(""), treeId, slot));
                }
                slots.add(runes);
            }
            trees.add(new RuneTree(treeId, t.path("key").asText(""), t.path("name").asText(""),
                    t.path("icon").asText(""), slots));
        }
        return trees;
    }

    /** {@code summoner.json}: alle Beschwörerzauber, nach Name sortiert. */
    public List<SummonerSpell> parseSummonerSpells(String json) throws IOException {
        JsonNode data = Json.MAPPER.readTree(json).path("data");
        List<SummonerSpell> result = new ArrayList<>();
        for (JsonNode s : data) {
            result.add(new SummonerSpell(
                    s.path("id").asText(""),
                    parseIntOr(s.path("key").asText(""), -1),
                    s.path("name").asText(""),
                    Text.htmlToPlain(s.path("description").asText("")),
                    doubles(s.path("cooldown")),
                    s.path("summonerLevel").asInt(0),
                    Json.strings(s.path("modes")),
                    doubles(s.path("range")),
                    s.path("image").path("full").asText("")));
        }
        result.sort(Comparator.comparing(SummonerSpell::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(SummonerSpell::id));
        return result;
    }

    private Champion parseChampion(JsonNode c) {
        JsonNode info = c.path("info");
        JsonNode s = c.path("stats");
        BaseStats stats = new BaseStats(
                s.path("hp").asDouble(), s.path("hpperlevel").asDouble(),
                s.path("mp").asDouble(), s.path("mpperlevel").asDouble(),
                s.path("movespeed").asDouble(),
                s.path("armor").asDouble(), s.path("armorperlevel").asDouble(),
                s.path("spellblock").asDouble(), s.path("spellblockperlevel").asDouble(),
                s.path("attackrange").asDouble(),
                s.path("hpregen").asDouble(), s.path("hpregenperlevel").asDouble(),
                s.path("mpregen").asDouble(), s.path("mpregenperlevel").asDouble(),
                s.path("crit").asDouble(), s.path("critperlevel").asDouble(),
                s.path("attackdamage").asDouble(), s.path("attackdamageperlevel").asDouble(),
                s.path("attackspeed").asDouble(), s.path("attackspeedperlevel").asDouble());
        return new Champion(
                c.path("id").asText(""),
                parseIntOr(c.path("key").asText(""), -1),
                c.path("name").asText(""),
                c.path("title").asText(""),
                Text.htmlToPlain(c.path("blurb").asText("")),
                Json.strings(c.path("tags")),
                c.path("partype").asText(""),
                new ChampionInfo(info.path("attack").asInt(), info.path("defense").asInt(),
                        info.path("magic").asInt(), info.path("difficulty").asInt()),
                stats,
                c.path("image").path("full").asText(""));
    }

    private Item parseItem(int id, JsonNode i) {
        JsonNode gold = i.path("gold");
        Map<String, Double> stats = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : i.path("stats").properties()) {
            if (e.getValue().isNumber()) {
                stats.put(e.getKey(), e.getValue().asDouble());
            }
        }
        Set<Integer> maps = new HashSet<>();
        for (Map.Entry<String, JsonNode> e : i.path("maps").properties()) {
            if (e.getValue().asBoolean(false)) {
                int mapId = parseIntOr(e.getKey(), -1);
                if (mapId >= 0) {
                    maps.add(mapId);
                }
            }
        }
        boolean inStore = i.path("inStore").asBoolean(true) && !i.path("hideFromAll").asBoolean(false);
        return new Item(id,
                i.path("name").asText(""),
                i.path("plaintext").asText(""),
                i.path("description").asText(""),
                new ItemGold(gold.path("base").asInt(), gold.path("total").asInt(), gold.path("sell").asInt(),
                        gold.path("purchasable").asBoolean(false)),
                Json.strings(i.path("tags")),
                stats,
                ints(i.path("from")),
                ints(i.path("into")),
                maps,
                inStore,
                i.path("depth").asInt(1),
                i.path("requiredChampion").asText(""),
                i.path("consumed").asBoolean(false),
                i.path("image").path("full").asText(""),
                i.path("specialRecipe").asInt(0));
    }

    /**
     * Data Dragon liefert {@code costType} oft als Template ({@code " {{ abilityresourcename }}"}).
     * Dann wird die Ressource des Champions eingesetzt; "No Cost" bzw. Kosten 0 ergeben einen Leerstring.
     */
    static String resolveCostType(String raw, List<Double> costs, String championResource) {
        boolean free = costs.isEmpty() || costs.stream().allMatch(c -> c == 0.0);
        String s = raw == null ? "" : raw.replace("{{ abilityresourcename }}", championResource).trim();
        if (s.equalsIgnoreCase("No Cost") || (free && s.isEmpty())) {
            return "";
        }
        if (s.contains("{{")) {
            return championResource;
        }
        return s.isEmpty() ? championResource : s;
    }

    private static List<Double> doubles(JsonNode array) {
        List<Double> out = new ArrayList<>();
        for (JsonNode n : array) {
            if (n.isNumber()) {
                out.add(n.asDouble());
            } else if (n.isTextual()) {
                try {
                    out.add(Double.parseDouble(n.asText()));
                } catch (NumberFormatException ignored) {
                    // z. B. "self" als Reichweite
                }
            }
        }
        return out;
    }

    private static List<Integer> ints(JsonNode array) {
        List<Integer> out = new ArrayList<>();
        for (JsonNode n : array) {
            int v = n.isNumber() ? n.asInt() : parseIntOr(n.asText(), Integer.MIN_VALUE);
            if (v != Integer.MIN_VALUE) {
                out.add(v);
            }
        }
        return out;
    }

    private static int parseIntOr(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
