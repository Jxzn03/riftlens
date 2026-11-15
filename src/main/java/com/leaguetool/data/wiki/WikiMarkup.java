package com.leaguetool.data.wiki;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wandelt Wiki-Markup (MediaWiki-Syntax des League-Wikis) in lesbaren Klartext um.
 *
 * <p>Templates werden von innen nach außen aufgelöst: Die Argumente eines Templates werden zuerst
 * expandiert, dann wird das Template selbst ersetzt. Unterstützt werden die im Wiki üblichen
 * Formatierungs-Templates ({@code as}, {@code sti}, {@code tip}, {@code tt}, {@code ap}, {@code st},
 * {@code dv}, {@code pp}, {@code ai} …) sowie die Parser-Funktionen {@code #expr}, {@code #if},
 * {@code #ifeq} und {@code #var}. Unbekannte Templates ohne Argumente werden entfernt, mit Argumenten
 * bleibt das letzte positionale Argument stehen.
 */
public final class WikiMarkup {

    private static final Pattern COMMENT = Pattern.compile("<!--.*?(-->|\\z)", Pattern.DOTALL);
    private static final Pattern NOINCLUDE = Pattern.compile("<noinclude>.*?(</noinclude>|\\z)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern INCLUDE_TAGS = Pattern.compile("</?(includeonly|onlyinclude)\\s*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REF = Pattern.compile("<ref[^>]*?/>|<ref[^>]*>.*?</ref>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TRIPLE_BRACE = Pattern.compile("\\{\\{\\{[^{}|]*(?:\\|([^{}]*))?}}}");
    private static final Pattern BR = Pattern.compile("<br\\s*/?\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAG = Pattern.compile("</?[a-zA-Z][^>]*>");
    private static final Pattern EXT_LINK_TEXT = Pattern.compile("\\[(?:https?:)?//[^\\s\\]]+\\s+([^\\]]+)]");
    private static final Pattern EXT_LINK_BARE = Pattern.compile("\\[(?:https?:)?//[^\\s\\]]+]");
    private static final Pattern APOSTROPHES = Pattern.compile("'{2,}");
    private static final Pattern HORIZONTAL_SPACE = Pattern.compile("[ \\t\\u00A0]+");
    private static final Pattern MANY_NEWLINES = Pattern.compile("\\n{3,}");
    private static final Pattern NAMED_KEY = Pattern.compile("[\\p{L}\\p{N} _\\-]+");
    private static final Pattern FILE_LINK = Pattern.compile("^:?\\s*(file|image|category|datei|media)\\s*:.*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Templates, die das erste positionale Argument anzeigen. */
    private static final Set<String> FIRST_ARG = Set.of(
            "as", "sbc", "ccs", "cai", "ci", "ii", "ri", "si", "ui", "bi", "fd", "tt", "nobr", "nowrap",
            "abbr", "small", "color", "rutip");
    /** Templates, die komplett entfernt werden (auch mit Argumenten). */
    private static final Set<String> REMOVE = Set.of(
            "bug", "effect at cast time end", "always quickcast", "sm2", "sic", "clarify", "cn",
            "citation needed", "ct", "-", "clear", "infobox damage");

    private WikiMarkup() {
    }

    /** Ersetzt Templates; liefert {@code null}, um die Standardbehandlung zu verwenden. */
    @FunctionalInterface
    interface TemplateHandler {
        String apply(String name, List<String> positional, Map<String, String> named);
    }

    /** Wiki-Markup → Klartext. {@code null} ergibt einen leeren String. */
    public static String toPlain(String wikitext) {
        if (wikitext == null || wikitext.isEmpty()) {
            return "";
        }
        return finish(expandTemplates(preprocess(wikitext), null));
    }

    /** Wie {@link #toPlain(String)}, einzelne Templates behandelt {@code override} selbst. */
    static String toPlain(String wikitext, TemplateHandler override) {
        return wikitext == null || wikitext.isEmpty() ? "" : finish(expandTemplates(preprocess(wikitext), override));
    }

    // ---------------------------------------------------------------- Vorverarbeitung

    /** Entfernt Kommentare, {@code <noinclude>}-Blöcke, Referenzen und Template-Parameter {@code {{{1|x}}}}. */
    static String preprocess(String text) {
        String s = COMMENT.matcher(text).replaceAll("");
        s = NOINCLUDE.matcher(s).replaceAll("");
        s = INCLUDE_TAGS.matcher(s).replaceAll("");
        s = REF.matcher(s).replaceAll("");
        String before;
        do {
            before = s;
            Matcher m = TRIPLE_BRACE.matcher(s);
            s = m.replaceAll(r -> Matcher.quoteReplacement(r.group(1) == null ? "" : r.group(1)));
        } while (!s.equals(before));
        return s;
    }

    // ---------------------------------------------------------------- Template-Expansion

    /**
     * Expandiert alle Templates in {@code text}. {@code override} kann einzelne Templates selbst behandeln
     * (Rückgabe {@code null} = Standardbehandlung). Unausgeglichene <code>{{</code> bleiben als Text stehen.
     */
    static String expandTemplates(String text, TemplateHandler override) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        int n = text.length();
        while (i < n) {
            int open = text.indexOf("{{", i);
            if (open < 0) {
                out.append(text, i, n);
                break;
            }
            out.append(text, i, open);
            int close = findClosing(text, open);
            if (close < 0) {
                out.append("{{");
                i = open + 2;
                continue;
            }
            out.append(applyTemplate(text.substring(open + 2, close), override));
            i = close + 2;
        }
        return out.toString();
    }

    /**
     * Position der schließenden <code>}}</code> zu dem <code>{{</code> an {@code open} oder -1, wenn es keine gibt.
     */
    static int findClosing(String text, int open) {
        int depth = 0;
        int j = open;
        int n = text.length();
        while (j < n - 1) {
            if (text.charAt(j) == '{' && text.charAt(j + 1) == '{') {
                depth++;
                j += 2;
            } else if (text.charAt(j) == '}' && text.charAt(j + 1) == '}') {
                depth--;
                if (depth == 0) {
                    return j;
                }
                j += 2;
            } else {
                j++;
            }
        }
        return -1;
    }

    /**
     * Teilt an {@code |}, die nicht in verschachtelten {@code {{...}}} oder {@code [[...]]} stehen.
     * Nicht geschlossene <code>[[</code> werden ignoriert.
     */
    static List<String> splitTopLevel(String inner) {
        List<String> parts = splitTopLevel(inner, true);
        return parts != null ? parts : splitTopLevel(inner, false);
    }

    private static List<String> splitTopLevel(String inner, boolean respectBrackets) {
        List<String> parts = new ArrayList<>();
        int braces = 0;
        int brackets = 0;
        int start = 0;
        int n = inner.length();
        int j = 0;
        while (j < n) {
            char c = inner.charAt(j);
            char next = j + 1 < n ? inner.charAt(j + 1) : '\0';
            if (c == '{' && next == '{') {
                braces++;
                j += 2;
            } else if (c == '}' && next == '}' && braces > 0) {
                braces--;
                j += 2;
            } else if (respectBrackets && c == '[' && next == '[') {
                brackets++;
                j += 2;
            } else if (respectBrackets && c == ']' && next == ']' && brackets > 0) {
                brackets--;
                j += 2;
            } else if (c == '|' && braces == 0 && brackets == 0) {
                parts.add(inner.substring(start, j));
                start = ++j;
            } else {
                j++;
            }
        }
        if (respectBrackets && brackets != 0) {
            return null;
        }
        parts.add(inner.substring(start));
        return parts;
    }

    /** Index des ersten {@code =} außerhalb verschachtelter Klammern oder -1. */
    static int topLevelEquals(String arg) {
        int depth = 0;
        for (int j = 0; j < arg.length(); j++) {
            char c = arg.charAt(j);
            if ((c == '{' || c == '[') && j + 1 < arg.length() && arg.charAt(j + 1) == c) {
                depth++;
                j++;
            } else if ((c == '}' || c == ']') && j + 1 < arg.length() && arg.charAt(j + 1) == c && depth > 0) {
                depth--;
                j++;
            } else if (c == '=' && depth == 0) {
                return j;
            }
        }
        return -1;
    }

    private static String applyTemplate(String inner, TemplateHandler override) {
        List<String> raw = splitTopLevel(inner);
        String head = raw.get(0);
        List<String> rawArgs = new ArrayList<>(raw.subList(1, raw.size()));
        String trimmedHead = head.trim();
        boolean parserFunction = trimmedHead.startsWith("#") && trimmedHead.contains(":");
        String name;
        if (parserFunction) {
            int colon = trimmedHead.indexOf(':');
            name = trimmedHead.substring(0, colon);
            rawArgs.add(0, trimmedHead.substring(colon + 1));
        } else {
            name = trimmedHead;
        }
        name = normalizeName(name);

        TemplateArgs args = splitArgs(rawArgs, parserFunction);
        List<String> positional = new ArrayList<>();
        for (String arg : args.positional()) {
            positional.add(expandTemplates(arg, override).trim());
        }
        Map<String, String> named = new LinkedHashMap<>();
        args.named().forEach((key, value) -> named.put(key, expandTemplates(value, override).trim()));
        String result = override == null ? null : override.apply(name, positional, named);
        return result != null ? result : defaultTemplate(name, positional, named);
    }

    /**
     * Argumente eines Template-Aufrufs, roh (nicht expandiert, nicht getrimmt).
     *
     * @param positional positionale Argumente in Reihenfolge
     * @param named      benannte Argumente; Schlüssel getrimmt und klein geschrieben, bei doppelten Schlüsseln
     *                   gewinnt der letzte
     */
    record TemplateArgs(List<String> positional, Map<String, String> named) {
    }

    /**
     * Ordnet die (bereits an {@code |} getrennten) Argumente in positionale und benannte. Ein Argument ist
     * benannt, wenn vor dem ersten {@code =} außerhalb verschachtelter Klammern ein gültiger Schlüssel steht.
     *
     * @param allPositional {@code true} für Parser-Funktionen ({@code #if}, {@code #expr} …), deren Argumente nie
     *                      benannt sind
     */
    static TemplateArgs splitArgs(List<String> rawArgs, boolean allPositional) {
        List<String> positional = new ArrayList<>();
        Map<String, String> named = new LinkedHashMap<>();
        for (String arg : rawArgs) {
            int eq = allPositional ? -1 : topLevelEquals(arg);
            String key = eq > 0 ? arg.substring(0, eq).trim() : "";
            if (eq > 0 && NAMED_KEY.matcher(key).matches()) {
                named.put(key.toLowerCase(Locale.ROOT), arg.substring(eq + 1));
            } else {
                positional.add(arg);
            }
        }
        return new TemplateArgs(positional, named);
    }

    private static String normalizeName(String name) {
        return HORIZONTAL_SPACE.matcher(name.replace('_', ' ')).replaceAll(" ").trim().toLowerCase(Locale.ROOT);
    }

    /** Standardbehandlung eines Templates (Argumente sind bereits expandiert). */
    static String defaultTemplate(String name, List<String> pos, Map<String, String> named) {
        if (REMOVE.contains(name)) {
            return "";
        }
        if (FIRST_ARG.contains(name)) {
            return pos.isEmpty() ? "" : pos.get(0);
        }
        switch (name) {
            case "tip", "sti" -> {
                if ("true".equalsIgnoreCase(named.getOrDefault("icononly", ""))) {
                    return "";
                }
                return pos.isEmpty() ? "" : pos.get(pos.size() - 1);
            }
            case "ai" -> {
                return pos.isEmpty() ? "" : pos.size() >= 3 && !pos.get(2).isEmpty() ? pos.get(2) : pos.get(0);
            }
            case "ais", "cis" -> {
                return pos.isEmpty() ? "" : pos.get(0) + "'s";
            }
            case "ft" -> {
                // Kurztext eines aufklappbaren Textes; das Template setzt eigene Leerzeichen
                return pos.isEmpty() ? "" : " " + pos.get(0) + " ";
            }
            case "ap" -> {
                return formatAp(pos, named.get("key"));
            }
            case "pp", "pplevel" -> {
                return formatPp(pos, named.get("key"));
            }
            case "st" -> {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < pos.size(); i += 2) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(pos.get(i));
                    if (i + 1 < pos.size()) {
                        sb.append(": ").append(pos.get(i + 1));
                    }
                }
                return sb.toString();
            }
            case "dv" -> {
                return String.join(" / ", pos);
            }
            case "times" -> {
                return "×";
            }
            case "degree" -> {
                return "°";
            }
            case "!" -> {
                return "|";
            }
            case "=" -> {
                return "=";
            }
            case "nbsp" -> {
                return " ";
            }
            case "#expr" -> {
                if (pos.isEmpty()) {
                    return "";
                }
                OptionalDouble v = WikiExpr.tryEval(pos.get(0));
                return v.isPresent() ? WikiExpr.format(v.getAsDouble()) : pos.get(0);
            }
            case "#if" -> {
                boolean condition = !pos.isEmpty() && !pos.get(0).isBlank();
                int idx = condition ? 1 : 2;
                return idx < pos.size() ? pos.get(idx) : "";
            }
            case "#ifeq" -> {
                boolean equal = pos.size() >= 2 && pos.get(0).trim().equals(pos.get(1).trim());
                int idx = equal ? 2 : 3;
                return idx < pos.size() ? pos.get(idx) : "";
            }
            case "#var" -> {
                return pos.size() > 1 ? pos.get(1) : "";
            }
            default -> {
                if (name.startsWith("#")) {
                    return "";
                }
                return pos.isEmpty() ? "" : pos.get(pos.size() - 1);
            }
        }
    }

    /** Klartext eines {@code {{ap|...}}}: "35 – 135", "a / b / c" oder ein ausgewerteter Ausdruck. */
    static String formatAp(List<String> pos, String key) {
        List<String> parts = pos.stream().filter(s -> !s.isBlank()).toList();
        if (parts.isEmpty()) {
            return "";
        }
        String suffix = key == null ? "" : key;
        if (parts.size() > 1) {
            List<String> shown = new ArrayList<>();
            for (String p : parts) {
                shown.add(numberOrRaw(p) + (WikiExpr.tryEval(p).isPresent() ? suffix : ""));
            }
            return String.join(" / ", shown);
        }
        String spec = parts.get(0).trim();
        Matcher forMatcher = WikiExpr.FOR_SPEC.matcher(spec);
        if (forMatcher.matches()) {
            Optional<WikiExpr.RankValues> rv = WikiExpr.rankValues(spec, 5);
            if (rv.isPresent()) {
                return String.join(" / ", rv.get().values().stream().map(v -> WikiExpr.format(v) + suffix).toList());
            }
            return spec;
        }
        String[] to = WikiExpr.TO_SPLIT.split(spec, -1);
        if (to.length == 2) {
            Optional<WikiExpr.RankValues> rv = WikiExpr.rankValues(spec, 5);
            if (rv.isPresent()) {
                return WikiExpr.format(rv.get().first()) + suffix + " – " + WikiExpr.format(rv.get().last()) + suffix;
            }
            return to[0].trim() + " – " + to[1].trim();
        }
        OptionalDouble v = WikiExpr.tryEval(spec);
        return v.isPresent() ? WikiExpr.format(v.getAsDouble()) + suffix : spec;
    }

    /** Klartext eines {@code {{pp|...}}} (Wert je Champion-Level): "35 – 95 (based on level)". */
    static String formatPp(List<String> pos, String key) {
        if (pos.isEmpty() || pos.get(0).isBlank()) {
            return "";
        }
        String spec = pos.get(0).trim();
        String suffix = key == null ? "" : key;
        if (spec.contains(";")) {
            // explizite Werteliste je Level: "1.2;1.155;...;0.6"
            String[] values = spec.split("\\s*;\\s*");
            return numberOrRaw(values[0]) + suffix + " – " + numberOrRaw(values[values.length - 1]) + suffix
                    + " (based on level)";
        }
        Matcher forMatcher = WikiExpr.FOR_SPEC.matcher(spec);
        if (forMatcher.matches() && WikiExpr.TO_SPLIT.split(forMatcher.group(1), -1).length == 2) {
            // "0 to 100 for 11"
            String[] to = WikiExpr.TO_SPLIT.split(forMatcher.group(1), -1);
            return numberOrRaw(to[0]) + suffix + " – " + numberOrRaw(to[1]) + suffix + " (based on level)";
        }
        if (forMatcher.matches()) {
            int n = Integer.parseInt(forMatcher.group(2));
            OptionalDouble a = WikiExpr.tryEval(forMatcher.group(1), 1);
            OptionalDouble b = WikiExpr.tryEval(forMatcher.group(1), Math.min(n, 18));
            if (a.isPresent() && b.isPresent()) {
                return WikiExpr.format(a.getAsDouble()) + suffix + " – " + WikiExpr.format(b.getAsDouble()) + suffix
                        + " (based on level)";
            }
            return spec;
        }
        String[] to = WikiExpr.TO_SPLIT.split(spec, -1);
        if (to.length == 2) {
            return numberOrRaw(to[0]) + suffix + " – " + numberOrRaw(to[1]) + suffix + " (based on level)";
        }
        return numberOrRaw(spec) + suffix;
    }

    private static String numberOrRaw(String s) {
        OptionalDouble v = WikiExpr.tryEval(s);
        return v.isPresent() ? WikiExpr.format(v.getAsDouble()) : s.trim();
    }

    // ---------------------------------------------------------------- Nachbearbeitung

    /** Links, HTML, Fett/Kursiv und Leerraum (nach der Template-Expansion). */
    static String finish(String text) {
        String s = processLinks(text);
        s = EXT_LINK_TEXT.matcher(s).replaceAll("$1");
        s = EXT_LINK_BARE.matcher(s).replaceAll("");
        s = BR.matcher(s).replaceAll("\n");
        s = HTML_TAG.matcher(s).replaceAll("");
        s = s.replace("&nbsp;", " ").replace("&ndash;", "–").replace("&mdash;", "—").replace("&times;", "×")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&amp;", "&");
        s = APOSTROPHES.matcher(s).replaceAll(m -> {
            int len = m.group().length();
            return switch (len) {
                case 2, 3, 5 -> "";
                case 4 -> "'";
                default -> "'".repeat(len - 5);
            };
        });
        s = HORIZONTAL_SPACE.matcher(s).replaceAll(" ");
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n", -1)) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line.trim());
        }
        s = MANY_NEWLINES.matcher(sb.toString()).replaceAll("\n\n");
        return s.strip();
    }

    /** {@code [[Seite|Text]]} → Text, {@code [[Seite]]} → Seite, Datei-/Kategorie-Links werden entfernt. */
    static String processLinks(String text) {
        if (!text.contains("[[")) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        int n = text.length();
        while (i < n) {
            int open = text.indexOf("[[", i);
            if (open < 0) {
                out.append(text, i, n);
                break;
            }
            out.append(text, i, open);
            int close = findLinkClosing(text, open);
            if (close < 0) {
                out.append("[[");
                i = open + 2;
                continue;
            }
            String inner = text.substring(open + 2, close);
            if (!FILE_LINK.matcher(inner).matches()) {
                List<String> parts = splitTopLevel(inner);
                String shown = parts.size() > 1 ? parts.get(parts.size() - 1) : parts.get(0);
                if (parts.size() == 1 && shown.startsWith(":")) {
                    shown = shown.substring(1);
                }
                out.append(processLinks(shown));
            }
            i = close + 2;
        }
        return out.toString();
    }

    private static int findLinkClosing(String text, int open) {
        int depth = 0;
        int j = open;
        while (j < text.length() - 1) {
            if (text.charAt(j) == '[' && text.charAt(j + 1) == '[') {
                depth++;
                j += 2;
            } else if (text.charAt(j) == ']' && text.charAt(j + 1) == ']') {
                depth--;
                if (depth == 0) {
                    return j;
                }
                j += 2;
            } else {
                j++;
            }
        }
        return -1;
    }
}
