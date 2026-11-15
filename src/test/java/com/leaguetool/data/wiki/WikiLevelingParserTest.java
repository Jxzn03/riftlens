package com.leaguetool.data.wiki;

import com.leaguetool.model.AbilitySlot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikiLevelingParserTest {

    @Test
    void ahriOrbOfDeception() {
        Map<String, String> p = WikiTemplateParser.parse(
                WikiTestPages.page(WikiTestPages.AHRI, "Template:Data Ahri/Orb of Deception"));
        List<WikiLeveling> l = WikiLevelingParser.parse(p.get("leveling"), 5);
        assertEquals(2, l.size());

        WikiLeveling perPass = l.get(0);
        assertEquals("Damage Per Pass", perPass.label());
        assertEquals(List.of(35.0, 60.0, 85.0, 110.0, 135.0), perPass.valuesPerRank());
        assertEquals(List.of(new WikiRatio(List.of(50.0, 50.0, 50.0, 50.0, 50.0), "AP")), perPass.ratios());
        assertEquals("35 – 135 (+ 50% AP)", perPass.text());

        WikiLeveling total = l.get(1);
        assertEquals("Total Mixed Damage", total.label());
        assertEquals(List.of(70.0, 120.0, 170.0, 220.0, 270.0), total.valuesPerRank());
        assertEquals("AP", total.ratios().get(0).stat());
        assertEquals(List.of(100.0, 100.0, 100.0, 100.0, 100.0), total.ratios().get(0).percentsPerRank());
    }

    @Test
    void ahriCharmWithTwoStatTables() {
        Map<String, String> p = WikiTemplateParser.parse(WikiTestPages.page(WikiTestPages.AHRI, "Template:Data Ahri/Charm"));
        List<WikiLeveling> l = WikiLevelingParser.parse(p.get("leveling"), 5);
        assertEquals(List.of("Magic Damage", "Disable Duration"), l.stream().map(WikiLeveling::label).toList());
        assertEquals(List.of(80.0, 120.0, 160.0, 200.0, 240.0), l.get(0).valuesPerRank());
        assertEquals(List.of(85.0, 85.0, 85.0, 85.0, 85.0), l.get(0).ratios().get(0).percentsPerRank());
        assertEquals(List.of(1.2, 1.35, 1.5, 1.65, 1.8), l.get(1).valuesPerRank());
        assertTrue(l.get(1).ratios().isEmpty());
        assertEquals("1.2 – 1.8 seconds", l.get(1).text());
    }

    @Test
    void foxFireExpressions() {
        Map<String, String> p = WikiTemplateParser.parse(WikiTestPages.page(WikiTestPages.AHRI, "Template:Data Ahri/Fox-Fire"));
        List<WikiLeveling> l = WikiLevelingParser.parse(p.get("leveling2"), 5);
        assertEquals(3, l.size());
        assertEquals(List.of(16.0, 24.0, 32.0, 40.0, 48.0), l.get(1).valuesPerRank());
        assertEquals(List.of(16.0, 16.0, 16.0, 16.0, 16.0), l.get(1).ratios().get(0).percentsPerRank());
        assertEquals(List.of(72.0, 108.0, 144.0, 180.0, 216.0), l.get(2).valuesPerRank());
        assertEquals(72.0, l.get(2).ratios().get(0).percentsPerRank().get(0));
    }

    @Test
    void explicitSingleAndCustomRankValues() {
        List<WikiLeveling> explicit = WikiLevelingParser.parse("{{st|Schaden|{{ap|10|20|35*2|40|50}}}}", 5);
        assertEquals(List.of(10.0, 20.0, 70.0, 40.0, 50.0), explicit.get(0).valuesPerRank());
        assertEquals("10 / 20 / 70 / 40 / 50", explicit.get(0).text());

        List<WikiLeveling> single = WikiLevelingParser.parse("{{st|Dauer|2 seconds}}", 3);
        assertEquals(List.of(2.0, 2.0, 2.0), single.get(0).valuesPerRank());

        List<WikiLeveling> r = WikiLevelingParser.parse("{{st|Bonus|{{ap|60 to 100}}%}}", 3);
        assertEquals(List.of(60.0, 80.0, 100.0), r.get(0).valuesPerRank());

        // eigene Rangzahl ("A to B N"); konstante Ratio wird auf dieselbe Länge gebracht
        List<WikiLeveling> six = WikiLevelingParser.parse(
                "{{st|Physical Damage|{{ap|80 to 285 6}} {{as|(+ 130% '''bonus''' AD)}}}}", 5);
        assertEquals(List.of(80.0, 121.0, 162.0, 203.0, 244.0, 285.0), six.get(0).valuesPerRank());
        assertEquals(new WikiRatio(List.of(130.0, 130.0, 130.0, 130.0, 130.0, 130.0), "bonus AD"),
                six.get(0).ratios().get(0));

        assertTrue(WikiLevelingParser.parse("{{st|Nur Text|varies}}", 5).get(0).valuesPerRank().isEmpty());
        assertTrue(WikiLevelingParser.parse("", 5).isEmpty());
        assertTrue(WikiLevelingParser.parse(null, 5).isEmpty());
    }

    @Test
    void ratioForms() {
        WikiLeveling l = WikiLevelingParser.parse("{{st|Schaden|{{ap|50 to 90}} {{as|(+ 20 – 40% AP)}} "
                + "{{as|(+ {{ap|50*2}}% '''bonus''' AD)}} {{as|(+ 6% of target's '''maximum''' health)}} "
                + "{{as|(+ 10 / 20 / 30 / 40 / 50% '''bonus''' armor)}} {{as|(+ 0.5 per 100 AP)}}}}", 5).get(0);
        assertEquals(List.of(50.0, 60.0, 70.0, 80.0, 90.0), l.valuesPerRank());
        assertEquals(4, l.ratios().size());
        assertEquals(new WikiRatio(List.of(20.0, 25.0, 30.0, 35.0, 40.0), "AP"), l.ratios().get(0));
        assertEquals(new WikiRatio(List.of(100.0, 100.0, 100.0, 100.0, 100.0), "bonus AD"), l.ratios().get(1));
        assertEquals("target's maximum health", l.ratios().get(2).stat());
        assertEquals(new WikiRatio(List.of(10.0, 20.0, 30.0, 40.0, 50.0), "bonus armor"), l.ratios().get(3));
        // Skalierung ohne Prozent bleibt nur im Text
        assertTrue(l.text().endsWith("(+ 0.5 per 100 AP)"), l.text());
    }

    @Test
    void normalizesStatNames() {
        assertEquals("AP", WikiLevelingParser.normalizeStat("AP"));
        assertEquals("AD", WikiLevelingParser.normalizeStat("attack damage"));
        assertEquals("bonus AD", WikiLevelingParser.normalizeStat("bonus AD"));
        assertEquals("maximum health", WikiLevelingParser.normalizeStat("of max health"));
        assertEquals("bonus health", WikiLevelingParser.normalizeStat("bonus health"));
        assertEquals("armor", WikiLevelingParser.normalizeStat("armor"));
        assertEquals("bonus magic resistance", WikiLevelingParser.normalizeStat("bonus MR"));
        assertEquals("target's maximum health", WikiLevelingParser.normalizeStat("of the target's maximum health."));
    }

    @Test
    void rankValuesOfSimpleParameters() {
        assertEquals(List.of(7.0, 7.0, 7.0, 7.0, 7.0), WikiLevelingParser.parseRankValues("7", 5));
        assertEquals(List.of(12.0, 11.0, 10.0, 9.0, 8.0), WikiLevelingParser.parseRankValues("{{ap|12 to 8}}", 5));
        assertEquals(List.of(9.0, 8.0, 7.0, 6.0, 5.0),
                WikiLevelingParser.parseRankValues("{{tt|{{ap|9 to 5}}|Starts post-effect}}", 5));
        assertEquals(List.of(140.0, 120.0, 100.0), WikiLevelingParser.parseRankValues("{{ap|140 to 100}}", 3));
        assertEquals(List.of(1.0, 2.0, 3.0), WikiLevelingParser.parseRankValues("1 / 2 / 3", 3));
        assertEquals(List.of(6.0, 6.0, 6.0, 6.0, 6.0), WikiLevelingParser.parseRankValues("{{tt|6|Starts on-cast}}", 5));
        assertTrue(WikiLevelingParser.parseRankValues("Varied", 5).isEmpty());
        assertTrue(WikiLevelingParser.parseRankValues("{{pplevel|22 to 10}}", 5).isEmpty());
        assertTrue(WikiLevelingParser.parseRankValues(null, 5).isEmpty());
    }

    @Test
    void slashWithoutSpacesIsDivision() {
        assertEquals(List.of(80.0, 80.0, 80.0, 80.0, 80.0), WikiLevelingParser.parseRankValues("{{ap|120/1.5}}", 5));
        assertEquals(List.of(33.333333, 33.333333, 33.333333),
                WikiLevelingParser.parseRankValues("{{ap|100/3}}", 3));
        assertEquals(List.of(50.0, 50.0, 50.0, 50.0, 50.0),
                WikiLevelingParser.parse("{{st|X|{{ap|100/2}}}}", 5).get(0).valuesPerRank());
        assertEquals("33.3333", WikiMarkup.toPlain("{{ap|100/3}}"));
        // Klartext-Liste mit Leerzeichen bleibt eine Liste
        assertEquals(List.of(1.0, 2.0, 3.0), WikiLevelingParser.parseRankValues("1 / 2 / 3", 3));
    }

    @Test
    void numberFallbackOnlyForRealBaseValues() {
        WikiLeveling perLevel = WikiLevelingParser.parse(
                "{{st|Heilung|{{pp|35 to 95}} {{as|(+ 20% AP)}}}}", 5).get(0);
        assertTrue(perLevel.valuesPerRank().isEmpty(), perLevel.toString());
        assertEquals(List.of(new WikiRatio(List.of(20.0, 20.0, 20.0, 20.0, 20.0), "AP")), perLevel.ratios());
        assertEquals("35 – 95 (based on level) (+ 20% AP)", perLevel.text());

        WikiLeveling failedRatio = WikiLevelingParser.parse("{{st|Schild|{{as|(+ 0.5 per 100 AP)}}}}", 5).get(0);
        assertTrue(failedRatio.valuesPerRank().isEmpty(), failedRatio.toString());
        assertTrue(failedRatio.ratios().isEmpty());

        WikiLeveling prose = WikiLevelingParser.parse("{{st|Bonus|equal to 5% of damage dealt}}", 5).get(0);
        assertTrue(prose.valuesPerRank().isEmpty(), prose.toString());

        assertEquals(List.of(30.0, 30.0, 30.0, 30.0, 30.0),
                WikiLevelingParser.parse("{{st|Slow|{{as|30% '''bonus''' slow}}}}", 5).get(0).valuesPerRank());
        assertTrue(WikiLevelingParser.parseRankValues("{{pp|10 to 20}}", 5).isEmpty());
    }

    @Test
    void rankCountIgnoresMarkersInsideRatios() {
        WikiLeveling l = WikiLevelingParser.parse(
                "{{st|Schaden|{{ap|10 to 50}} {{as|(+ {{ap|1|2|3|4|5|6}}% AP)}}}}", 5).get(0);
        assertEquals(List.of(10.0, 20.0, 30.0, 40.0, 50.0), l.valuesPerRank());
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0), l.ratios().get(0).percentsPerRank());

        // konstante Ratio folgt der Rangzahl des Basiswerts
        WikiLeveling six = WikiLevelingParser.parse("{{st|X|{{ap|1|2|3|4|5|6}} {{as|(+ 10% AP)}}}}", 5).get(0);
        assertEquals(6, six.ratios().get(0).percentsPerRank().size());
    }

    @Test
    void defaultRanksAndDetection() {
        assertEquals(3, WikiLevelingParser.defaultRanks(AbilitySlot.R));
        assertEquals(5, WikiLevelingParser.defaultRanks(AbilitySlot.Q));
        assertEquals(3, WikiLevelingParser.defaultRanks("R"));
        assertEquals(5, WikiLevelingParser.defaultRanks("E"));
        assertEquals(6, WikiLevelingParser.detectRanks("{{st|X|{{ap|80 to 285 6}}}}").orElseThrow());
        assertEquals(4, WikiLevelingParser.detectRanks("{{ap|1|2|3|4}}").orElseThrow());
        assertTrue(WikiLevelingParser.detectRanks("{{ap|35 to 135}}").isEmpty());
        assertTrue(WikiLevelingParser.detectRanks("7").isEmpty());
    }
}
