package com.leaguetool.data.wiki;

import com.leaguetool.model.AbilitySlot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort-Auswertung der rangabhängigen Werte einer Fähigkeit.
 *
 * <p>Quelle sind {@code {{st|Label|Wert|Label2|Wert2}}}-Templates (Parameter {@code leveling},
 * {@code leveling2}, …). Ein Wert besteht typischerweise aus einem {@code {{ap|...}}} für den Basiswert und
 * Skalierungen in der Form {@code (+ X% STAT)}.
 *
 * <ul>
 *   <li>{@code {{ap|A to B}}}: linear auf die Rangzahl interpoliert; {@code {{ap|A to B N}}} mit eigener
 *       Rangzahl N</li>
 *   <li>{@code {{ap|a|b|c|d|e}}}: explizite Werte; einfache Ausdrücke wie {@code 35*2} werden ausgewertet</li>
 *   <li>einzelne Zahl oder einzelner Ausdruck: gilt für alle Ränge; ohne {@code {{ap}}} zählt nur eine Zahl am
 *       Anfang des Werts (außerhalb von Skalierungen und {@code {{pp}}}) als Basiswert, sonst bleibt
 *       {@link WikiLeveling#valuesPerRank()} leer</li>
 *   <li>Skalierungen: {@code (+ 50% AP)}, {@code (+ {{ap|50*2}}% AP)}, {@code (+ 20 – 40% AP)},
 *       {@code (+ 20 / 30 / 40% AP)}</li>
 * </ul>
 *
 * <p>Nicht abgedeckt (bleibt nur im Klartext {@link WikiLeveling#text()}): Werte je Champion-Level
 * ({@code {{pp|...}}}), Skalierungen ohne Prozentangabe (z. B. "(+ 0.5 per 100 AP)"), bedingte Werte
 * innerhalb eines Textes sowie mehrere Basiswerte in einem Wert (nur der erste zählt).
 */
public final class WikiLevelingParser {

    /** Platzhalter für ausgewertete {@code {{ap|...}}} (Private-Use-Zeichen, überstehen trim/strip). */
    private static final char M_START = '\uE000';
    private static final char M_END = '\uE001';
    /** Platzhalter für Werte je Champion-Level ({@code {{pp|...}}}); zählen nie als Rangwerte. */
    private static final char P_START = (char) 0xE002;
    private static final char P_END = (char) 0xE003;
    private static final Pattern MARKER = Pattern.compile(M_START + "(\\d+)" + M_END);
    private static final Pattern ONLY_MARKER = Pattern.compile("^" + M_START + "(\\d+)" + M_END + "$");
    private static final Pattern PP_MARKER = Pattern.compile(P_START + "(\\d+)" + P_END);
    private static final Pattern RATIO_GROUP = Pattern.compile("\\(\\s*\\+\\s*([^()]*?)\\s*\\)");
    private static final Pattern RATIO_CONTENT = Pattern.compile("^(.+?)\\s*%\\s*(.+)$", Pattern.DOTALL);
    /** Zahl am Anfang des Basisteils, z. B. "2 seconds" oder "30%". */
    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\+?(-?\\d+(?:\\.\\d+)?)(?![\\d.])");

    private WikiLevelingParser() {
    }

    /** Wert eines {@code {{ap|...}}} bzw. {@code {{pp|...}}} ({@code values == null}) mit seinem Klartext. */
    private record Marker(WikiExpr.RankValues values, String plain) {
    }

    /** Standard-Rangzahl: R = 3, sonst 5. */
    public static int defaultRanks(AbilitySlot slot) {
        return slot == AbilitySlot.R ? 3 : 5;
    }

    /** Standard-Rangzahl aus dem Parameter {@code skill}: "R" = 3, sonst 5. */
    public static int defaultRanks(String skill) {
        return skill != null && skill.trim().equalsIgnoreCase("R") ? 3 : 5;
    }

    /**
     * Wertet einen {@code leveling}-Parameter aus (kann mehrere {@code {{st|...}}} enthalten).
     *
     * @param ranks Rangzahl für {@code A to B} und Einzelwerte
     */
    public static List<WikiLeveling> parse(String levelingWikitext, int ranks) {
        if (levelingWikitext == null || levelingWikitext.isBlank()) {
            return List.of();
        }
        List<Marker> markers = new ArrayList<>();
        List<String[]> pairs = new ArrayList<>();
        WikiMarkup.TemplateHandler handler = (name, pos, named) -> switch (name) {
            case "ap" -> marker(markers, pos, named, ranks);
            case "pp", "pplevel" -> ppMarker(markers, pos, named);
            case "st" -> {
                for (int i = 0; i < pos.size(); i += 2) {
                    pairs.add(new String[]{pos.get(i), i + 1 < pos.size() ? pos.get(i + 1) : ""});
                }
                yield "";
            }
            default -> null;
        };
        WikiMarkup.expandTemplates(WikiMarkup.preprocess(levelingWikitext), handler);

        List<WikiLeveling> result = new ArrayList<>();
        for (String[] pair : pairs) {
            String label = plain(WikiMarkup.finish(pair[0]), markers);
            if (label.isEmpty() && pair[1].isBlank()) {
                continue;
            }
            result.add(buildLeveling(label, WikiMarkup.finish(pair[1]), markers, ranks));
        }
        return result;
    }

    /**
     * Werte je Rang aus einem einfachen Parameter wie {@code cooldown} oder {@code cost}: "7",
     * {@code {{ap|12 to 8}}}, {@code {{tt|{{ap|9 to 5}}|...}}} oder "a / b / c".
     *
     * @return leer, wenn kein Zahlenwert erkennbar ist
     */
    public static List<Double> parseRankValues(String wikitext, int ranks) {
        if (wikitext == null || wikitext.isBlank()) {
            return List.of();
        }
        List<Marker> markers = new ArrayList<>();
        WikiMarkup.TemplateHandler handler = (name, pos, named) -> switch (name) {
            case "ap" -> marker(markers, pos, named, ranks);
            case "pp", "pplevel" -> ppMarker(markers, pos, named);
            default -> null;
        };
        String v = WikiMarkup.finish(WikiMarkup.expandTemplates(WikiMarkup.preprocess(wikitext), handler));
        Matcher m = MARKER.matcher(v);
        if (m.find()) {
            return markers.get(Integer.parseInt(m.group(1))).values().withRanks(ranks).values();
        }
        if (PP_MARKER.matcher(v).find()) {
            return List.of(); // Werte je Champion-Level, nicht je Rang
        }
        return WikiExpr.rankValues(v, ranks).map(rv -> rv.withRanks(ranks).values()).orElse(List.of());
    }

    /**
     * Eigene Rangzahl, falls ein Text ein {@code {{ap|...}}} mit fester Länge enthält (explizite Werte,
     * {@code A to B N} oder {@code Formel for N}).
     */
    static OptionalInt detectRanks(String wikitext) {
        if (wikitext == null || !wikitext.contains("{{")) {
            return OptionalInt.empty();
        }
        int[] found = {-1};
        WikiMarkup.TemplateHandler handler = (name, pos, named) -> {
            if (name.equals("ap") && found[0] < 0) {
                WikiExpr.rankValues(pos, 5)
                        .filter(rv -> rv.kind() == WikiExpr.Kind.FIXED && rv.values().size() > 1)
                        .ifPresent(rv -> found[0] = rv.values().size());
            }
            return null;
        };
        WikiMarkup.expandTemplates(WikiMarkup.preprocess(wikitext), handler);
        return found[0] > 0 ? OptionalInt.of(found[0]) : OptionalInt.empty();
    }

    private static String marker(List<Marker> markers, List<String> pos, Map<String, String> named, int ranks) {
        String plain = WikiMarkup.formatAp(pos, named.get("key"));
        Optional<WikiExpr.RankValues> rv = WikiExpr.rankValues(pos, ranks);
        if (rv.isEmpty()) {
            return plain;
        }
        markers.add(new Marker(rv.get(), plain));
        return M_START + Integer.toString(markers.size() - 1) + M_END;
    }

    private static String ppMarker(List<Marker> markers, List<String> pos, Map<String, String> named) {
        markers.add(new Marker(null, WikiMarkup.formatPp(pos, named.get("key"))));
        return P_START + Integer.toString(markers.size() - 1) + P_END;
    }

    private static WikiLeveling buildLeveling(String label, String value, List<Marker> markers, int ranks) {
        // Basisteil: ohne Skalierungsgruppen "(+ ...)" (auch nicht auswertbare) und ohne Werte je Champion-Level
        String base = PP_MARKER.matcher(RATIO_GROUP.matcher(value).replaceAll(" ")).replaceAll(" ").trim();

        // Rangzahl dieses Werts: erste Angabe mit eigener Länge im Basisteil, sonst Standard
        int count = ranks;
        Matcher mm = MARKER.matcher(base);
        while (mm.find()) {
            WikiExpr.RankValues rv = markers.get(Integer.parseInt(mm.group(1))).values();
            if (rv.kind() == WikiExpr.Kind.FIXED) {
                count = rv.values().size();
                break;
            }
        }

        List<WikiRatio> ratios = new ArrayList<>();
        Matcher rm = RATIO_GROUP.matcher(value);
        while (rm.find()) {
            parseRatio(rm.group(1), markers, count).ifPresent(ratios::add);
        }

        // Basiswert: erstes {{ap}} im Basisteil, sonst eine Zahl am Anfang ("2 seconds"), sonst leer
        List<Double> values = List.of();
        Matcher first = MARKER.matcher(base);
        if (first.find()) {
            values = markers.get(Integer.parseInt(first.group(1))).values().withRanks(count).values();
        } else {
            Matcher num = LEADING_NUMBER.matcher(base);
            if (num.find()) {
                values = Collections.nCopies(count, WikiExpr.round6(Double.parseDouble(num.group(1))));
            }
        }
        return new WikiLeveling(label, values, ratios, plain(value, markers));
    }

    private static Optional<WikiRatio> parseRatio(String content, List<Marker> markers, int count) {
        Matcher m = RATIO_CONTENT.matcher(content.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        String numberPart = m.group(1).trim();
        String stat = normalizeStat(plain(m.group(2), markers));
        if (stat.isEmpty()) {
            return Optional.empty();
        }
        Matcher only = ONLY_MARKER.matcher(numberPart);
        if (only.matches()) {
            WikiExpr.RankValues rv = markers.get(Integer.parseInt(only.group(1))).values();
            return Optional.of(new WikiRatio(rv.withRanks(count).values(), stat));
        }
        if (MARKER.matcher(numberPart).find() || PP_MARKER.matcher(numberPart).find()) {
            return Optional.empty();
        }
        return WikiExpr.rankValues(numberPart, count)
                .map(rv -> new WikiRatio(rv.withRanks(count).values(), stat));
    }

    /**
     * Normalisiert eine Stat-Bezeichnung: Kleinschreibung, "of " am Anfang entfernt, "max" → "maximum",
     * "MR" → "magic resistance", "attack damage"/"ability power" → "AD"/"AP", "the target's" → "target's".
     */
    static String normalizeStat(String stat) {
        String s = stat.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        s = s.replaceAll("[.,;:]+$", "").trim();
        s = s.replaceAll("^of\\s+", "");
        s = s.replaceAll("\\bthe target's\\b", "target's");
        s = s.replaceAll("\\bmax\\b", "maximum");
        s = s.replaceAll("\\bmr\\b", "magic resistance");
        s = s.replaceAll("\\bmagic resist\\b", "magic resistance");
        s = s.replaceAll("\\bhp\\b", "health");
        s = s.replaceAll("\\battack damage\\b", "AD");
        s = s.replaceAll("\\bability power\\b", "AP");
        s = s.replaceAll("\\bad\\b", "AD");
        s = s.replaceAll("\\bap\\b", "AP");
        return s.trim();
    }

    /** Ersetzt Marker durch den Klartext des jeweiligen {@code {{ap|...}}} bzw. {@code {{pp|...}}}. */
    private static String plain(String text, List<Marker> markers) {
        String s = text;
        if (s.indexOf(M_START) >= 0) {
            s = MARKER.matcher(s).replaceAll(r ->
                    Matcher.quoteReplacement(markers.get(Integer.parseInt(r.group(1))).plain()));
        }
        if (s.indexOf(P_START) >= 0) {
            s = PP_MARKER.matcher(s).replaceAll(r ->
                    Matcher.quoteReplacement(markers.get(Integer.parseInt(r.group(1))).plain()));
        }
        return s.trim();
    }
}
