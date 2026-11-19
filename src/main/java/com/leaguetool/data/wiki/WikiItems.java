package com.leaguetool.data.wiki;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Passiven der Items aus {@code Module:ItemData/data}. */
public final class WikiItems {

    /**
     * Passive eines Items.
     *
     * @param name     Name, leer wenn das Wiki keinen führt
     * @param lines    Beschreibung als Klartext-Zeilen
     * @param cooldown Abklingzeit in Sekunden als Text (z. B. {@code "1.5"}, {@code "198 to 99"}), leer ohne
     */
    public record Passive(String name, List<String> lines, String cooldown) {

        public Passive(String name, List<String> lines) {
            this(name, lines, "");
        }
    }

    /** Abklingzeit im Beschreibungstext, z. B. „(1.5 second cooldown“ oder „40-second cooldown“. */
    private static final Pattern TEXT_COOLDOWN =
            Pattern.compile("(\\d+(?:\\.\\d+)?(?:\\s*(?:-|–|to)\\s*\\d+(?:\\.\\d+)?)?)[ -]seconds? cooldown");

    /** Item mit seinen Passiven und Aktiven (gleich aufgebaut, nur Passiven zählen für den Build-Rechner). */
    public record Entry(int id, String name, List<Passive> passives, List<Passive> actives) {

        public Entry(int id, String name, List<Passive> passives) {
            this(id, name, passives, List.of());
        }
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
                items.putIfAbsent(id.intValue(), new Entry(id.intValue(), String.valueOf(e.getKey()),
                        effects(item, root, "pass"), effects(item, root, "act")));
            }
        }
        return items;
    }

    /** Effekte, deren Schlüssel mit {@code prefix} beginnt ({@code pass}, {@code pass2} … oder {@code act}). */
    private static List<Passive> effects(Map<?, ?> item, Map<?, ?> root, String prefix) {
        List<Passive> passives = new ArrayList<>();
        if (item.get("effects") instanceof Map<?, ?> effects) {
            for (Map.Entry<?, ?> e : effects.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (key.startsWith(prefix) && resolve(e.getValue(), key, root) instanceof Map<?, ?> passive) {
                    List<String> lines = lines(passive);
                    passives.add(new Passive(passive.get("name") instanceof String n ? n : "", lines, cooldown(passive, lines)));
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

    /** Feld {@code cd} des Wikis, sonst die erste Abklingzeit im Text; leer ohne. */
    private static String cooldown(Map<?, ?> passive, List<String> lines) {
        Object cd = passive.get("cd");
        if (cd instanceof Number n) {
            return n.toString().replaceAll("\\.0$", "");
        }
        if (cd instanceof String s && !s.isBlank()) {
            return plain(s).trim();
        }
        Matcher m = TEXT_COOLDOWN.matcher(String.join(" ", lines));
        return m.find() ? m.group(1) : "";
    }

    /** {@code {{rd|Nahkampf|Fernkampf}}} behält beide Werte, sonst bliebe nur der Fernkampf-Wert stehen. */
    private static String plain(String text) {
        return WikiMarkup.toPlain(text, (name, positional, named) ->
                name.equals("rd") && positional.size() >= 2 ? positional.get(0) + "/" + positional.get(1) : null);
    }
}
