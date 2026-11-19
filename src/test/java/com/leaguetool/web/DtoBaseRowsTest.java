package com.leaguetool.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leaguetool.data.wiki.WikiChampionData;
import com.leaguetool.model.BaseStats;
import com.leaguetool.model.Champion;
import com.leaguetool.model.ChampionInfo;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class DtoBaseRowsTest {

    private static final BaseStats AHRI = new BaseStats(590, 104, 418, 25, 330, 21, 4.2, 30, 1.3, 550, 2.5, 0.6, 8, 0.8,
            0, 0, 53, 3, 0.668, 2.2);
    private static final Champion CHAMPION = new Champion("Ahri", 103, "Ahri", "", "", List.of(), "Mana",
            new ChampionInfo(3, 4, 8, 5), AHRI, "Ahri.png");

    /** Wiki wie Data Dragon, nur Angriffsschaden pro Level anders (2.5 statt 3). */
    private static WikiChampionData wiki() {
        return new WikiChampionData("Ahri", "Ahri", 103, "", "Mana", "Ranged", "Magic", List.of("Middle"), List.of(),
                590, 104, 418, 25, 21, 4.2, 30, 1.3, 2.5, 0.6, 8, 0.8, 53, 2.5, 0.668, 2.2, 0.625, 550, 330,
                OptionalDouble.of(0.2), OptionalDouble.of(1.0), OptionalDouble.empty(), Map.of());
    }

    private static Map<String, Object> row(List<Map<String, Object>> rows, String label) {
        return rows.stream().filter(r -> label.equals(r.get("label"))).findFirst().orElseThrow();
    }

    @Test
    void prefersWikiValuesAndNamesTheDataDragonValueWhenTheyDiffer() {
        List<Map<String, Object>> rows = Dto.baseRows(CHAMPION, wiki());
        Map<String, Object> ad = row(rows, "Angriffsschaden");
        assertEquals("+2.5", String.valueOf(ad.get("perLevel")).replace(',', '.'));
        assertEquals("53 / +3", ad.get("dd"));
        assertNull(row(rows, "Leben").get("dd"), "gleiche Werte: kein Hinweis");
        assertEquals("0.625", String.valueOf(row(rows, "Angriffstempo-Ratio").get("base")).replace(',', '.'));
        assertEquals(18, ((List<?>) row(rows, "Windup").get("values")).size());
    }

    @Test
    void buildValuesContainAllBaseRowsPlusWikiExtras() {
        List<Map<String, Object>> rows = new java.util.ArrayList<>(Dto.finalRows(
                com.leaguetool.calc.ChampionBuildCalculator.compute(AHRI, 18, com.leaguetool.calc.StatBlock.EMPTY)));
        rows.addAll(Dto.wikiExtraRows(wiki()));
        List<Object> labels = rows.stream().map(r -> r.get("label")).toList();
        for (String label : List.of("Leben", "Lebensreg. (5 s)", "Mana", "Mana-Reg. (5 s)", "Angriffsschaden", "Angriffstempo",
                "Rüstung", "Magieresistenz", "Reichweite", "Lauftempo", "Angriffstempo-Ratio", "Windup")) {
            assertTrue(labels.contains(label), label);
        }
    }

    @Test
    void withoutWikiOnlyDataDragonRows() {
        List<Map<String, Object>> rows = Dto.baseRows(CHAMPION);
        assertFalse(rows.stream().anyMatch(r -> r.containsKey("dd")));
        assertFalse(rows.stream().anyMatch(r -> "Windup".equals(r.get("label"))));
    }
}
