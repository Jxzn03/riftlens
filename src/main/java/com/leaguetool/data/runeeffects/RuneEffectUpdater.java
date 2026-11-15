package com.leaguetool.data.runeeffects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leaguetool.cache.FileCache;
import com.leaguetool.calc.RuneEffects;
import com.leaguetool.data.GameData;
import com.leaguetool.data.wiki.WikiClient;
import com.leaguetool.data.wiki.WikiRuneShards;
import com.leaguetool.model.Rune;
import com.leaguetool.model.RuneTree;
import com.leaguetool.util.Text;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Hält die Zahlen der Runen und Shards ({@link RuneEffects}) je Patch aktuell.
 *
 * <p>Läuft nur beim ersten Laden eines neuen Patches; danach gilt der gespeicherte Stand
 * ({@code cache/_global/rune-effects/state.json}). Runen kommen aus den Data-Dragon-Texten des Patches, Shards samt
 * Reihen aus der Wiki-Seite „Rune“. Ist ein Text nicht mehr auswertbar, bleibt der letzte Wert und die Rune steht unter
 * {@code review}. Erkennt der Abgleich weniger als die Hälfte der Runen, gilt das Format als geändert und der letzte
 * Stand bleibt komplett.
 */
public final class RuneEffectUpdater {

    /**
     * Ergebnis eines Abgleichs.
     *
     * @param changes automatisch übernommene Änderungen dieses Patches
     * @param review  Runen, deren Text nicht ausgewertet werden konnte (letzter Wert bleibt)
     * @param notes   Hinweise (Quellen, Wiki nicht verfügbar)
     */
    public record Report(String patch, String checkedAt, int count, List<String> changes, List<String> review,
                         List<String> notes) {
        static Report none() {
            return new Report("", "", RuneEffects.definitions().size(), List.of(), List.of(), List.of());
        }
    }

    record Result(Map<Integer, double[]> params, List<List<Integer>> rows, List<String> review, List<String> notes) {
    }

    private static final String STATE_KEY = "rune-effects/state.json";
    private static final String HISTORY_KEY = "rune-effects/history.json";
    private static final int MAX_HISTORY = 30;
    private static final double MAX_PLAUSIBLE_VALUE = 1000;
    private static final double EPSILON = 1e-9;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FileCache cache;
    private volatile Report latest = Report.none();

    public RuneEffectUpdater(FileCache cache) {
        this.cache = cache;
    }

    public Report latest() {
        return latest;
    }

    /**
     * Aktiviert den Stand für {@code data}. Beim ersten Laden eines Patches wird neu abgeglichen, sonst der gespeicherte
     * Stand verwendet. Fehler führen nie zum Abbruch.
     *
     * @param wiki     Wiki-Client oder {@code null}, wenn das Wiki aus ist (Shards behalten dann ihren Stand)
     * @param newPatch {@code true} beim ersten Start nach einem Patchwechsel
     */
    public Report update(GameData data, WikiClient wiki, boolean newPatch) {
        JsonNode state = readState();
        Map<Integer, double[]> previous = RuneEffects.defaults();
        List<List<Integer>> previousRows = RuneEffects.DEFAULT_SHARD_ROWS;
        String previousPatch = "";
        if (state != null) {
            previous.putAll(params(state.path("params")));
            previousRows = rows(state.path("shardRows"), previousRows);
            previousPatch = state.path("patch").asText("");
        }
        if (!newPatch && state != null && previousPatch.equals(data.patch())) {
            RuneEffects.install(previous, previousRows);
            latest = report(state.path("report"), data.patch());
            return latest;
        }
        try {
            List<String> notes = new ArrayList<>();
            WikiRuneShards.Shards shards = wikiShards(wiki, notes);
            Result result = recognize(texts(data), shards, previous, previousRows);
            notes.addAll(result.notes());
            RuneEffects.install(result.params(), result.rows());
            List<String> changes = changes(previous, previousRows, result);
            latest = new Report(data.patch(), LocalDate.now().toString(), result.params().size(), changes,
                    result.review(), notes);
            persist(data.patch(), result, latest);
        } catch (IOException | RuntimeException e) {
            RuneEffects.install(previous, previousRows);
            latest = new Report(data.patch(), LocalDate.now().toString(), previous.size(), List.of(), List.of(),
                    List.of("Abgleich fehlgeschlagen, letzter Stand bleibt aktiv: " + e.getMessage()));
        }
        return latest;
    }

    private static WikiRuneShards.Shards wikiShards(WikiClient wiki, List<String> notes) {
        if (wiki == null) {
            notes.add("Wiki deaktiviert: Shards behalten ihren letzten Stand");
            return null;
        }
        try {
            return wiki.runeShards();
        } catch (IOException e) {
            notes.add("Wiki nicht verfügbar, Shards behalten ihren letzten Stand (" + e.getMessage() + ")");
            return null;
        }
    }

    /** Klartext der Runen-Beschreibungen des Patches je ID. */
    static Map<Integer, String> texts(GameData data) {
        Map<Integer, String> out = new HashMap<>();
        for (RuneTree tree : data.runeTrees()) {
            tree.runes().forEach((Rune r) -> out.put(r.id(),
                    Text.htmlToPlain(r.longDesc()).replaceAll("\\s+", " ").strip()));
        }
        return out;
    }

    /**
     * Liest die Zahlen aus den Quellen; was nicht lesbar ist, behält den bisherigen Wert.
     *
     * @param shards Shards laut Wiki oder {@code null}
     */
    static Result recognize(Map<Integer, String> texts, WikiRuneShards.Shards shards, Map<Integer, double[]> previous,
                            List<List<Integer>> previousRows) {
        Map<Integer, double[]> params = new HashMap<>();
        previous.forEach((id, p) -> params.put(id, p.clone()));
        List<String> review = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int runes = 0;
        int recognized = 0;
        Map<Integer, double[]> found = new HashMap<>();
        for (RuneEffects.Definition d : RuneEffects.definitions()) {
            if (d.isShard()) {
                continue;
            }
            runes++;
            String text = texts.get(d.id());
            if (text == null) {
                notes.add(d.name() + ": nicht mehr im Spiel, letzter Wert bleibt");
                continue;
            }
            double[] values = match(d, text);
            if (values == null) {
                review.add(d.name() + ": Text nicht mehr auswertbar, letzter Wert bleibt aktiv");
            } else {
                found.put(d.id(), values);
                recognized++;
            }
        }
        if (recognized * 2 < runes) {
            notes.add("Data-Dragon-Format offenbar geändert (nur " + recognized + " von " + runes
                    + " Runen erkannt): letzter Stand bleibt");
        } else {
            params.putAll(found);
        }
        List<List<Integer>> rows = previousRows;
        if (shards != null) {
            rows = shards.rows();
            for (RuneEffects.Definition d : RuneEffects.definitions()) {
                Double value = d.isShard() ? shards.values().get(d.id()) : null;
                if (value != null && value > 0 && value <= MAX_PLAUSIBLE_VALUE) {
                    params.put(d.id(), new double[] {value});
                } else if (d.isShard() && rows.stream().anyMatch(r -> r.contains(d.id()))) {
                    review.add(d.name() + " (Shard): Wert im Wiki nicht lesbar, letzter Wert bleibt aktiv");
                }
            }
        }
        return new Result(params, rows, review, notes);
    }

    private static double[] match(RuneEffects.Definition d, String text) {
        Matcher m = d.source().matcher(text);
        if (!m.find() || m.groupCount() != d.defaults().length) {
            return null;
        }
        double[] values = new double[m.groupCount()];
        for (int i = 0; i < values.length; i++) {
            values[i] = Double.parseDouble(m.group(i + 1));
            if (values[i] <= 0 || values[i] > MAX_PLAUSIBLE_VALUE) {
                return null;
            }
        }
        return values;
    }

    private static List<String> changes(Map<Integer, double[]> previous, List<List<Integer>> previousRows, Result result) {
        List<String> changes = new ArrayList<>();
        for (RuneEffects.Definition d : RuneEffects.definitions()) {
            double[] before = previous.get(d.id());
            double[] after = result.params().get(d.id());
            if (before != null && after != null && !same(before, after)) {
                changes.add(d.name() + ": " + d.summary().apply(before) + " → " + d.summary().apply(after));
            }
        }
        if (!previousRows.equals(result.rows())) {
            changes.add("Shard-Reihen: " + previousRows + " → " + result.rows());
        }
        return changes;
    }

    private static boolean same(double[] a, double[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > EPSILON) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- Speicher

    private JsonNode readState() {
        try {
            return cache.readText(FileCache.GLOBAL, STATE_KEY).map(RuneEffectUpdater::parse).orElse(null);
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

    private static Map<Integer, double[]> params(JsonNode node) {
        Map<Integer, double[]> out = new HashMap<>();
        node.fields().forEachRemaining(e -> {
            try {
                int id = Integer.parseInt(e.getKey());
                double[] values = new double[e.getValue().size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = e.getValue().get(i).asDouble();
                }
                RuneEffects.definition(id).filter(d -> d.defaults().length == values.length)
                        .ifPresent(d -> out.put(id, values));
            } catch (NumberFormatException ignored) {
                // Eintrag einer älteren Programmversion
            }
        });
        return out;
    }

    private static List<List<Integer>> rows(JsonNode node, List<List<Integer>> fallback) {
        if (!node.isArray() || node.size() != 3) {
            return fallback;
        }
        List<List<Integer>> rows = new ArrayList<>();
        for (JsonNode row : node) {
            List<Integer> ids = new ArrayList<>();
            row.forEach(id -> ids.add(id.asInt()));
            rows.add(ids);
        }
        return rows;
    }

    private static Report report(JsonNode node, String patch) {
        return new Report(patch, node.path("checkedAt").asText(""), RuneEffects.definitions().size(),
                strings(node.path("changes")), strings(node.path("review")), strings(node.path("notes")));
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private void persist(String patch, Result result, Report report) throws IOException {
        ObjectNode state = MAPPER.createObjectNode();
        state.put("patch", patch);
        ObjectNode params = state.putObject("params");
        result.params().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            ArrayNode values = params.putArray(String.valueOf(e.getKey()));
            Arrays.stream(e.getValue()).forEach(values::add);
        });
        ArrayNode rows = state.putArray("shardRows");
        result.rows().forEach(row -> {
            ArrayNode r = rows.addArray();
            row.forEach(r::add);
        });
        ObjectNode rep = state.putObject("report");
        rep.put("checkedAt", report.checkedAt());
        report.changes().forEach(rep.putArray("changes")::add);
        report.review().forEach(rep.putArray("review")::add);
        report.notes().forEach(rep.putArray("notes")::add);
        cache.writeText(FileCache.GLOBAL, STATE_KEY, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(state));

        if (!report.changes().isEmpty()) {
            JsonNode existing = cache.readText(FileCache.GLOBAL, HISTORY_KEY).map(RuneEffectUpdater::parse).orElse(null);
            ArrayNode history = existing instanceof ArrayNode array ? array : MAPPER.createArrayNode();
            ObjectNode entry = history.addObject().put("patch", patch).put("date", LocalDate.now().toString());
            report.changes().forEach(entry.putArray("changes")::add);
            while (history.size() > MAX_HISTORY) {
                history.remove(0);
            }
            cache.writeText(FileCache.GLOBAL, HISTORY_KEY, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(history));
        }
    }
}
