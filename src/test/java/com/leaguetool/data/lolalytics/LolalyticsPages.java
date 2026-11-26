package com.leaguetool.data.lolalytics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Erzeugt Testseiten im Aufbau des eingebetteten Lolalytics-Seitenzustands (flache Liste mit Base-36-Verweisen). */
public final class LolalyticsPages {

    private LolalyticsPages() {
    }

    public static String tierList() {
        Page p = new Page();
        Map<String, Object> rows = new LinkedHashMap<>();
        rows.put("103", row(p, 2, "middle", 95.15, 53.11, 10.03, 3.24, 14, 579_664));
        rows.put("222", row(p, 0, "middle", 0.19, 48.21, 0.03, 7.78, 0, 1_562));
        rows.put("1", row(p, 15, "middle", 30.0, 47.0, 1.0, 0.5, -50, 4_454));
        Map<String, Object> list = new LinkedHashMap<>();
        list.put("analysed", p.ref(57_802_290L));
        list.put("avgWr", p.ref(51.73));
        list.put("cid", p.ref(rows));
        p.ref(list);
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("patch", p.ref("16.22"));
        page.put("champPath", p.ref(Map.of("103", p.ref("ahri"), "222", p.ref("jinx"))));
        p.ref(page);
        return p.html();
    }

    private static String row(Page p, int tier, String lane, double pctLane, double wr, double pr, double br, int pbi, int games) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("tier", p.ref(tier));
        r.put("lane", p.ref(lane));
        r.put("pctLane", p.ref(pctLane));
        r.put("wr", p.ref(wr));
        r.put("pr", p.ref(pr));
        r.put("br", p.ref(br));
        r.put("pbi", p.ref(pbi));
        r.put("games", p.ref(games));
        return p.ref(r);
    }

    public static String build() {
        Page p = new Page();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("n", p.ref(579_664));
        header.put("wr", p.ref(53.11));
        header.put("pr", p.ref(9.96));
        header.put("br", p.ref(3.24));

        Map<String, Object> runePage = new LinkedHashMap<>();
        runePage.put("wr", p.ref(52.98));
        runePage.put("n", p.ref(499_515));
        runePage.put("set", p.ref(Map.of(
                "pri", p.list(8112, 8139),
                "sec", p.list(8226, 8210),
                "mod", p.list(5005, 5008, 5001))));
        Map<String, Object> pick = Map.of("runes", p.ref(runePage));

        Map<String, Object> build = new LinkedHashMap<>();
        build.put("header", p.ref(header));
        build.put("summary", p.ref(Map.of("pick", p.ref(pick))));
        build.put("itemSets", p.ref(Map.of()));
        build.put("runes", p.ref(Map.of("stats", p.ref(Map.of(
                "8112", p.ref(List.of(p.list(86.2, 53.0, 499_515))),
                "8226", p.ref(List.of(p.list(0.5, 50.0, 2_900), p.list(41.2, 54.0, 238_800))),
                "5008f", p.ref(List.of(p.list(6.0, 54.0, 34_500))),
                "5005", p.ref(List.of(p.list(7.1, 52.0, 41_000))),
                "5013", p.ref(List.of(p.list(3.8, 53.5, 22_000))))))));
        build.put("startSet", p.ref(List.of(p.list("1056_2003_2003", 53.12, 99.19, 574_940), p.list("", 48.48, 0.01, 66))));
        build.put("builtBootSet3", p.ref(List.of(p.list("3118_3020_4645", 53.66, 17.68, 102_489))));
        build.put("boots", p.ref(List.of(p.list(3020, 53.86, 57.72, 334_594, 13))));
        // Itemplatz 1 liefert die Seite, Itemplatz 2 fehlt (wird aus den Core-Builds abgeleitet).
        build.put("item1", p.ref(List.of(p.list(3118, 53.9, 60.0, 347_798, 11), p.list(6655, 52.1, 30.0, 173_899, 11),
                p.list(3041, 60.0, 10.0, 57_966, 12))));   // Mejai's: beste Winrate, aber Snowballing-Item
        build.put("item3", p.ref(List.of(p.list(3157, 56.67, 20.48, 119_752, 27))));
        // 3118 steckt im Core-Build, 3135 hat zu wenige Spiele.
        build.put("item4", p.ref(List.of(p.list(3118, 60.0, 10.0, 60_000, 31), p.list(3089, 60.79, 7.79, 45_562, 31),
                p.list(3135, 55.0, 0.01, 50, 31))));
        build.put("item5", p.ref(List.of(p.list(3089, 62.15, 1.53, 8_924, 34))));
        build.put("skillOrder", p.ref(List.of(p.list("QWE", 53.4, 91.2, 523_408))));
        build.put("skill6", p.ref(List.of(p.list(213_114, 53.1, 66.8, 386_979))));
        // Komplette Skillung bis Level 15: ab Level 8 E vor Q (weicht von der Max-Reihenfolge QWE ab).
        build.put("skill15", p.ref(List.of(p.list(213_114_131_343_322L, 55.9, 61.2, 354_100))));
        build.put("spells", p.ref(List.of(p.list("4_14", 53.52, 70.26, 407_292))));
        build.put("enemy_h", p.list("id", "wr", "d1", "d2", "pr", "n"));
        build.put("enemy", p.ref(Map.of("top", p.ref(List.of(p.list(266, 57.36, 8.88, 3.16, 3.87, 1630))))));
        header.put("damage", p.ref(Map.of("physical", p.ref(1785.05), "magic", p.ref(17893.03), "true", p.ref(5325.13))));
        build.put("sidebar", p.ref(Map.of("time", p.ref(Map.of(
                "time", p.ref(Map.of("1", p.ref(400), "2", p.ref(600))),
                "timeWin", p.ref(Map.of("1", p.ref(200), "2", p.ref(360))))))));
        p.ref(build);
        return p.html();
    }

    /** Antwort der Team-Schnittstelle mit einem Mitspieler auf der Top-Lane. */
    public static String team(int champion, double winRate, int games) {
        return "{\"team_h\":[\"id\",\"wr\",\"d1\",\"d2\",\"pr\",\"n\"],\"team\":{\"top\":[[" + champion + "," + winRate
                + ",0.02,-0.33,4.32," + games + "]]}}";
    }

    /** Tierliste einer Lane; jede Zeile ist {@code {Key, Winrate in Prozent, Spiele}}, alle mit dem Pfad {@code c<Key>}. */
    public static String laneTierList(String lane, double average, double[]... champions) {
        Page p = new Page();
        Map<String, Object> rows = new LinkedHashMap<>();
        Map<String, Object> slugs = new LinkedHashMap<>();
        for (double[] champion : champions) {
            String key = String.valueOf((int) champion[0]);
            rows.put(key, row(p, 1, lane, 90, champion[1], 5, 1, 1, (int) champion[2]));
            slugs.put(key, p.ref("c" + key));
        }
        Map<String, Object> list = new LinkedHashMap<>();
        list.put("analysed", p.ref(1_000_000L));
        list.put("avgWr", p.ref(average));
        list.put("cid", p.ref(rows));
        p.ref(list);
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("patch", p.ref("16.22"));
        page.put("champPath", p.ref(slugs));
        p.ref(page);
        return p.html();
    }

    private static final class Page {

        private final List<Object> objects = new ArrayList<>();

        String ref(Object value) {
            objects.add(value);
            return Integer.toString(objects.size() - 1, 36);
        }

        String list(Object... values) {
            List<String> refs = new ArrayList<>();
            for (Object value : values) {
                refs.add(ref(value));
            }
            return ref(refs);
        }

        String html() {
            try {
                String json = new ObjectMapper().writeValueAsString(Map.of("objs", objects));
                return "<html><script type=\"qwik/json\">" + json + "</script></html>";
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
