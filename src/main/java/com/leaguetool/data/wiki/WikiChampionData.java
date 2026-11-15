package com.leaguetool.data.wiki;

import com.leaguetool.model.AbilitySlot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Champion-Eintrag aus {@code Module:ChampionData/data} des Wikis. Fehlende Basiswerte sind 0.
 *
 * @param wikiName          Schlüssel im Modul (Wiki-Name, z. B. "Bel'Veth", "Wukong")
 * @param apiName           {@code apiname}, entspricht der Data-Dragon-ID (z. B. "MonkeyKing")
 * @param id                Champion-Key ({@code id}; Sonderformen wie "Kled &amp; Skaarl" (240.1) abgeschnitten)
 * @param title             Titel, z. B. "The Nine-Tailed Fox"
 * @param resource          Ressource, z. B. "Mana"
 * @param rangeType         "Melee" oder "Ranged"
 * @param adaptiveType      "Physical" oder "Magic"
 * @param positions         {@code client_positions}, z. B. ["Middle"]
 * @param roles             {@code role}, z. B. ["Burst"]
 * @param asLvl             Angriffstempo-Wachstum in Prozent
 * @param attackCastTime    {@code attack_cast_time} (Sekunden), falls vorhanden
 * @param attackTotalTime   {@code attack_total_time} (Sekunden), falls vorhanden
 * @param attackDelayOffset {@code attack_delay_offset}, falls vorhanden
 * @param skills            Fähigkeitsnamen je Slot ({@code skill_i} → P, {@code skill_q} → Q …), in Modul-Reihenfolge
 */
public record WikiChampionData(
        String wikiName, String apiName, int id, String title, String resource, String rangeType,
        String adaptiveType, List<String> positions, List<String> roles,
        double hpBase, double hpLvl, double mpBase, double mpLvl, double armBase, double armLvl,
        double mrBase, double mrLvl, double hp5Base, double hp5Lvl, double mp5Base, double mp5Lvl,
        double damBase, double damLvl, double asBase, double asLvl, double asRatio, double range, double ms,
        OptionalDouble attackCastTime, OptionalDouble attackTotalTime, OptionalDouble attackDelayOffset,
        Map<AbilitySlot, List<String>> skills) {

    public WikiChampionData {
        positions = List.copyOf(positions);
        roles = List.copyOf(roles);
        EnumMap<AbilitySlot, List<String>> copy = new EnumMap<>(AbilitySlot.class);
        skills.forEach((slot, names) -> copy.put(slot, List.copyOf(names)));
        skills = Collections.unmodifiableMap(copy);
    }

    /**
     * Anteil der Angriffsanimation bis zum Treffer (Windup): {@code cast / total}, sonst
     * {@code 0.3 + attack_delay_offset}, sonst leer.
     */
    public Optional<Double> windupPercent() {
        if (attackCastTime.isPresent() && attackTotalTime.isPresent() && attackTotalTime.getAsDouble() > 0) {
            return Optional.of(attackCastTime.getAsDouble() / attackTotalTime.getAsDouble());
        }
        if (attackDelayOffset.isPresent()) {
            return Optional.of(0.3 + attackDelayOffset.getAsDouble());
        }
        return Optional.empty();
    }

    /** Fähigkeitsnamen eines Slots (leer, wenn unbekannt). */
    public List<String> skillNames(AbilitySlot slot) {
        return skills.getOrDefault(slot, List.of());
    }

    /**
     * Baut einen Eintrag aus dem geparsten Lua-Table.
     *
     * @throws IllegalArgumentException wenn {@code id}, {@code apiname} oder {@code stats} fehlen
     */
    public static WikiChampionData fromLua(String wikiName, Map<String, Object> entry) {
        Object idValue = entry.get("id");
        if (!(idValue instanceof Double idNumber)) {
            throw new IllegalArgumentException("Champion '" + wikiName + "': Feld 'id' fehlt oder ist keine Zahl");
        }
        String apiName = string(entry.get("apiname"));
        if (apiName.isBlank()) {
            throw new IllegalArgumentException("Champion '" + wikiName + "': Feld 'apiname' fehlt");
        }
        Map<String, Object> stats = map(entry.get("stats"));
        if (stats.isEmpty()) {
            throw new IllegalArgumentException("Champion '" + wikiName + "': Feld 'stats' fehlt");
        }
        Map<AbilitySlot, List<String>> skills = new EnumMap<>(AbilitySlot.class);
        putSkills(skills, AbilitySlot.P, entry.get("skill_i"));
        putSkills(skills, AbilitySlot.Q, entry.get("skill_q"));
        putSkills(skills, AbilitySlot.W, entry.get("skill_w"));
        putSkills(skills, AbilitySlot.E, entry.get("skill_e"));
        putSkills(skills, AbilitySlot.R, entry.get("skill_r"));

        return new WikiChampionData(
                wikiName, apiName, (int) idNumber.doubleValue(),
                string(entry.get("title")), string(entry.get("resource")), string(entry.get("rangetype")),
                string(entry.get("adaptivetype")), strings(entry.get("client_positions")), strings(entry.get("role")),
                num(stats, "hp_base"), num(stats, "hp_lvl"), num(stats, "mp_base"), num(stats, "mp_lvl"),
                num(stats, "arm_base"), num(stats, "arm_lvl"), num(stats, "mr_base"), num(stats, "mr_lvl"),
                num(stats, "hp5_base"), num(stats, "hp5_lvl"), num(stats, "mp5_base"), num(stats, "mp5_lvl"),
                num(stats, "dam_base"), num(stats, "dam_lvl"), num(stats, "as_base"), num(stats, "as_lvl"),
                num(stats, "as_ratio"), num(stats, "range"), num(stats, "ms"),
                optional(stats, "attack_cast_time"), optional(stats, "attack_total_time"),
                optional(stats, "attack_delay_offset"), skills);
    }

    /**
     * Parst den kompletten Modul-Inhalt. Schlüssel der Ergebnis-Map ist {@link #apiName()} (Data-Dragon-ID).
     * Fehlerhafte Einzeleinträge werden übersprungen. Teilen sich mehrere Einträge einen {@code apiname}
     * (z. B. "Kled" und "Kled &amp; Skaarl"), gewinnt der mit ganzzahliger {@code id}, sonst der erste.
     *
     * @throws LuaTableParser.LuaParseException bei Syntaxfehlern im Modul
     */
    public static Map<String, WikiChampionData> parseModule(String luaSource) {
        Map<String, Object> root = LuaTableParser.parseTable(luaSource);
        Map<String, WikiChampionData> result = new LinkedHashMap<>();
        Map<String, Boolean> integralId = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : root.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?>)) {
                continue;
            }
            WikiChampionData data;
            boolean integral;
            try {
                Map<String, Object> entry = map(e.getValue());
                data = fromLua(e.getKey(), entry);
                double rawId = (Double) entry.get("id");
                integral = rawId == Math.rint(rawId);
            } catch (RuntimeException ex) {
                continue;
            }
            Boolean existingIntegral = integralId.get(data.apiName());
            if (existingIntegral == null || (!existingIntegral && integral)) {
                result.put(data.apiName(), data);
                integralId.put(data.apiName(), integral);
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- Hilfen für Lua-Werte

    private static void putSkills(Map<AbilitySlot, List<String>> skills, AbilitySlot slot, Object value) {
        List<String> names = strings(value);
        if (!names.isEmpty()) {
            skills.put(slot, names);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String string(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Double d) {
            return WikiExpr.format(d);
        }
        return value.toString();
    }

    /** String, Liste oder Map (Werte in Reihenfolge) → Liste nicht leerer Strings. */
    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !string(o).isBlank()) {
                    out.add(string(o));
                }
            }
        } else if (value instanceof Map<?, ?> m) {
            for (Object o : m.values()) {
                if (o != null && !string(o).isBlank()) {
                    out.add(string(o));
                }
            }
        } else if (value != null && !string(value).isBlank()) {
            out.add(string(value));
        }
        return out;
    }

    private static double num(Map<String, Object> stats, String key) {
        return stats.get(key) instanceof Double d ? d : 0;
    }

    private static OptionalDouble optional(Map<String, Object> stats, String key) {
        return stats.get(key) instanceof Double d ? OptionalDouble.of(d) : OptionalDouble.empty();
    }
}
