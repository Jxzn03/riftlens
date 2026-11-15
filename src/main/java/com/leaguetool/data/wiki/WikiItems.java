package com.leaguetool.data.wiki;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Passiven der Items aus {@code Module:ItemData/data}. */
public final class WikiItems {

    /**
     * Passive eines Items.
     *
     * @param name  Name, leer wenn das Wiki keinen führt
     * @param lines Beschreibung als Klartext-Zeilen
     */
    public record Passive(String name, List<String> lines) {
    }

    /** Item mit seinen Passiven. */
    public record Entry(int id, String name, List<Passive> passives) {
    }

    private static final String REFERENCE = "=>";
    private static final int MAX_REFERENCE_DEPTH = 5;

    private WikiItems() {
    }

    /**
     * @return Item-ID → Eintrag; Einträge ohne ID fehlen
     * @throws LuaTableParser.LuaParseException bei einem nicht lesbaren Modul
     */
    static Map<Integer, Entry> parseModule(String lua) {
        Map<Integer, Entry> items = new LinkedHashMap<>();
        if (!(LuaTableParser.parse(lua) instanceof Map<?, ?> root)) {
            return items;
        }
        for (Map.Entry<?, ?> e : root.entrySet()) {
            if (e.getValue() instanceof Map<?, ?> item && item.get("id") instanceof Number id) {
                items.putIfAbsent(id.intValue(), new Entry(id.intValue(), String.valueOf(e.getKey()), passives(item, root)));
            }
        }
        return items;
    }

    private static List<Passive> passives(Map<?, ?> item, Map<?, ?> root) {
        List<Passive> passives = new ArrayList<>();
        if (item.get("effects") instanceof Map<?, ?> effects) {
            for (Map.Entry<?, ?> e : effects.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (key.startsWith("pass") && resolve(e.getValue(), key, root) instanceof Map<?, ?> passive) {
                    passives.add(new Passive(passive.get("name") instanceof String n ? n : "", lines(passive)));
                }
            }
        }
        return passives;
    }

    /** Folgt Verweisen wie {@code "=>Winter's Approach"}: Die Passive steht dann beim genannten Item. */
    private static Object resolve(Object value, String key, Map<?, ?> root) {
        Object current = value;
        for (int depth = 0; depth < MAX_REFERENCE_DEPTH && current instanceof String s && s.startsWith(REFERENCE); depth++) {
            current = root.get(s.substring(REFERENCE.length())) instanceof Map<?, ?> target
                    && target.get("effects") instanceof Map<?, ?> effects ? effects.get(key) : null;
        }
        return current;
    }

    private static List<String> lines(Map<?, ?> passive) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<?, ?> e : passive.entrySet()) {
            if (String.valueOf(e.getKey()).startsWith("description") && e.getValue() instanceof String text) {
                lines.addAll(List.of(plain(text).split("\n")));
            }
        }
        lines.removeIf(String::isBlank);
        return lines;
    }

    /** {@code {{rd|Nahkampf|Fernkampf}}} behält beide Werte, sonst bliebe nur der Fernkampf-Wert stehen. */
    private static String plain(String text) {
        return WikiMarkup.toPlain(text, (name, positional, named) ->
                name.equals("rd") && positional.size() >= 2 ? positional.get(0) + "/" + positional.get(1) : null);
    }
}
