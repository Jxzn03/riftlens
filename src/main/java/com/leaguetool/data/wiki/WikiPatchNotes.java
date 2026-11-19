package com.leaguetool.data.wiki;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Patch-Notes aus dem League-Wiki, nur Summoner's Rift: die Patch-Seite ({@code V26.23}) und die Patch-Historie eines
 * Champions ({@code Ahri/Patch history}). Wiki-Markup wird mit {@link WikiMarkup} zu Klartext.
 *
 * <p>Patch-Seite: Abschnitte unter „League of Legends V…“ und „Hotfixes“; Client, Kosmetik und Spielmodi (ARAM,
 * Arena, URF …) fallen weg. Champion-Historie: Einträge {@code ;[[V26.06]]} mit ihren Aufzählungen; Skin-Änderungen
 * und ausdrücklich modusbezogene Punkte fallen weg.
 */
public final class WikiPatchNotes {

    /**
     * Teil einer Zeile mit Darstellung wie im Wiki.
     *
     * @param style {@code null} (Text), {@code label} (z. B. „New Effect:“), {@code ability}, {@code champion},
     *              {@code item}, {@code rune}, {@code spell} oder eine Wertfarbe ({@code ap}, {@code ad},
     *              {@code health}, {@code mana}, {@code armor}, {@code mr}, {@code as}, {@code ms}, {@code crit},
     *              {@code haste}, {@code scale})
     * @param ref   Bezug für Icon und Link: bei {@code ability} „Champion|Fähigkeit“, sonst der Name; sonst {@code null}
     */
    public record Part(String text, String style, String ref) {
        public Part {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * Eine Zeile einer Aufzählung; {@code depth} 0 = Fließtext, 1 = {@code *}, 2 = {@code **} …
     *
     * @param text  Klartext (Suche, Tests)
     * @param parts dieselbe Zeile in Teilen mit Darstellung
     * @param trend {@code buff}, {@code nerf} oder {@code null}, aus dem Text geschätzt ({@link #trendOf})
     */
    public record Line(int depth, String text, List<Part> parts, String trend) {
        public Line {
            Objects.requireNonNull(text, "text");
            parts = List.copyOf(parts);
        }

        /** Zeile ohne Darstellung (ein Textteil), Trend aus dem Text. */
        public Line(int depth, String text) {
            this(depth, text, List.of(new Part(text, null, null)), trendOf(text));
        }
    }

    /**
     * Gruppe unter einer {@code ;}-Überschrift, z. B. ein Champion.
     *
     * @param heading Klartext der Überschrift oder {@code null} (Zeilen vor der ersten Überschrift)
     * @param kind    {@code champion}, {@code item}, {@code rune}, {@code spell} oder {@code null}
     * @param ref     Name aus der Vorlage (z. B. „Mel“ bei „Mel - Gameplay Update“) für Icon und Link, sonst {@code null}
     */
    public record Group(String heading, String kind, String ref, List<Line> lines) {
        public Group {
            lines = List.copyOf(lines);
        }
    }

    /**
     * Abschnitt der Patch-Seite, z. B. „Champions“ oder ein Hotfix.
     *
     * @param kind {@link #HOTFIX}, {@link #PATCH} oder {@link #BUGFIX}
     */
    public record Section(String title, String kind, List<Group> groups) {
        public Section {
            groups = List.copyOf(groups);
        }
    }

    public static final String HOTFIX = "hotfix";
    public static final String PATCH = "patch";
    public static final String BUGFIX = "bugfix";
    static final String BUGFIX_TITLE = "Bugfixes";

    /**
     * Ein Patch in der Historie eines Champions, z. B. {@code V26.06}.
     *
     * @param hotfix Hotfix innerhalb des Patches (z. B. „December 17th Hotfix“) oder {@code null}
     */
    public record Entry(String patch, String hotfix, List<Line> lines) {
        public Entry {
            lines = List.copyOf(lines);
        }
    }

    private static final Pattern HEADING = Pattern.compile("^(={2,6})\\s*(.*?)\\s*\\1\\s*$");
    private static final Pattern GROUP_KIND = Pattern.compile("^\\{\\{\\s*(ci|ii|ri|si)\\s*\\|\\s*([^|}]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HISTORY_PATCH = Pattern.compile("^;\\s*\\[\\[\\s*(V[^\\]|]+?)\\s*(?:\\|[^\\]]*)?]]");
    private static final Pattern DDRAGON_VERSION = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\..*)?$");
    /** Abschnitte und Punkte, die nicht Summoner's Rift betreffen. */
    private static final Pattern NOT_RIFT = Pattern.compile(
            "\\b(client|aram|arena|urf|swarm|nexus blitz|one for all|spellbook|tft|teamfight tactics|brawl|doom bots?"
                    + "|cosmetics?|store|clash|mayhem|skins?|chromas?|tocker|mode-specific)\\b", Pattern.CASE_INSENSITIVE);

    private WikiPatchNotes() {
    }

    /**
     * Wiki-Titel des Spiel-Patches zu einer Data-Dragon-Version: {@code 16.23.1} → {@code V26.23}. Data Dragon zählt
     * die Hauptversion seit 2025 zehn niedriger als der Spiel-Patch (15.x = 25.x); das Wiki schreibt die Patchnummer
     * zweistellig.
     */
    public static String wikiPatchTitle(String ddragonVersion) {
        Matcher m = DDRAGON_VERSION.matcher(ddragonVersion == null ? "" : ddragonVersion.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Unbekannte Patch-Version: " + ddragonVersion);
        }
        return String.format(Locale.ROOT, "V%d.%02d", Integer.parseInt(m.group(1)) + 10, Integer.parseInt(m.group(2)));
    }

    /**
     * Wiki-Titel des aktuellen Patches und der {@code previous} Patches davor, neuester zuerst, aus den Data-Dragon-
     * Versionen (mehrere Builds eines Patches zählen einmal). Versionen vor {@code current} werden übersprungen.
     */
    public static List<String> recentPatchTitles(List<String> ddragonVersions, String current, int previous) {
        List<String> titles = new ArrayList<>();
        String currentTitle = wikiPatchTitle(current);
        titles.add(currentTitle);
        boolean reached = false;
        for (String version : ddragonVersions) {
            String title;
            try {
                title = wikiPatchTitle(version);
            } catch (IllegalArgumentException e) {
                continue;
            }
            reached |= title.equals(currentTitle);
            if (reached && !titles.contains(title)) {
                titles.add(title);
                if (titles.size() > previous) {
                    break;
                }
            }
        }
        return titles;
    }

    /**
     * Summoner's-Rift-Abschnitte einer Patch-Seite: zuerst die Hotfixes, dann die Abschnitte des Patches, am Ende
     * „Bugfixes“. Die „Bug Fix:“-Punkte der Patch-Abschnitte wandern samt Unterpunkten und übergeordneten Zeilen
     * (z. B. der Fähigkeit) dorthin; in Hotfixes bleiben sie stehen, weil das Datum dazugehört.
     */
    public static List<Section> parsePatchPage(String wikitext) {
        List<Section> hotfixes = new ArrayList<>();
        List<Section> patch = new ArrayList<>();
        String level2 = "";
        String title = null;
        List<String> body = new ArrayList<>();
        for (String line : logicalLines(wikitext)) {
            Matcher h = HEADING.matcher(line);
            if (h.matches()) {
                int level = h.group(1).length();
                if (level <= 3) {
                    addSection(isHotfix(level2) ? hotfixes : patch, title, isHotfix(level2) ? HOTFIX : PATCH, body);
                    title = null;
                    body = new ArrayList<>();
                }
                String heading = WikiMarkup.toPlain(h.group(2));
                if (level == 2) {
                    level2 = heading;
                } else if (level == 3 && relevantLevel2(level2) && !NOT_RIFT.matcher(heading).find()) {
                    title = heading;
                }
                continue;
            }
            if (title != null) {
                body.add(line);
            }
        }
        addSection(isHotfix(level2) ? hotfixes : patch, title, isHotfix(level2) ? HOTFIX : PATCH, body);

        List<Group> bugfixes = new ArrayList<>();
        List<Section> result = new ArrayList<>(hotfixes);
        for (Section s : patch) {
            List<Group> kept = new ArrayList<>();
            for (Group g : s.groups()) {
                Split split = splitBugfixes(g.lines());
                if (!split.rest().isEmpty()) {
                    kept.add(new Group(g.heading(), g.kind(), g.ref(), split.rest()));
                }
                if (!split.bugfixes().isEmpty()) {
                    mergeGroup(bugfixes, new Group(g.heading(), g.kind(), g.ref(), split.bugfixes()));
                }
            }
            if (!kept.isEmpty()) {
                result.add(new Section(s.title(), PATCH, kept));
            }
        }
        if (!bugfixes.isEmpty()) {
            result.add(new Section(BUGFIX_TITLE, BUGFIX, bugfixes));
        }
        return result;
    }

    /** Zeilen einer Gruppe, getrennt in Bugfixes (mit Kontext) und den Rest. */
    private record Split(List<Line> bugfixes, List<Line> rest) {
    }

    /**
     * Trennt die „Bug Fix:“-Punkte samt Unterpunkten ab. Übergeordnete Zeilen kommen als Kontext mit in die Bugfixes
     * und bleiben im Rest nur, wenn sie dort noch eigene Unterpunkte haben.
     */
    static Split splitBugfixes(List<Line> lines) {
        boolean[] bug = new boolean[lines.size()];
        boolean[] context = new boolean[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            if (bug[i] || !isBugfix(lines.get(i))) {
                continue;
            }
            int depth = lines.get(i).depth();
            bug[i] = true;
            for (int j = i + 1; j < lines.size() && lines.get(j).depth() > depth; j++) {
                bug[j] = true;
            }
            int need = depth - 1; // nächste übergeordnete Zeile je Ebene darüber
            for (int k = i - 1; k >= 0 && need >= 1; k--) {
                if (lines.get(k).depth() == need) {
                    context[k] = true;
                    need--;
                }
            }
        }
        List<Line> bugfixes = new ArrayList<>();
        List<Line> rest = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (bug[i] || context[i]) {
                bugfixes.add(lines.get(i));
            }
            if (!bug[i] && (!context[i] || hasRemainingChild(lines, bug, i))) {
                rest.add(lines.get(i));
            }
        }
        return new Split(bugfixes, rest);
    }

    private static boolean isBugfix(Line line) {
        return line.text().regionMatches(true, 0, "Bug Fix", 0, 7);
    }

    private static boolean hasRemainingChild(List<Line> lines, boolean[] bug, int index) {
        int depth = lines.get(index).depth();
        for (int j = index + 1; j < lines.size() && lines.get(j).depth() > depth; j++) {
            if (!bug[j]) {
                return true;
            }
        }
        return false;
    }

    /** Bugfixes desselben Champions oder Items aus mehreren Abschnitten zusammenführen. */
    private static void mergeGroup(List<Group> groups, Group add) {
        for (int i = 0; i < groups.size(); i++) {
            Group g = groups.get(i);
            if (Objects.equals(g.heading(), add.heading()) && Objects.equals(g.kind(), add.kind())) {
                List<Line> lines = new ArrayList<>(g.lines());
                lines.addAll(add.lines());
                groups.set(i, new Group(g.heading(), g.kind(), g.ref(), lines));
                return;
            }
        }
        groups.add(add);
    }

    private static boolean isHotfix(String level2) {
        return level2.toLowerCase(Locale.ROOT).startsWith("hotfix");
    }

    /** Einträge der Patch-Historie eines Champions, neuester zuerst (Reihenfolge der Seite). */
    public static List<Entry> parseChampionHistory(String wikitext) {
        List<Entry> entries = new ArrayList<>();
        String patch = null;
        List<Line> lines = new ArrayList<>();
        boolean skipping = false;
        for (String line : logicalLines(wikitext)) {
            Matcher h = HEADING.matcher(line);
            if (h.matches()) {
                if (WikiMarkup.toPlain(h.group(2)).toLowerCase(Locale.ROOT).startsWith("see also")) {
                    break;
                }
                continue;
            }
            Matcher p = HISTORY_PATCH.matcher(line);
            if (p.find()) {
                addEntry(entries, patch, lines);
                patch = p.group(1).trim();
                lines = new ArrayList<>();
                skipping = false;
                continue;
            }
            if (patch == null) {
                continue;
            }
            int depth = bulletDepth(line);
            String raw = line.substring(depth).trim();
            if (depth == 1) {
                // Skin-Änderungen und ausdrücklich modusbezogene Punkte samt Unterpunkten überspringen.
                skipping = raw.regionMatches(true, 0, "{{csl", 0, 5) || NOT_RIFT.matcher(leadingWord(raw)).find();
            }
            if (skipping || depth == 0) {
                continue;
            }
            Line parsed = line(depth, raw);
            if (!parsed.text().isEmpty()) {
                lines.add(parsed);
            }
        }
        addEntry(entries, patch, lines);
        return entries;
    }

    // ---------------------------------------------------------------- intern

    private static boolean relevantLevel2(String heading) {
        String h = heading.toLowerCase(Locale.ROOT);
        return h.startsWith("league of legends") || h.startsWith("hotfix");
    }

    private static void addSection(List<Section> sections, String title, String sectionKind, List<String> body) {
        if (title == null) {
            return;
        }
        List<Group> groups = new ArrayList<>();
        String heading = null;
        String kind = null;
        String ref = null;
        List<Line> lines = new ArrayList<>();
        boolean skipGroup = false;
        for (String line : body) {
            if (line.startsWith(";")) {
                addGroup(groups, heading, kind, ref, lines, skipGroup);
                String raw = line.substring(1).trim();
                Matcher k = GROUP_KIND.matcher(raw);
                boolean found = k.find();
                kind = found ? kindOf(k.group(1)) : null;
                ref = found ? k.group(2).trim() : null; // z. B. „Mel“ bei „{{ci|Mel}} - Gameplay Update“
                heading = plain(raw);
                skipGroup = kind == null && NOT_RIFT.matcher(heading).find(); // z. B. „ARAM“ in einem Hotfix
                lines = new ArrayList<>();
                continue;
            }
            int depth = bulletDepth(line);
            Line parsed = line(depth, line.substring(depth));
            if (!parsed.text().isEmpty()) {
                lines.add(parsed);
            }
        }
        addGroup(groups, heading, kind, ref, lines, skipGroup);
        if (!groups.isEmpty()) {
            sections.add(new Section(title, sectionKind, groups));
        }
    }

    private static void addGroup(List<Group> groups, String heading, String kind, String ref, List<Line> lines,
                                 boolean skip) {
        if (!skip && !lines.isEmpty()) {
            groups.add(new Group(heading == null || heading.isEmpty() ? null : heading, kind, ref, lines));
        }
    }

    /** @param target Linkziel wie {@code V26.06} oder mit Hotfix-Anker {@code V25.24#December 17th Hotfix} */
    private static void addEntry(List<Entry> entries, String target, List<Line> lines) {
        if (target == null || lines.isEmpty()) {
            return;
        }
        int hash = target.indexOf('#');
        String patch = hash < 0 ? target : target.substring(0, hash).trim();
        String hotfix = hash < 0 ? null : target.substring(hash + 1).replace('_', ' ').trim();
        entries.add(new Entry(patch, hotfix == null || hotfix.isEmpty() ? null : hotfix, lines));
    }

    private static String kindOf(String template) {
        return switch (template.toLowerCase(Locale.ROOT)) {
            case "ci" -> "champion";
            case "ii" -> "item";
            case "ri" -> "rune";
            default -> "spell";
        };
    }

    private static int bulletDepth(String line) {
        int depth = 0;
        while (depth < line.length() && line.charAt(depth) == '*') {
            depth++;
        }
        return depth;
    }

    /** Erstes Wort des Klartexts (für die Prüfung auf einen Spielmodus am Anfang eines Punkts). */
    private static String leadingWord(String raw) {
        String text = plain(raw);
        int end = 0;
        while (end < text.length() && (Character.isLetter(text.charAt(end)) || text.charAt(end) == ':')) {
            end++;
        }
        return text.substring(0, end);
    }

    // Marken um formatierte Stellen: START Stil SEP Bezug SEP Text END. Zeichen aus dem privaten Unicode-Bereich: kein
    // Leerraum, kein Markup, von trim() unberührt (Steuerzeichen würde trim() am Zeilenrand entfernen).
    private static final char START = '';
    private static final char SEP = '';
    private static final char END = '';

    /** Zeile mit Teilen und Trend aus Wiki-Markup. */
    static Line line(int depth, String raw) {
        List<Part> parts = parts(rich(raw));
        String plainText = textOf(parts);
        return new Line(depth, plainText, parts, trendOf(plainText));
    }

    /** Klartext einer Zeile; {@code {{rd|Nahkampf|Fernkampf}}} wird zu „a melee / b ranged“. */
    static String plain(String raw) {
        return textOf(parts(rich(raw)));
    }

    private static String textOf(List<Part> parts) {
        StringBuilder text = new StringBuilder();
        parts.forEach(p -> text.append(p.text()));
        return text.toString();
    }

    /** Wiki-Markup zu Text mit Marken, Leerraum zusammengefasst. */
    private static String rich(String raw) {
        String text = WikiMarkup.toPlain(raw, WikiPatchNotes::template);
        return text.replace('\n', ' ').replaceAll("\\s{2,}", " ").trim();
    }

    private static String mark(String style, String ref, String text) {
        return START + style + SEP + (ref == null ? "" : ref) + SEP + text + END;
    }

    /** Zerlegt Text mit (auch verschachtelten) Marken in Teile; innen gilt der innere Stil. */
    static List<Part> parts(String marked) {
        List<Part> parts = new ArrayList<>();
        java.util.ArrayDeque<String[]> stack = new java.util.ArrayDeque<>(); // {Stil, Bezug}
        StringBuilder current = new StringBuilder();
        int i = 0;
        while (i < marked.length()) {
            char c = marked.charAt(i);
            if (c == START) {
                int sep1 = marked.indexOf(SEP, i + 1);
                int sep2 = sep1 < 0 ? -1 : marked.indexOf(SEP, sep1 + 1);
                if (sep2 < 0) {
                    i++;
                    continue;
                }
                flush(parts, current, stack.peek());
                String ref = marked.substring(sep1 + 1, sep2);
                stack.push(new String[] {marked.substring(i + 1, sep1), ref.isEmpty() ? null : ref});
                i = sep2 + 1;
            } else if (c == END) {
                flush(parts, current, stack.peek());
                stack.poll();
                i++;
            } else {
                if (c != SEP) {
                    current.append(c);
                }
                i++;
            }
        }
        flush(parts, current, stack.peek());
        return parts;
    }

    private static void flush(List<Part> parts, StringBuilder text, String[] styleRef) {
        if (text.length() == 0) {
            return;
        }
        String style = styleRef == null ? null : styleRef[0];
        String ref = styleRef == null ? null : styleRef[1];
        Part last = parts.isEmpty() ? null : parts.get(parts.size() - 1);
        if (last != null && Objects.equals(last.style(), style) && Objects.equals(last.ref(), ref)) {
            parts.set(parts.size() - 1, new Part(last.text() + text, style, ref));
        } else {
            parts.add(new Part(text.toString(), style, ref));
        }
        text.setLength(0);
    }

    private static final Pattern TREND_VERB = Pattern.compile("\\b(increased|reduced|decreased|lowered)\\b");
    /** Werte, bei denen „weniger“ besser ist (kürzere Abklingzeit = Buff). */
    private static final Pattern LOWER_IS_BETTER = Pattern.compile(
            "cooldown|\\bcost|cast time|wind-?up|delay|damage taken|incoming damage|recharge|lockout|channel time"
                    + "|travel time|static cooldown|\\bwait\\b");

    /**
     * Grobe Einstufung einer Zeile: „increased“/„reduced“ mit dem Wert davor, „New Effect“ = Buff, „Removed“ = Nerf.
     * Bei Werten wie Abklingzeit oder Kosten kehrt sich die Richtung um. Bugfixes und Zeilen ohne Richtung: {@code null}.
     */
    static String trendOf(String text) {
        String t = text.toLowerCase(Locale.ROOT).trim();
        if (t.startsWith("bug fix")) {
            return null;
        }
        if (t.startsWith("new effect") || t.startsWith("new:")) {
            return "buff";
        }
        if (t.startsWith("removed")) {
            return "nerf";
        }
        Matcher m = TREND_VERB.matcher(t);
        if (!m.find()) {
            return null;
        }
        boolean up = "increased".equals(m.group(1));
        boolean inverse = LOWER_IS_BETTER.matcher(t.substring(0, m.start())).find();
        return up != inverse ? "buff" : "nerf";
    }

    /** Wertfarbe eines {@code {{as|…}}} wie im Wiki: aus dem Typ-Argument oder Schlüsselwörtern im Text. */
    static String statStyle(String text, String type) {
        String t = ((type == null ? "" : type) + " " + text).toLowerCase(Locale.ROOT);
        if (t.matches(".*\\b(ap|ability power|magic damage)\\b.*")) {
            return "ap";
        }
        if (t.matches(".*\\b(ad|attack damage|physical damage)\\b.*")) {
            return "ad";
        }
        if (t.matches(".*\\b(health|hp|heal|healing)\\b.*")) {
            return "health";
        }
        if (t.matches(".*\\bmana\\b.*")) {
            return "mana";
        }
        if (t.matches(".*\\b(magic resist(ance)?|mr)\\b.*")) {
            return "mr";
        }
        if (t.matches(".*\\barmor\\b.*")) {
            return "armor";
        }
        if (t.matches(".*\\b(attack speed|as)\\b.*")) {
            return "as";
        }
        if (t.matches(".*\\b(movement speed|move speed|ms)\\b.*")) {
            return "ms";
        }
        if (t.matches(".*\\bcrit(ical)?\\b.*")) {
            return "crit";
        }
        if (t.matches(".*\\b(ability haste|haste)\\b.*")) {
            return "haste";
        }
        return "scale";
    }

    private static String template(String name, List<String> positional, Map<String, String> named) {
        String first = positional.isEmpty() ? "" : positional.get(0);
        switch (name) {
            case "as" -> {
                return first.isEmpty() ? "" : mark(statStyle(first, positional.size() > 1 ? positional.get(1) : null), null, first);
            }
            case "sbc" -> {
                return first.isEmpty() ? "" : mark("label", null, first);
            }
            case "ai" -> {
                String shown = positional.size() >= 3 && !positional.get(2).isEmpty() ? positional.get(2) : first;
                String champion = positional.size() >= 2 ? positional.get(1) : "";
                return first.isEmpty() ? "" : mark("ability", champion + "|" + first, shown);
            }
            case "ci", "ii", "ri", "si" -> {
                return first.isEmpty() ? "" : mark(kindOf(name), first, first);
            }
            default -> {
                // weiter unten
            }
        }
        if ("rd".equals(name) && positional.size() >= 2) {
            return positional.get(0) + " melee / " + positional.get(1) + " ranged";
        }
        // Stufenweise Werte je Level ("1275; then +75*x for 4; …"): die lesbare Formel des Wikis statt der Rohangabe.
        if ("pp".equals(name) && !positional.isEmpty() && positional.get(0).contains("then")) {
            String formula = named.get("formula");
            if (formula != null && !formula.isBlank()) {
                return "(" + formula.trim() + ")";
            }
            return positional.get(0).split(";")[0].trim() + " (based on level)";
        }
        return null;
    }

    /**
     * Zeilen des Quelltexts; ein Template über mehrere Zeilen (z. B. {@code {{Quote|…}}}) wird zu einer Zeile
     * zusammengefasst, damit Aufzählungen und Überschriften richtig erkannt werden.
     */
    static List<String> logicalLines(String wikitext) {
        List<String> out = new ArrayList<>();
        if (wikitext == null) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        int open = 0;
        for (String line : wikitext.replace("\r", "").split("\n", -1)) {
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(line);
            open += count(line, "{{") - count(line, "}}");
            if (open <= 0) {
                String logical = current.toString().trim();
                if (!logical.isEmpty()) {
                    out.add(logical);
                }
                current.setLength(0);
                open = 0;
            }
        }
        if (current.length() > 0) {
            out.add(current.toString().trim());
        }
        return out;
    }

    private static int count(String s, String token) {
        int n = 0;
        for (int i = s.indexOf(token); i >= 0; i = s.indexOf(token, i + token.length())) {
            n++;
        }
        return n;
    }
}
