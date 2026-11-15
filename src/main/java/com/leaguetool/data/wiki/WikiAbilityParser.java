package com.leaguetool.data.wiki;

import com.leaguetool.model.AbilitySlot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Baut eine {@link WikiAbility} aus einer Seite {@code Template:Data_<Champion>/<Fähigkeit>}. */
public final class WikiAbilityParser {

    private static final Pattern NUMBERED = Pattern.compile("^([a-z]+?)(\\d*)$");

    private WikiAbilityParser() {
    }

    /** Wie {@link #parse(String, String, AbilitySlot, String)} ohne Ersatzwerte. */
    public static WikiAbility parse(String wikitext) {
        return parse(wikitext, null, null, null);
    }

    /**
     * Parst eine Ability-Seite.
     *
     * @param fallbackChampion Champion, falls {@code champion} fehlt
     * @param fallbackSlot     Slot, falls {@code skill} fehlt oder unbekannt ist
     * @param fallbackName     Name, falls weder {@code name} noch ein positionales Argument vorhanden ist
     * @throws IllegalArgumentException bei unlesbarer Seite oder unbekanntem Slot
     */
    public static WikiAbility parse(String wikitext, String fallbackChampion, AbilitySlot fallbackSlot,
                                    String fallbackName) {
        WikiTemplateParser.TemplateCall call = WikiTemplateParser.parseCall(wikitext);
        Map<String, String> p = call.params();

        AbilitySlot slot = slotFromSkill(p.get("skill")).orElse(fallbackSlot);
        if (slot == null) {
            throw new IllegalArgumentException("Unbekannter Fähigkeiten-Slot: '" + p.get("skill") + "'");
        }
        String name = firstNonBlank(WikiMarkup.toPlain(p.get("name")),
                call.positional().isEmpty() ? null : WikiMarkup.toPlain(call.positional().get(0)), fallbackName);
        String champion = firstNonBlank(WikiMarkup.toPlain(p.get("champion")), fallbackChampion);

        Map<Integer, String> levelingParams = numbered(p, "leveling");
        int ranks = detectRanks(levelingParams, p).orElse(WikiLevelingParser.defaultRanks(slot));

        List<String> descriptions = new ArrayList<>();
        for (String d : numbered(p, "description").values()) {
            String plain = WikiMarkup.toPlain(d);
            if (!plain.isEmpty()) {
                descriptions.add(plain);
            }
        }
        List<WikiLeveling> levelings = new ArrayList<>();
        for (String l : levelingParams.values()) {
            levelings.addAll(WikiLevelingParser.parse(l, ranks));
        }

        String cooldownRaw = p.getOrDefault("cooldown", "");
        // Statische Abklingzeit: Haste verringert sie laut Wiki nicht.
        boolean staticCooldown = cooldownRaw.isBlank() && !p.getOrDefault("static", "").isBlank();
        if (staticCooldown) {
            cooldownRaw = p.get("static");
        }
        String range = WikiMarkup.toPlain(p.get("range"));
        if (range.isEmpty()) {
            range = WikiMarkup.toPlain(p.get("target range"));
        }
        return new WikiAbility(
                champion, slot, name, String.join("\n\n", descriptions), levelings,
                WikiMarkup.toPlain(cooldownRaw), WikiLevelingParser.parseRankValues(cooldownRaw, ranks),
                WikiMarkup.toPlain(p.get("cost")), WikiMarkup.toPlain(p.get("costtype")), range,
                WikiMarkup.toPlain(p.get("cast time")), WikiMarkup.toPlain(p.get("damagetype")),
                WikiMarkup.toPlain(p.get("targeting")), WikiMarkup.toPlain(p.get("affects")),
                WikiMarkup.toPlain(p.get("spellshield")), WikiMarkup.toPlain(p.get("projectile")), staticCooldown);
    }

    /** {@code skill}-Parameter → Slot: I/P → P, Q, W, E, R. */
    public static Optional<AbilitySlot> slotFromSkill(String skill) {
        if (skill == null) {
            return Optional.empty();
        }
        return switch (WikiMarkup.toPlain(skill).trim().toUpperCase(Locale.ROOT)) {
            case "I", "P", "PASSIVE", "INNATE" -> Optional.of(AbilitySlot.P);
            case "Q" -> Optional.of(AbilitySlot.Q);
            case "W" -> Optional.of(AbilitySlot.W);
            case "E" -> Optional.of(AbilitySlot.E);
            case "R" -> Optional.of(AbilitySlot.R);
            default -> Optional.empty();
        };
    }

    /** Eigene Rangzahl aus Leveling, Abklingzeit oder Kosten (z. B. {@code {{ap|80 to 285 6}}}). */
    private static OptionalInt detectRanks(Map<Integer, String> levelingParams, Map<String, String> p) {
        for (String l : levelingParams.values()) {
            OptionalInt r = WikiLevelingParser.detectRanks(l);
            if (r.isPresent()) {
                return r;
            }
        }
        OptionalInt r = WikiLevelingParser.detectRanks(p.get("cooldown"));
        return r.isPresent() ? r : WikiLevelingParser.detectRanks(p.get("cost"));
    }

    /** Parameter {@code prefix}, {@code prefix2}, {@code prefix3} … nach Nummer sortiert (ohne Nummer = 1). */
    static Map<Integer, String> numbered(Map<String, String> params, String prefix) {
        Map<Integer, String> out = new TreeMap<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            Matcher m = NUMBERED.matcher(e.getKey());
            if (m.matches() && m.group(1).equals(prefix)) {
                int index = m.group(2).isEmpty() ? 1 : Integer.parseInt(m.group(2));
                out.put(index, e.getValue());
            }
        }
        return out;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }
}
