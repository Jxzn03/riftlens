package com.leaguetool.data.wiki;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.leaguetool.data.wiki.WikiMarkup.toPlain;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WikiMarkupTest {

    @Test
    void simpleTemplatesShowTheirText() {
        assertEquals("X", toPlain("{{as|X}}"));
        assertEquals("X", toPlain("{{as|X|color}}"));
        assertEquals("X", toPlain("{{sti|X}}"));
        assertEquals("Anzeige", toPlain("{{sti|stat|Anzeige}}"));
        assertEquals("X", toPlain("{{sbc|X}}"));
        assertEquals("X", toPlain("{{ci|X}}"));
        assertEquals("X", toPlain("{{cai|X|Ahri}}"));
        assertEquals("Charm", toPlain("{{ai|Charm|Ahri}}"));
        assertEquals("Anzeige", toPlain("{{ai|Essence Theft|Ahri|Anzeige}}"));
        assertEquals("Aatrox's", toPlain("{{cis|Aatrox}}"));
    }

    @Test
    void tipTooltipAndFormatting() {
        assertEquals("a", toPlain("{{tip|a}}"));
        assertEquals("b", toPlain("{{tip|a|b}}"));
        assertEquals("", toPlain("{{tip|cr|icononly = true}}"));
        assertEquals("900", toPlain("{{tip|cr|icononly = true}} {{tt|900|Erklärung}}"));
        assertEquals("900", toPlain("{{tt|900|Erklärung}}"));
        assertEquals("0.25", toPlain("{{fd|0.25}}"));
    }

    @Test
    void abilityProgression() {
        assertEquals("35 – 135", toPlain("{{ap|35 to 135}}"));
        assertEquals("a / b / c", toPlain("{{ap|a|b|c}}"));
        assertEquals("1 / 2 / 3", toPlain("{{ap|1|2|3}}"));
        assertEquals("100", toPlain("{{ap|50*2}}"));
        assertEquals("70 – 270", toPlain("{{ap|35*2 to 135*2}}"));
        assertEquals("72 – 216", toPlain("{{ap|40*(1+0.4*2) to 120*(1+0.4*2)}}"));
        assertEquals("80 – 285", toPlain("{{ap|80 to 285 6}}"));
        assertEquals("16", toPlain("{{ap|40*0.4}}"));
        assertEquals("35 – 95 (based on level)", toPlain("{{pplevel|35 to 95|color=heal}}"));
        assertEquals("4% – 10% (based on level)", toPlain("{{pplevel|key=%|4 to 10}}"));
    }

    @Test
    void statTableAndDivisions() {
        assertEquals("Label: Wert\nLabel2: Wert2", toPlain("{{st|Label|Wert|Label2|Wert2}}"));
        assertEquals("Damage Per Pass: 35 – 135 (+ 50% AP)",
                toPlain("{{st|Damage Per Pass|{{ap|35 to 135}} {{as|(+ 50% AP)}}}}"));
        assertEquals("a / b", toPlain("{{dv|a|b}}"));
        assertEquals("825 / 765", toPlain("{{dv|{{tip|cr|icononly = true}} 825|{{tip|er|icononly = true}} 765}}"));
    }

    @Test
    void markupLinksCommentsAndBreaks() {
        assertEquals("fett und kursiv", toPlain("'''fett''' und ''kursiv''"));
        assertEquals("Ahri's Orb", toPlain("'''Ahri's''' Orb"));
        assertEquals("Text und Seite", toPlain("[[Seite|Text]] und [[Seite]]"));
        assertEquals("vor nach", toPlain("vor <!-- weg {{as|x}} --> nach"));
        assertEquals("Zeile 1\nZeile 2\nZeile 3", toPlain("Zeile 1<br>Zeile 2<br/>Zeile 3"));
        assertEquals("Text", toPlain("[[File:X.png|20px|link=]] Text"));
        assertEquals("mit Bildunterschrift", toPlain(
                "[[File:A.png|thumb|Caption mit [[Link|Text]]]]mit Bildunterschrift"));
        assertEquals("a & b", toPlain("a &amp; b"));
    }

    @Test
    void unknownTemplates() {
        assertEquals("Satz.", toPlain("Satz.{{bug}}"));
        assertEquals("Satz.", toPlain("Satz.{{bug|1}}"));
        assertEquals("", toPlain("{{Effect at cast time end}}"));
        assertEquals("b", toPlain("{{unbekannt|a|b}}"));
        assertEquals("b", toPlain("{{unbekannt|a|b|name=x}}"));
        assertEquals("", toPlain("{{Infobox damage\n|dtitle = Ahri - Orb\n|dtype = m\n}}"));
    }

    @Test
    void nestedTemplatesAreResolvedInsideOut() {
        assertEquals("40% bonus movement speed", toPlain("{{sti|{{as|40% '''bonus''' movement speed}}}}"));
        assertEquals("9 – 5", toPlain("{{tt|{{ap|9 to 5}}|Starts post-effect}}"));
        assertEquals("(+ 100% AP)", toPlain("{{as|(+ {{ap|50*2}}% AP)}}"));
        assertEquals("75.92° per second / 1400",
                toPlain("{{dv|{{tt|{{fd|75.92}}{{degree}} per second|Orbiting}}|{{tt|1400|Fired missile speed}}}}"));
    }

    @Test
    void parserFunctions() {
        assertEquals("75% bonus", toPlain("{{#expr:(1.75-1)*100}}% bonus"));
        assertEquals("ja", toPlain("{{#if:x|ja|nein}}"));
        assertEquals("nein", toPlain("{{#if:|ja|nein}}"));
        assertEquals("gleich", toPlain("{{#ifeq:a|a|gleich|ungleich}}"));
        assertEquals("", toPlain("{{#vardefine:x|1}}"));
        assertEquals("default", toPlain("{{#var:unbekannt|default}}"));
    }

    @Test
    void robustAgainstBrokenMarkup() {
        assertEquals("{{offen", toPlain("{{offen"));
        assertEquals("[[offen", toPlain("[[offen"));
        assertEquals("", toPlain(null));
        assertEquals("", toPlain(""));
    }

    @Test
    void realAbilityDescription() {
        Map<String, String> p = WikiTemplateParser.parse(
                WikiTestPages.page(WikiTestPages.AHRI, "Template:Data Ahri/Orb of Deception"));
        assertEquals("Active: Ahri sends her orb in the target direction that deals magic damage to enemies it passes "
                + "through. Upon reaching maximum range, it returns to her to deal the same amount in true damage to "
                + "enemies it passes through.", toPlain(p.get("description")));
        assertEquals("900", toPlain(p.get("range")));
        assertEquals("1550 / 60 - 2600", toPlain(p.get("speed")));
        assertEquals("Damage Per Pass: 35 – 135 (+ 50% AP)\nTotal Mixed Damage: 70 – 270 (+ 100% AP)",
                toPlain(p.get("leveling")));
    }
}
