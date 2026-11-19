package com.leaguetool.util;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Text-Hilfen: Data-Dragon-Markup in Klartext, Zahlenformatierung. */
public final class Text {

    private static final Pattern BREAK = Pattern.compile("(?i)<br\\s*/?>|</li>|</p>");
    private static final Pattern LIST_ITEM = Pattern.compile("(?i)<li>");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\x0B\\f\\r]+");
    private static final Pattern SPACE_AROUND_NEWLINE = Pattern.compile(" *\n *");
    private static final Pattern MANY_NEWLINES = Pattern.compile("\n{3,}");
    private static final Pattern SPACES_ALL = Pattern.compile("\\s+");

    /** Data Dragon gibt globale Reichweiten als 25000 (oder mehr) an. */
    private static final double GLOBAL_RANGE = 25000;

    private Text() {
    }

    /** Wandelt Data-Dragon-HTML ({@code <br>}, {@code <stats>}, {@code <passive>} ...) in Klartext um. */
    public static String htmlToPlain(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String s = BREAK.matcher(html).replaceAll("\n");
        s = LIST_ITEM.matcher(s).replaceAll("• ");
        s = TAG.matcher(s).replaceAll("");
        s = unescape(s);
        s = SPACES.matcher(s).replaceAll(" ");
        s = SPACE_AROUND_NEWLINE.matcher(s).replaceAll("\n");
        s = MANY_NEWLINES.matcher(s).replaceAll("\n\n");
        return s.strip();
    }

    static String unescape(String s) {
        return s.replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    /** Zahl ohne überflüssige Nachkommastellen: 7.0 → "7", 0.668 → "0.668". */
    public static String num(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "–";
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        String s = String.format(Locale.ROOT, "%.3f", value);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    /** Zahl mit fester Anzahl Nachkommastellen (Locale-unabhängig). */
    public static String num(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    /** Anteil (0..1) als Prozent, z. B. 0.5234 → "52.3 %". */
    public static String percent(double fraction) {
        if (Double.isNaN(fraction)) {
            return "–";
        }
        return String.format(Locale.ROOT, "%.1f %%", fraction * 100.0);
    }

    /** Reichweite, ab {@link #GLOBAL_RANGE} als "global". */
    public static String range(double value) {
        return value >= GLOBAL_RANGE ? "global" : num(value);
    }

    /** Reichweiten je Rang; "global", wenn alle Werte global sind, "–" bei leerer Liste. */
    public static String ranges(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return "–";
        }
        if (values.stream().allMatch(v -> v >= GLOBAL_RANGE)) {
            return "global";
        }
        return perRank(values);
    }

    /**
     * Normalisierter Bezeichner für Namens-Lookups: klein geschrieben, geschützte Leerzeichen und
     * Mehrfach-Leerraum zu einem Leerzeichen, abschließende {@code . , :} entfernt.
     */
    public static String normalizeName(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.toLowerCase(Locale.ROOT).replace('\u00A0', ' '); // geschütztes Leerzeichen
        s = SPACES_ALL.matcher(s).replaceAll(" ").strip();
        while (!s.isEmpty() && ".,:".indexOf(s.charAt(s.length() - 1)) >= 0) {
            s = s.substring(0, s.length() - 1).strip();
        }
        return s;
    }

    /** Fasst jeden Leerraum (auch Zeilenumbrüche) zu einem Leerzeichen zusammen. */
    public static String collapseSpaces(String s) {
        return SPACES_ALL.matcher(s).replaceAll(" ");
    }

    /** Werte je Rang: "7 / 6.5 / 6" bzw. ein einzelner Wert, wenn alle gleich sind. */
    public static String perRank(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return "–";
        }
        boolean allSame = values.stream().allMatch(v -> v.equals(values.get(0)));
        if (allSame) {
            return num(values.get(0));
        }
        return values.stream().map(Text::num).collect(java.util.stream.Collectors.joining(" / "));
    }
}
