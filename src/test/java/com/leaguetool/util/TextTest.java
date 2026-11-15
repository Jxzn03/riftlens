package com.leaguetool.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextTest {

    @Test
    void convertsDataDragonMarkupToPlainText() {
        String html = "<mainText><stats><attention>130</attention> Ability Power</stats><br><br>"
                + "<passive>Magical Opus</passive><br>Increases your total <scaleAP>Ability Power by 30%</scaleAP>.</mainText>";
        assertEquals("130 Ability Power\n\nMagical Opus\nIncreases your total Ability Power by 30%.",
                Text.htmlToPlain(html));
        assertEquals("a & b", Text.htmlToPlain("a &amp; b"));
        assertEquals("", Text.htmlToPlain(null));
    }

    @Test
    void formatsNumbers() {
        assertEquals("7", Text.num(7.0));
        assertEquals("0.668", Text.num(0.668));
        assertEquals("2.5", Text.num(2.50));
        assertEquals("52.3 %", Text.percent(0.5234));
        assertEquals("7", Text.perRank(List.of(7.0, 7.0, 7.0)));
        assertEquals("12 / 11 / 10", Text.perRank(List.of(12.0, 11.0, 10.0)));
    }

    @Test
    void shortensPatches() {
        assertEquals("16.22", Patches.shortPatch("16.22.1"));
        assertEquals("16.22", Patches.shortPatch("16.22.712.5432"));
        assertEquals(true, Patches.isRegularVersion("16.22.1"));
        assertEquals(false, Patches.isRegularVersion("lolpatch_7.20"));
        assertEquals(List.of("16.22.1", "16.3.1", "15.24.1"),
                List.of("16.3.1", "15.24.1", "16.22.1").stream().sorted(Patches.NEWEST_FIRST).toList());
    }
}
