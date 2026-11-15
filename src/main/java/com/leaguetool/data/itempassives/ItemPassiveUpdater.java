package com.leaguetool.data.itempassives;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leaguetool.cache.FileCache;
import com.leaguetool.calc.ItemPassive;
import com.leaguetool.calc.ItemPassiveTextParser;
import com.leaguetool.calc.ItemPassives;
import com.leaguetool.calc.StatType;
import com.leaguetool.data.GameData;
import com.leaguetool.data.wiki.WikiClient;
import com.leaguetool.data.wiki.WikiItems;
import com.leaguetool.model.Item;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hält die Item-Passiven ({@link ItemPassives}) je Patch aktuell.
 *
 * <p>Bei jedem Laden der Spieldaten werden die Passiv-Texte aus Data Dragon und dem Wiki ausgewertet und gegen den
 * zuletzt gültigen Stand geprüft. Der Wiki-Stand wird bei einem neuen Patch sofort und sonst täglich neu geholt.
 * Änderungen werden automatisch übernommen. Bei Abweichungen gilt Data Dragon, weil es zum Patch gehört. Das Wiki
 * liefert die Werte, die in Data Dragon fehlen. Scheitert eine Quelle oder ist ihr Format offenbar neu, bleibt der
 * letzte Stand erhalten.
 */
public final class ItemPassiveUpdater {

    /**
     * Ergebnis einer Prüfung.
     *
     * @param patch     geprüfter Patch, leer vor der ersten Prüfung
     * @param checkedAt Datum der Prüfung (ISO)
     * @param count     Anzahl der aktiven Effekte
     * @param changes   automatisch übernommene Änderungen dieses Patches
     * @param review    dauerhaft wirkende Texte, die nicht ausgewertet werden konnten
     * @param notes     Hinweise zur Prüfung (Quellen, Abweichungen)
     */
    public record Report(String patch, String checkedAt, int count, List<String> changes, List<String> review,
                         List<String> notes) {
        static Report none() {
            return new Report("", "", ItemPassives.baseline().size(), List.of(), List.of(), List.of());
        }
    }

    /** Text einer Quelle zu einem Item: Name der Passive → Zeilen. */
    record SourceItem(String name, Map<String, List<String>> passives) {
    }

    record Merge(List<ItemPassive> passives, List<String> changes, List<String> review, List<String> notes) {
    }

    private record Key(int itemId, StatType type) {
    }

    private static final String STATE_KEY = "item-passives/state.json";
    private static final String HISTORY_KEY = "item-passives/history.json";
    private static final int MAX_HISTORY = 30;
    private static final double MAX_PLAUSIBLE_VALUE = 200;
    private static final double EPSILON = 1e-9;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern STATS_BLOCK = Pattern.compile("(?is)<stats>.*?</stats>");
    private static final Pattern PASSIVE_SEGMENT = Pattern.compile("(?is)<passive>(.*?)</passive>(.*?)(?=<passive>|<active>|</mainText>|$)");
    private static final Pattern BR = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    private final FileCache cache;
    private volatile Report latest = Report.none();

    public ItemPassiveUpdater(FileCache cache) {
        this.cache = cache;
    }

    /** Ergebnis der letzten Prüfung. */
    public Report latest() {
        return latest;
    }

    /**
     * Prüft den Stand für {@code data} und aktiviert das Ergebnis. Fehler führen nie zum Abbruch: Dann bleibt der letzte
     * gültige Stand aktiv.
     *
     * @param wiki Wiki-Client oder {@code null}, wenn das Wiki deaktiviert ist
     */
    public Report update(GameData data, WikiClient wiki) {
        List<String> sourceNotes = new ArrayList<>();
        List<ItemPassive> previous = ItemPassives.baseline();
        String previousPatch = "";
        try {
            JsonNode state = readState();
            if (state != null) {
                previous = passives(state.path("passives"));
                previousPatch = state.path("patch").asText("");
            }
            Map<Integer, SourceItem> wikiItems = wikiItems(wiki, !data.patch().equals(previousPatch), sourceNotes);
            Merge merge = merge(previous, dataDragonItems(data.items()), wikiItems);
            ItemPassives.install(merge.passives());
            List<String> notes = new ArrayList<>(sourceNotes);
            notes.addAll(merge.notes());
            List<String> changes = persist(data.patch(), previousPatch, merge);
            latest = new Report(data.patch(), LocalDate.now().toString(), merge.passives().size(), changes, merge.review(), notes);
        } catch (IOException | RuntimeException e) {
            ItemPassives.install(previous);
            latest = new Report(data.patch(), LocalDate.now().toString(), previous.size(), List.of(), List.of(),
                    List.of("Prüfung fehlgeschlagen, letzter Stand bleibt aktiv: " + e.getMessage()));
        }
        return latest;
    }

    private static Map<Integer, SourceItem> wikiItems(WikiClient wiki, boolean newPatch, List<String> notes) {
        if (wiki == null) {
            notes.add("Wiki deaktiviert: nur Werte aus Data Dragon geprüft");
            return null;
        }
        try {
            Map<Integer, SourceItem> items = new LinkedHashMap<>();
            for (WikiItems.Entry entry : wiki.itemData(newPatch).values()) {
                Map<String, List<String>> passives = new LinkedHashMap<>();
                for (WikiItems.Passive passive : entry.passives()) {
                    passives.computeIfAbsent(passive.name(), n -> new ArrayList<>()).addAll(passive.lines());
                }
                items.put(entry.id(), new SourceItem(entry.name(), passives));
            }
            return items;
        } catch (IOException e) {
            notes.add("Wiki nicht verfügbar: nur Werte aus Data Dragon geprüft (" + e.getMessage() + ")");
            return null;
        }
    }

    /** Liest die Passiv-Texte der auf Summoner's Rift verfügbaren Items aus den Beschreibungen. */
    static Map<Integer, SourceItem> dataDragonItems(List<Item> items) {
        Map<Integer, SourceItem> out = new LinkedHashMap<>();
        for (Item item : items) {
            if (!item.isOnSummonersRift()) {
                continue;
            }
            Map<String, List<String>> passives = new LinkedHashMap<>();
            Matcher m = PASSIVE_SEGMENT.matcher(STATS_BLOCK.matcher(item.descriptionHtml()).replaceAll(""));
            while (m.find()) {
                List<String> lines = passives.computeIfAbsent(plain(m.group(1)), n -> new ArrayList<>());
                for (String line : BR.split(m.group(2))) {
                    String text = plain(line);
                    if (!text.isBlank()) {
                        lines.add(text);
                    }
                }
            }
            out.put(item.id(), new SourceItem(item.name(), passives));
        }
        return out;
    }

    private static String plain(String html) {
        return TAG.matcher(html).replaceAll("").strip();
    }

    /**
     * Führt beide Quellen mit dem bisherigen Stand zusammen.
     *
     * @param dataDragon Items des Patches; sie bestimmen, welche Items es noch gibt
     * @param wiki       Items laut Wiki oder {@code null}, wenn es nicht verfügbar ist
     */
    static Merge merge(List<ItemPassive> previous, Map<Integer, SourceItem> dataDragon, Map<Integer, SourceItem> wiki) {
        Set<String> review = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();
        Map<Key, ItemPassive> fromDataDragon = recognize(dataDragon, dataDragon, review);
        Map<Key, ItemPassive> fromWiki = wiki == null ? Map.of() : recognize(wiki, dataDragon, review);
        long stillInGame = previous.stream().filter(p -> dataDragon.containsKey(p.itemId())).count();
        boolean wikiUsable = wiki != null && fromWiki.size() * 2 >= stillInGame;
        if (wiki != null && !wikiUsable) {
            notes.add("Wiki-Format offenbar geändert (nur " + fromWiki.size() + " Effekte erkannt): Wiki ignoriert");
        }

        Map<Key, ItemPassive> result = new LinkedHashMap<>();
        if (wikiUsable) {
            result.putAll(fromWiki);
        }
        fromDataDragon.forEach((key, passive) -> {
            ItemPassive known = result.get(key);
            if (known != null && Math.abs(known.value() - passive.value()) > EPSILON) {
                notes.add(passive.itemName() + ": Data Dragon " + key.type().formatValue(passive.value()) + ", Wiki "
                        + key.type().formatValue(known.value()) + " – Data Dragon verwendet");
            }
            result.put(key, known == null ? passive : new ItemPassive(known.itemId(), known.itemName(), known.passive(),
                    known.type(), passive.value()));
        });

        List<String> changes = new ArrayList<>();
        for (ItemPassive old : previous) {
            Key key = new Key(old.itemId(), old.type());
            ItemPassive current = result.get(key);
            if (current != null) {
                if (Math.abs(current.value() - old.value()) > EPSILON) {
                    changes.add(old.itemName() + ": " + old.type().label() + " " + old.type().formatValue(old.value())
                            + " → " + old.type().formatValue(current.value()));
                }
            } else if (!dataDragon.containsKey(old.itemId())) {
                changes.add("Item nicht mehr im Spiel: " + describe(old));
            } else if (!wikiUsable) {
                result.put(key, old);
            } else if (stillExists(old, dataDragon, wiki)) {
                result.put(key, old);
                review.add(old.itemName() + " (" + old.passive() + "): Text nicht mehr auswertbar, letzter Wert bleibt aktiv");
            } else {
                changes.add("Entfallen: " + describe(old));
            }
        }
        Set<Key> known = new LinkedHashSet<>();
        previous.forEach(p -> known.add(new Key(p.itemId(), p.type())));
        result.forEach((key, passive) -> {
            if (!known.contains(key)) {
                changes.add("Neu: " + describe(passive));
            }
        });
        return new Merge(List.copyOf(result.values()), changes, List.copyOf(review), notes);
    }

    private static boolean stillExists(ItemPassive old, Map<Integer, SourceItem> dataDragon, Map<Integer, SourceItem> wiki) {
        return hasPassive(dataDragon.get(old.itemId()), old.passive()) || hasPassive(wiki.get(old.itemId()), old.passive());
    }

    private static boolean hasPassive(SourceItem item, String passive) {
        return item != null && item.passives().containsKey(passive);
    }

    private static String describe(ItemPassive p) {
        return p.itemName() + " – " + p.type().label() + " " + p.type().formatValue(p.value());
    }

    private static Map<Key, ItemPassive> recognize(Map<Integer, SourceItem> source, Map<Integer, SourceItem> allowed,
                                                   Set<String> review) {
        Map<Key, ItemPassive> found = new LinkedHashMap<>();
        source.forEach((id, item) -> {
            if (!allowed.containsKey(id)) {
                return;
            }
            item.passives().forEach((passive, lines) -> {
                for (String line : lines) {
                    for (ItemPassiveTextParser.Effect effect : ItemPassiveTextParser.parse(line)) {
                        if (effect.value() > 0 && effect.value() <= MAX_PLAUSIBLE_VALUE) {
                            found.put(new Key(id, effect.type()),
                                    new ItemPassive(id, item.name(), passive, effect.type(), effect.value()));
                        } else {
                            review.add(item.name() + " (" + passive + "): unplausibler Wert in „" + line + "“");
                        }
                    }
                    if (ItemPassiveTextParser.looksPermanent(line)) {
                        review.add(item.name() + " (" + passive + "): " + line);
                    }
                }
            });
        });
        return found;
    }

    // ---------------------------------------------------------------- Speicher

    private JsonNode readState() {
        try {
            return cache.readText(FileCache.GLOBAL, STATE_KEY).map(ItemPassiveUpdater::parse).orElse(null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<ItemPassive> passives(JsonNode array) {
        List<ItemPassive> out = new ArrayList<>();
        for (JsonNode n : array) {
            try {
                out.add(new ItemPassive(n.path("itemId").asInt(), n.path("itemName").asText(), n.path("passive").asText(),
                        StatType.valueOf(n.path("type").asText().toUpperCase(Locale.ROOT)), n.path("value").asDouble()));
            } catch (IllegalArgumentException e) {
                // Wert einer älteren Programmversion: wird beim nächsten Abgleich neu erkannt
            }
        }
        return out;
    }

    /** Speichert Stand und Änderungsverlauf; liefert die Änderungen des aktuellen Patches. */
    private List<String> persist(String patch, String previousPatch, Merge merge) throws IOException {
        ObjectNode state = MAPPER.createObjectNode();
        state.put("patch", patch);
        ArrayNode array = state.putArray("passives");
        for (ItemPassive p : merge.passives()) {
            array.addObject().put("itemId", p.itemId()).put("itemName", p.itemName()).put("passive", p.passive())
                    .put("type", p.type().name()).put("value", p.value());
        }
        cache.writeText(FileCache.GLOBAL, STATE_KEY, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(state));

        ArrayNode history = readHistory();
        if (!merge.changes().isEmpty()) {
            ObjectNode entry = history.addObject();
            entry.put("patch", patch).put("date", LocalDate.now().toString());
            merge.changes().forEach(entry.putArray("changes")::add);
            while (history.size() > MAX_HISTORY) {
                history.remove(0);
            }
            cache.writeText(FileCache.GLOBAL, HISTORY_KEY, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(history));
        }
        List<String> changes = new ArrayList<>();
        for (JsonNode entry : history) {
            if (patch.equals(entry.path("patch").asText())) {
                entry.path("changes").forEach(c -> changes.add(c.asText()));
            }
        }
        return changes;
    }

    private ArrayNode readHistory() {
        try {
            JsonNode node = cache.readText(FileCache.GLOBAL, HISTORY_KEY).map(ItemPassiveUpdater::parse).orElse(null);
            return node instanceof ArrayNode array ? array : MAPPER.createArrayNode();
        } catch (IOException | RuntimeException e) {
            return MAPPER.createArrayNode();
        }
    }
}
