package com.leaguetool.data.lolalytics;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest die serverseitig gerenderte Tierliste ({@code /lol/tierlist/}) von Lolalytics.
 *
 * <p>Jede Champion-Zeile ist ein {@code <div class="flex h-[52px] …">}; nach dem Entfernen von Tags und
 * Tooltips ergibt sich: Rang, Name, Tier, Lane-Anteil %, Winrate %, Delta, Pickrate %, Banrate %, PBI,
 * Spiele, …. Ändert die Seite ihr Layout, schlägt das Parsen mit einer klaren Meldung fehl, statt falsche
 * Werte zu liefern.
 */
public final class LolalyticsParser {

    private static final String ROW_MARKER = "<div class=\"flex h-[52px]";
    private static final Pattern SLUG = Pattern.compile("/lol/([a-z0-9]+)/build/");
    private static final Pattern LANE = Pattern.compile("lane27/([a-z]+)\\.webp");
    private static final Pattern PATCH = Pattern.compile("Patch (\\d+\\.\\d+)");
    private static final Pattern AVG = Pattern.compile("Average [^%]*?Win Rate: ([0-9.]+)\\s*%");
    private static final Pattern COMMENTS = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern TEMPLATES = Pattern.compile("<q:template.*?</q:template>", Pattern.DOTALL);
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");

    private LolalyticsParser() {
    }

    public static LolalyticsTierList parse(String html) throws IOException {
        String patch = firstGroup(PATCH, html);
        String avg = firstGroup(AVG, TAGS.matcher(COMMENTS.matcher(html).replaceAll("")).replaceAll(""));
        double avgWinRate = avg == null ? Double.NaN : Double.parseDouble(avg) / 100.0;

        List<LolalyticsChampion> rows = new ArrayList<>();
        int from = html.indexOf(ROW_MARKER);
        while (from >= 0) {
            int next = html.indexOf(ROW_MARKER, from + ROW_MARKER.length());
            String chunk = html.substring(from, next < 0 ? html.length() : next);
            parseRow(chunk).ifPresent(rows::add);
            from = next;
        }
        if (rows.isEmpty()) {
            throw new IOException("Lolalytics: keine Champion-Zeilen gefunden (Seite geändert oder "
                    + "Zugriff blockiert)");
        }
        return new LolalyticsTierList(patch, avgWinRate, rows);
    }

    private static java.util.Optional<LolalyticsChampion> parseRow(String chunk) {
        String slug = firstGroup(SLUG, chunk);
        if (slug == null) {
            return java.util.Optional.empty();
        }
        String lane = firstGroup(LANE, chunk);
        String text = TAGS.matcher(TEMPLATES.matcher(COMMENTS.matcher(chunk).replaceAll("")).replaceAll(""))
                .replaceAll("|");
        List<String> t = new ArrayList<>();
        for (String s : text.split("\\|")) {
            String v = s.trim();
            if (!v.isEmpty()) {
                t.add(v);
            }
        }
        if (t.size() < 10) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(new LolalyticsChampion(slug, t.get(1), num(t.get(0)).intValue(), t.get(2),
                    lane, num(t.get(3)) / 100.0, num(t.get(4)) / 100.0, num(t.get(5)) / 100.0,
                    num(t.get(6)) / 100.0, num(t.get(7)) / 100.0, num(t.get(8)).intValue(),
                    num(t.get(9)).intValue()));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    private static Double num(String s) {
        return Double.valueOf(s.replace(",", "").replace("+", ""));
    }

    private static String firstGroup(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }
}
