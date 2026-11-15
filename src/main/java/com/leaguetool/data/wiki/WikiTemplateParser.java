package com.leaguetool.data.wiki;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parst Wiki-Seiten, die aus einem einzelnen Template-Aufruf bestehen, z. B.
 * {@code Template:Data_Ahri/Orb of Deception}:
 * <pre>
 * {{{{{1&lt;noinclude&gt;|Ability data&lt;/noinclude&gt;}}}|Orb of Deception|{{{2|}}}|...
 * |champion = Ahri
 * |skill    = Q
 * |leveling = {{st|...}}
 * }}
 * </pre>
 *
 * <p>Vor dem Zerlegen werden Kommentare, {@code <noinclude>}-Blöcke und Template-Parameter
 * ({@code {{{2|}}}}) entfernt und Variablen ({@code {{#vardefine:b1|10}}} / {@code {{#var:b1}}}) eingesetzt.
 * Ein {@code |} innerhalb verschachtelter {@code {{...}}} oder {@code [[...]]} trennt keine Parameter.
 */
public final class WikiTemplateParser {

    private static final Pattern VARDEFINE = Pattern.compile("\\{\\{\\s*#vardefine(echo)?\\s*:",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*#var\\s*:", Pattern.CASE_INSENSITIVE);

    private WikiTemplateParser() {
    }

    /**
     * Top-Level-Aufruf einer Seite.
     *
     * @param name       Template-Name (bei Ability-Seiten leer, da er aus einem Parameter stammt)
     * @param positional positionale Argumente (getrimmt), z. B. der Fähigkeitsname
     * @param params     benannte Parameter {@code |key = value} (Schlüssel getrimmt und klein geschrieben, Werte
     *                   getrimmt, Reihenfolge wie auf der Seite; bei doppelten Schlüsseln gewinnt der letzte)
     */
    public record TemplateCall(String name, List<String> positional, Map<String, String> params) {

        public TemplateCall {
            positional = List.copyOf(positional);
            params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
        }
    }

    /** Benannte Top-Level-Parameter der Seite. */
    public static Map<String, String> parse(String wikitext) {
        return parseCall(wikitext).params();
    }

    /**
     * Zerlegt den ersten Template-Aufruf der Seite.
     *
     * @throws IllegalArgumentException wenn die Seite keinen (geschlossenen) Template-Aufruf enthält oder eine
     *                                  Weiterleitung ist
     */
    public static TemplateCall parseCall(String wikitext) {
        if (wikitext == null || wikitext.isBlank()) {
            throw new IllegalArgumentException("Leere Wiki-Seite");
        }
        String trimmed = wikitext.stripLeading();
        if (trimmed.toUpperCase(Locale.ROOT).startsWith("#REDIRECT")) {
            throw new IllegalArgumentException("Seite ist eine Weiterleitung: " + firstLine(trimmed));
        }
        String s = resolveVariables(WikiMarkup.preprocess(wikitext));
        int open = s.indexOf("{{");
        if (open < 0) {
            throw new IllegalArgumentException("Kein Template-Aufruf auf der Seite gefunden");
        }
        int close = WikiMarkup.findClosing(s, open);
        if (close < 0) {
            throw new IllegalArgumentException("Template-Aufruf ab Position " + open + " wird nicht geschlossen");
        }
        List<String> parts = WikiMarkup.splitTopLevel(s.substring(open + 2, close));
        String name = parts.get(0).trim();
        WikiMarkup.TemplateArgs args = WikiMarkup.splitArgs(parts.subList(1, parts.size()), false);
        List<String> positional = new ArrayList<>();
        for (String arg : args.positional()) {
            positional.add(arg.trim());
        }
        Map<String, String> params = new LinkedHashMap<>();
        args.named().forEach((key, value) -> params.put(key, value.trim()));
        return new TemplateCall(name, positional, params);
    }

    /**
     * Löst Variablen in Textreihenfolge auf: {@code {{#vardefine:name|wert}}} setzt den Wert (und wird entfernt,
     * {@code #vardefineecho} durch den Wert ersetzt), {@code {{#var:name}}} bzw. {@code {{#var:name|default}}}
     * liefert den zu dieser Stelle gültigen Wert. Wird eine Variable später neu definiert, gilt der neue Wert
     * erst ab dort.
     */
    static String resolveVariables(String text) {
        return resolve(text, new HashMap<>());
    }

    private static String resolve(String text, Map<String, String> vars) {
        if (!text.contains("#var")) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        Matcher def = VARDEFINE.matcher(text);
        Matcher use = VAR.matcher(text);
        int i = 0;
        while (true) {
            boolean hasDef = def.find(i);
            boolean hasUse = use.find(i);
            if (!hasDef && !hasUse) {
                break;
            }
            boolean isDef = hasDef && (!hasUse || def.start() < use.start());
            Matcher m = isDef ? def : use;
            int close = WikiMarkup.findClosing(text, m.start());
            if (close < 0) {
                break;
            }
            out.append(text, i, m.start());
            List<String> parts = WikiMarkup.splitTopLevel(text.substring(m.end(), close));
            String name = parts.get(0).trim();
            if (isDef) {
                String value = parts.size() > 1 ? resolve(parts.get(1).trim(), vars) : "";
                vars.put(name, value);
                if (def.group(1) != null) {
                    out.append(value);
                }
            } else {
                String value = vars.get(name);
                out.append(value != null ? value : parts.size() > 1 ? resolve(parts.get(1), vars) : "");
            }
            i = close + 2;
        }
        out.append(text.substring(i));
        return out.toString();
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return (nl < 0 ? s : s.substring(0, nl)).trim();
    }
}
