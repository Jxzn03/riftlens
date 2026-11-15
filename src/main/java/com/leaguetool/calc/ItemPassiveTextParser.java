package com.leaguetool.calc;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Erkennt immer aktive Item-Effekte in einer Textzeile (englisch, Wiki oder Data Dragon).
 *
 * <p>Eine Zeile muss als Ganzes einem bekannten Satzbau entsprechen. So greift bei bedingten Effekten ("After 5
 * seconds … increase …") kein Muster, und neue Items mit gewohntem Satzbau werden von selbst erkannt.
 */
public final class ItemPassiveTextParser {

    /** Erkannter Effekt ohne Item-Bezug. */
    public record Effect(StatType type, double value) {
    }

    private static final String N = "(\\d+(?:\\.\\d+)?)";
    private static final String TARGET = "(ap|ad|health|hsp|adaptive force)";
    private static final String SOURCE = "(bonus health from items|item health|bonus health|bonus mana|max mana|base ad|ms)";

    private static final Pattern PARENTHESIZED_NUMBER = Pattern.compile("\\(\\d+\\)");
    private static final Pattern FILLER = Pattern.compile("\\b(your|of|total|additional|the|an?)\\b");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private static final Pattern EQUAL_TO = Pattern.compile("gain (?:bonus )?" + TARGET + " equal to " + N + "% " + SOURCE);
    private static final Pattern AS_TARGET = Pattern.compile("gain " + N + "% " + SOURCE + " as (?:bonus )?" + TARGET);
    private static final Pattern AP_AMP = Pattern.compile("increases? ap by " + N + "%");
    private static final Pattern PER_BASE_MANA_REGEN = Pattern.compile(
            "gain " + N + "% hsp and " + N + " ap (?:for every|per) 100% base mana regen(?:eration)?");
    private static final Pattern HASTE_FROM_AD_SPLIT = Pattern.compile(
            "gain " + N + " \\(\\+ " + N + "%/" + N + "% bonus ad\\) ability haste");
    private static final Pattern HASTE_FROM_AD = Pattern.compile("gain " + N + " \\(\\+ " + N + "% bonus ad\\) ability haste");
    private static final Pattern BASIC_HASTE = Pattern.compile("gain " + N + " basic ability haste");
    private static final Pattern ULTIMATE_HASTE = Pattern.compile("gain " + N + " ultimate (?:ability )?haste");
    private static final Pattern IMMOBILIZE_HASTE = Pattern.compile(
            "abilities with immobilizing effects have their cooldown reduced equivalent to " + N + " ability haste");
    private static final Pattern IMMOBILIZE_HASTE_GAIN = Pattern.compile("gain " + N + " ability haste for abilities with immobilizing effects");

    private static final Pattern HAS_DIGIT = Pattern.compile("\\d");
    private static final Pattern STARTS_LIKE_STAT =Pattern.compile("^(gain|increases?)\\b.*");
    private static final Pattern STAT_WORD = Pattern.compile("\\b(ap|ad|health|mana|armor|magic resist(ance)?|ms|ability haste"
            + "|haste|hsp|adaptive force|attack speed|critical strike chance|lethality|magic penetration|armor penetration"
            + "|omnivamp|life steal|tenacity)\\b");
    private static final Pattern CONDITION_WORD = Pattern.compile("\\b(after|when|whenever|while|if|on|upon|against|per|for \\d"
            + "|seconds?|stack\\w*|takedowns?|nearby|attack(s|ing)?|hit|kill\\w*|damag\\w*|champions?|ally|allies|enem\\w*"
            + "|level\\w*|out combat|up to|based on|every|charges?|shields?|heal\\w*|regenerat\\w*|summoner)\\b");

    private static final Map<String, StatType> CONVERSIONS = Map.of(
            "ap<bonus health", StatType.AP_PER_BONUS_HEALTH,
            "ad<bonus health", StatType.AD_PER_BONUS_HEALTH,
            "ap<bonus mana", StatType.AP_PER_BONUS_MANA,
            "ad<max mana", StatType.AD_PER_MAX_MANA,
            "health<bonus mana", StatType.HEALTH_PER_BONUS_MANA,
            "ad<base ad", StatType.AD_PER_BASE_AD,
            "hsp<bonus mana", StatType.HSP_PER_BONUS_MANA,
            "adaptive force<ms", StatType.ADAPTIVE_PER_MOVE_SPEED,
            "health<bonus health from items", StatType.ITEM_HEALTH_AMP,
            "health<item health", StatType.ITEM_HEALTH_AMP);

    private ItemPassiveTextParser() {
    }

    /** Effekte der Zeile; leer, wenn sie keinem bekannten Satzbau entspricht. */
    public static List<Effect> parse(String line) {
        String s = normalize(line);
        Matcher m = EQUAL_TO.matcher(s);
        if (m.matches()) {
            return conversion(m.group(1), m.group(3), m.group(2));
        }
        m = AS_TARGET.matcher(s);
        if (m.matches()) {
            return conversion(m.group(3), m.group(2), m.group(1));
        }
        m = AP_AMP.matcher(s);
        if (m.matches()) {
            return List.of(new Effect(StatType.ABILITY_POWER_AMP, number(m, 1)));
        }
        m = PER_BASE_MANA_REGEN.matcher(s);
        if (m.matches()) {
            return List.of(new Effect(StatType.HSP_PER_100_BASE_MANA_REGEN, number(m, 1)),
                    new Effect(StatType.AP_PER_100_BASE_MANA_REGEN, number(m, 2)));
        }
        m = HASTE_FROM_AD_SPLIT.matcher(s);
        if (m.matches()) {
            return hasteFromAd(number(m, 1), number(m, 2), number(m, 3));
        }
        m = HASTE_FROM_AD.matcher(s);
        if (m.matches()) {
            return hasteFromAd(number(m, 1), number(m, 2), number(m, 2));
        }
        return simpleHaste(s);
    }

    /**
     * {@code true} für Zeilen, die wie ein dauerhafter Werte-Effekt aussehen, aber nicht erkannt wurden. Zeilen ohne
     * Zahl zählen nicht: In Data Dragon fehlt bei manchen Passiven der Wert, ihn liefert dann das Wiki.
     */
    public static boolean looksPermanent(String line) {
        String s = normalize(line);
        return parse(line).isEmpty() && HAS_DIGIT.matcher(s).find() && STARTS_LIKE_STAT.matcher(s).matches()
                && STAT_WORD.matcher(s).find() && !CONDITION_WORD.matcher(s).find();
    }

    private static List<Effect> simpleHaste(String s) {
        Map<Pattern, StatType> patterns = Map.of(BASIC_HASTE, StatType.BASIC_ABILITY_HASTE,
                ULTIMATE_HASTE, StatType.ULTIMATE_HASTE,
                IMMOBILIZE_HASTE, StatType.IMMOBILIZE_ABILITY_HASTE,
                IMMOBILIZE_HASTE_GAIN, StatType.IMMOBILIZE_ABILITY_HASTE);
        for (Map.Entry<Pattern, StatType> e : patterns.entrySet()) {
            Matcher m = e.getKey().matcher(s);
            if (m.matches()) {
                return List.of(new Effect(e.getValue(), number(m, 1)));
            }
        }
        return List.of();
    }

    private static List<Effect> hasteFromAd(double flat, double melee, double ranged) {
        return List.of(new Effect(StatType.ABILITY_HASTE, flat),
                new Effect(StatType.HASTE_PER_BONUS_AD_MELEE, melee),
                new Effect(StatType.HASTE_PER_BONUS_AD_RANGED, ranged));
    }

    private static List<Effect> conversion(String target, String source, String value) {
        StatType type = CONVERSIONS.get(target + "<" + source);
        return type == null ? List.of() : List.of(new Effect(type, Double.parseDouble(value)));
    }

    private static double number(Matcher m, int group) {
        return Double.parseDouble(m.group(group));
    }

    /** Einheitliche Schreibweise, damit Wiki- und Data-Dragon-Texte auf dieselben Muster passen. */
    static String normalize(String line) {
        String s = line.toLowerCase(Locale.ROOT).strip();
        s = PARENTHESIZED_NUMBER.matcher(s).replaceAll(" ");
        s = s.replace("maximum", "max").replace("attack damage", "ad").replace("ability power", "ap")
                .replace("heal and shield power", "hsp").replace("movement speed", "ms").replace("move speed", "ms")
                .replace("grants", "gain").replace("grant", "gain").replace("gains", "gain");
        s = FILLER.matcher(s).replaceAll(" ");
        s = SPACES.matcher(s).replaceAll(" ").strip();
        return s.endsWith(".") ? s.substring(0, s.length() - 1).strip() : s;
    }
}
