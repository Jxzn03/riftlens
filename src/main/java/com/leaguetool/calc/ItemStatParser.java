package com.leaguetool.calc;

import com.leaguetool.model.Item;
import com.leaguetool.util.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest die Werte eines Items aus.
 *
 * <p><b>Primärquelle</b> ist der {@code <stats>…</stats>}-Block der Beschreibung, weil die
 * Data-Dragon-{@code stats}-Map unvollständig ist (Ability Haste, Lethality, Magiedurchdringung usw.
 * fehlen dort). Jede Zeile (getrennt durch {@code <br>}) hat die Form "Zahl [%] Name", z. B.
 * {@code <attention>12</attention> Magic Penetration}; die Zahl darf in beliebigen Tags stehen
 * ({@code <attention>}, {@code <ornnBonus>} …). Erkannt werden die englischen Namen aus
 * {@code en_US}; ob ein "%" dasteht, entscheidet bei Lauftempo und Magiedurchdringung über die
 * flache bzw. prozentuale Variante.
 *
 * <p><b>Fallback</b> ist die Data-Dragon-{@code stats}-Map: Ohne (bzw. mit leerem) {@code <stats>}-Block
 * werden alle Map-Werte übernommen. Stehen im Block unbekannte Zeilen (z. B. andere Sprache), wird ein
 * Map-Wert nur übernommen, wenn seine Zahl genau eine dieser Zeilen belegt; nicht zugeordnete Map-Werte
 * (meist passive Effekte wie die Lebensregeneration von Doran's Shield) zählen nicht. Werte aus der
 * Beschreibung haben Vorrang. Ist der Block vollständig erkannt, wird die Map ignoriert.
 *
 * <p>Nicht Erkanntes landet in {@link ParsedItemStats#unparsed()}.
 */
public final class ItemStatParser {

    private static final Pattern STATS_BLOCK = Pattern.compile("(?is)<stats>(.*?)</stats>");
    private static final Pattern LINE = Pattern.compile("^([+-]?\\d+(?:\\.\\d+)?)\\s*(%)?\\s*(\\S.*)$");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /** Englischer Stat-Name (klein, normalisiert) → Typ ohne bzw. mit "%". */
    private static final Map<String, Alias> NAMES = buildNames();

    /** Data-Dragon-Schlüssel → Typ und Umrechnungsfaktor. */
    private static final Map<String, DdKey> DDRAGON_KEYS = buildDdragonKeys();

    private ItemStatParser() {
    }

    /** Ergebnisse je Item; Items sind pro Patch unveränderlich (schwache Schlüssel, damit alte Patches verfallen). */
    private static final Map<Item, ParsedItemStats> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    /** Werte eines Items (siehe Klassenbeschreibung); Ergebnisse werden zwischengespeichert. */
    public static ParsedItemStats parse(Item item) {
        ParsedItemStats cached = CACHE.get(item);
        if (cached != null) {
            return cached;
        }
        ParsedItemStats parsed = parseUncached(item);
        CACHE.put(item, parsed);
        return parsed;
    }

    private static ParsedItemStats parseUncached(Item item) {
        List<String> lines = statsLines(item.descriptionHtml());

        EnumMap<StatType, Double> fromDescription = new EnumMap<>(StatType.class);
        List<UnknownLine> unknown = new ArrayList<>();
        for (String line : lines) {
            Matcher m = LINE.matcher(line);
            if (!m.matches()) {
                unknown.add(new UnknownLine(line, Double.NaN, false));
                continue;
            }
            double value = Double.parseDouble(m.group(1));
            boolean percent = m.group(2) != null;
            StatType type = typeFor(m.group(3), percent);
            if (type == null) {
                unknown.add(new UnknownLine(line, value, percent));
            } else {
                fromDescription.merge(type, value, Double::sum);
            }
        }

        List<String> unparsed = new ArrayList<>();
        EnumMap<StatType, Double> fromDdragon = new EnumMap<>(StatType.class);
        boolean useFallback = lines.isEmpty() || !unknown.isEmpty();
        if (useFallback) {
            List<String> unknownKeys = new ArrayList<>();
            EnumMap<StatType, Double> candidates = new EnumMap<>(StatType.class);
            for (Map.Entry<String, Double> e : new TreeMap<>(item.stats()).entrySet()) {
                double raw = e.getValue();
                if (raw == 0.0) {
                    continue;
                }
                DdKey key = DDRAGON_KEYS.get(e.getKey());
                if (key == null) {
                    unknownKeys.add(e.getKey() + " = " + Text.num(raw));
                } else if (!fromDescription.containsKey(key.type())) {
                    candidates.merge(key.type(), round(raw * key.factor()), Double::sum);
                }
            }
            if (lines.isEmpty()) {
                // Kein stats-Block: die Data-Dragon-Map ist die einzige Quelle.
                fromDdragon.putAll(candidates);
            } else {
                // Nur Werte übernehmen, die eine unbekannte Zeile belegen; übrige Map-Werte sind meist
                // passive Effekte (z. B. Lebensregeneration von Doran's Shield) und zählen nicht.
                List<StatType> unmatched = new ArrayList<>(candidates.keySet());
                for (UnknownLine u : unknown) {
                    StatType covered = findCovering(u, unmatched, candidates);
                    if (covered != null) {
                        unmatched.remove(covered);
                        fromDdragon.put(covered, candidates.get(covered));
                    } else {
                        unparsed.add(u.text());
                    }
                }
            }
            unparsed.addAll(unknownKeys);
        }

        StatBlock stats = StatBlock.of(fromDescription).plus(StatBlock.of(fromDdragon));
        return new ParsedItemStats(stats, unparsed);
    }

    /**
     * Typ zu einem englischen Stat-Namen, oder {@code null}, wenn unbekannt.
     *
     * @param percent ob der Wert mit "%" angegeben ist
     */
    static StatType typeFor(String name, boolean percent) {
        Alias alias = NAMES.get(normalize(name));
        if (alias == null) {
            return null;
        }
        return percent ? alias.percent() : alias.flat();
    }

    /** Zeilen des {@code <stats>}-Blocks im Klartext; leer, wenn kein Block oder ein leerer Block da ist. */
    static List<String> statsLines(String html) {
        if (html == null || html.isEmpty()) {
            return List.of();
        }
        Matcher m = STATS_BLOCK.matcher(html);
        if (!m.find()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String line : Text.htmlToPlain(m.group(1)).split("\n")) {
            String s = SPACES.matcher(line.replace('•', ' ')).replaceAll(" ").strip();
            if (!s.isEmpty()) {
                lines.add(s);
            }
        }
        return lines;
    }

    private static String normalize(String name) {
        return Text.normalizeName(name);
    }

    private static StatType findCovering(UnknownLine line, List<StatType> candidates,
                                         Map<StatType, Double> values) {
        if (Double.isNaN(line.value())) {
            return null;
        }
        for (StatType t : candidates) {
            if (t.percent() == line.percent() && Math.abs(values.get(t) - line.value()) < 1e-6) {
                return t;
            }
        }
        return null;
    }

    /** Glättet Rundungsfehler aus der Umrechnung Bruch → Prozent (0.07 * 100 = 7.000000000000001). */
    private static double round(double value) {
        return Math.round(value * 1e6) / 1e6;
    }

    private static Map<String, Alias> buildNames() {
        Map<String, Alias> m = new HashMap<>();
        flat(m, StatType.HEALTH, "health");
        flat(m, StatType.MANA, "mana");
        flat(m, StatType.ATTACK_DAMAGE, "attack damage");
        flat(m, StatType.ABILITY_POWER, "ability power");
        flat(m, StatType.ADAPTIVE_FORCE, "adaptive force");
        flat(m, StatType.ARMOR, "armor", "armour");
        flat(m, StatType.MAGIC_RESIST, "magic resist", "magic resistance");
        flat(m, StatType.ABILITY_HASTE, "ability haste");
        flat(m, StatType.LETHALITY, "lethality");
        flat(m, StatType.GOLD_PER_10, "gold per 10 seconds", "gold per 10 sec");
        percentOnly(m, StatType.ATTACK_SPEED, "attack speed");
        percentOnly(m, StatType.CRIT_CHANCE, "critical strike chance", "critical strike", "crit chance");
        percentOnly(m, StatType.CRIT_DAMAGE, "critical strike damage", "crit damage");
        percentOnly(m, StatType.COOLDOWN_REDUCTION, "cooldown reduction");
        percentOnly(m, StatType.LIFE_STEAL, "life steal", "lifesteal");
        percentOnly(m, StatType.OMNIVAMP, "omnivamp");
        percentOnly(m, StatType.HEAL_SHIELD_POWER, "heal and shield power", "heal & shield power");
        percentOnly(m, StatType.TENACITY, "tenacity");
        percentOnly(m, StatType.HEALTH_REGEN, "base health regen", "base health regeneration");
        percentOnly(m, StatType.MANA_REGEN, "base mana regen", "base mana regeneration");
        both(m, StatType.MOVE_SPEED, StatType.MOVE_SPEED_PERCENT, "move speed", "movement speed");
        both(m, StatType.MAGIC_PENETRATION, StatType.MAGIC_PENETRATION_PERCENT, "magic penetration");
        both(m, null, StatType.ARMOR_PENETRATION, "armor penetration", "armour penetration");
        both(m, StatType.HEALTH_REGEN_FLAT, StatType.HEALTH_REGEN, "health regen", "health regeneration");
        both(m, StatType.HEALTH_REGEN_FLAT, null, "health regen per 5 seconds", "health regen per 5");
        both(m, StatType.MANA_REGEN_FLAT, StatType.MANA_REGEN, "mana regen", "mana regeneration");
        both(m, StatType.MANA_REGEN_FLAT, null, "mana regen per 5 seconds", "mana regen per 5");
        return Map.copyOf(m);
    }

    private static void flat(Map<String, Alias> m, StatType type, String... names) {
        both(m, type, null, names);
    }

    /** Prozentwerte: auch ohne "%" wird die Zahl als Prozent gelesen (es gibt keine flache Variante). */
    private static void percentOnly(Map<String, Alias> m, StatType type, String... names) {
        both(m, type, type, names);
    }

    private static void both(Map<String, Alias> m, StatType flat, StatType percent, String... names) {
        for (String name : names) {
            m.put(name, new Alias(flat, percent));
        }
    }

    private static Map<String, DdKey> buildDdragonKeys() {
        Map<String, DdKey> m = new HashMap<>();
        m.put("FlatHPPoolMod", new DdKey(StatType.HEALTH, 1));
        m.put("FlatMPPoolMod", new DdKey(StatType.MANA, 1));
        m.put("FlatPhysicalDamageMod", new DdKey(StatType.ATTACK_DAMAGE, 1));
        m.put("FlatMagicDamageMod", new DdKey(StatType.ABILITY_POWER, 1));
        m.put("FlatArmorMod", new DdKey(StatType.ARMOR, 1));
        m.put("FlatSpellBlockMod", new DdKey(StatType.MAGIC_RESIST, 1));
        m.put("FlatMovementSpeedMod", new DdKey(StatType.MOVE_SPEED, 1));
        // Brüche (0.25) → Prozent (25)
        m.put("PercentAttackSpeedMod", new DdKey(StatType.ATTACK_SPEED, 100));
        m.put("FlatCritChanceMod", new DdKey(StatType.CRIT_CHANCE, 100));
        m.put("PercentMovementSpeedMod", new DdKey(StatType.MOVE_SPEED_PERCENT, 100));
        m.put("PercentLifeStealMod", new DdKey(StatType.LIFE_STEAL, 100));
        // Data Dragon speichert Regeneration pro Sekunde, die Beschreibungen nennen sie pro 5 Sekunden
        m.put("FlatHPRegenMod", new DdKey(StatType.HEALTH_REGEN_FLAT, 5));
        m.put("FlatMPRegenMod", new DdKey(StatType.MANA_REGEN_FLAT, 5));
        return Map.copyOf(m);
    }

    private record Alias(StatType flat, StatType percent) {
    }

    private record DdKey(StatType type, double factor) {
    }

    private record UnknownLine(String text, double value, boolean percent) {
    }
}
