package com.leaguetool.data.wiki;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikiTemplateParserTest {

    @Test
    void parsesTopLevelParametersOfRealPage() {
        WikiTemplateParser.TemplateCall call = WikiTemplateParser.parseCall(
                WikiTestPages.page(WikiTestPages.AHRI, "Template:Data Ahri/Orb of Deception"));
        assertEquals("", call.name());
        assertEquals(List.of("Orb of Deception", "", "", "", ""), call.positional());

        Map<String, String> p = call.params();
        assertEquals("Ahri", p.get("champion"));
        assertEquals("Q", p.get("skill"));
        assertEquals("7", p.get("cooldown"));
        assertEquals("{{ap|55 to 95}}", p.get("cost"));
        assertEquals("mana", p.get("costtype"));
        assertEquals("{{fd|0.25}}", p.get("cast time"));
        assertEquals("{{tip|cr|icononly = true}} {{tt|100|Circle check at return point}}", p.get("effect radius"));
        assertEquals("{{st|Damage Per Pass|{{ap|35 to 135}} {{as|(+ 50% AP)}}|Total Mixed Damage|"
                + "{{ap|35*2 to 135*2}} {{as|(+ {{ap|50*2}}% AP)}}}}", p.get("leveling"));
    }

    @Test
    void pipesInsideNestedTemplatesDoNotSplit() {
        Map<String, String> p = WikiTemplateParser.parse(
                WikiTestPages.page(WikiTestPages.AHRI, "Template:Data Ahri/Orb of Deception"));
        String details = p.get("damagedetails");
        assertTrue(details.startsWith("{{Infobox damage"), details);
        assertTrue(details.contains("|dtitle      = Ahri - Orb of Deception: Outgoing Missile"), details);
        assertFalse(p.containsKey("dtitle"));
        assertFalse(p.containsKey("dtype"));
        // mehrzeilige Notizen mit Kommentar und Templates
        String notes = p.get("notes");
        assertTrue(notes.startsWith("* ''Orb of Deception'' will hit"), notes);
        assertTrue(notes.contains("{{bug}}"), notes);
        assertFalse(notes.contains("<!--"), notes);
        assertTrue(p.containsKey("blurb"));
    }

    @Test
    void multilineValues() {
        Map<String, String> p = WikiTemplateParser.parse(
                WikiTestPages.page(WikiTestPages.AHRI, "Template:Data Ahri/Charm"));
        assertEquals("{{st|Magic Damage|{{ap|80 to 240}} {{as|(+ 85% AP)}}}}\n"
                + "{{st|Disable Duration|{{ap|1.2 to 1.8}} seconds}}", p.get("leveling"));
        assertEquals("12", p.get("cooldown"));
        assertEquals("60", p.get("cost"));
    }

    @Test
    void variablesAreSubstituted() {
        WikiTemplateParser.TemplateCall call = WikiTemplateParser.parseCall(
                WikiTestPages.page(WikiTestPages.AATROX, "Template:Data Aatrox/The Darkin Blade"));
        Map<String, String> p = call.params();
        assertEquals("The Darkin Blade", p.get("name"));
        assertEquals("The Darkin Blade", call.positional().get(0));
        assertTrue(p.get("leveling3").startsWith("{{st|First Cast Damage|{{ap|10 to 70}} {{as|(+ {{ap|60 to 90}}% AD)}}"),
                p.get("leveling3"));
        assertTrue(p.get("description2").contains("{{#expr:(1.75-1)*100}}"), p.get("description2"));
        assertTrue(p.get("leveling6").startsWith("{{st|Maximum Non-Minion Non-Sweetspot Damage|"), p.get("leveling6"));
        assertFalse(p.toString().contains("#var"));
    }

    @Test
    void syntheticTemplate() {
        Map<String, String> p = WikiTemplateParser.parse("""
                <!-- Kommentar -->{{#vardefine:x|5}}{{Foo|positional
                | key one  = [[Seite|Text]] und {{t|a|b=c}}
                |zahl={{#var:x}}
                |leer =
                |doppelt = 1
                |doppelt = 2
                }}
                Text danach""");
        assertEquals("[[Seite|Text]] und {{t|a|b=c}}", p.get("key one"));
        assertEquals("5", p.get("zahl"));
        assertEquals("", p.get("leer"));
        assertEquals("2", p.get("doppelt"));
        assertEquals(List.of("key one", "zahl", "leer", "doppelt"), List.copyOf(p.keySet()));
    }

    @Test
    void variablesUseTheValueValidAtTheirPosition() {
        assertEquals("1 2", WikiTemplateParser.resolveVariables(
                "{{#vardefine:x|1}}{{#var:x}} {{#vardefine:x|2}}{{#var:x}}"));
        assertEquals("d v", WikiTemplateParser.resolveVariables("{{#var:y|d}} {{#vardefine:y|v}}{{#var:y}}"));
        assertEquals("3", WikiTemplateParser.resolveVariables("{{#vardefine:a|3}}{{#vardefine:b|{{#var:a}}}}{{#var:b}}"));
        assertEquals("echo5", WikiTemplateParser.resolveVariables("echo{{#vardefineecho:z|5}}"));

        Map<String, String> p = WikiTemplateParser.parse(
                "{{#vardefine:x|1}}{{Foo\n|a = {{#var:x}}{{#vardefine:x|2}}\n|b = {{#var:x}}\n}}");
        assertEquals("1", p.get("a"));
        assertEquals("2", p.get("b"));
    }

    @Test
    void keysAreLowercaseLikeInWikiMarkup() {
        Map<String, String> p = WikiTemplateParser.parse("{{Foo|Cooldown = 7|Cast Time=0.25|x=1}}");
        assertEquals(List.of("cooldown", "cast time", "x"), List.copyOf(p.keySet()));
        assertEquals("", WikiMarkup.toPlain("{{tip|cr|IconOnly = true}}"));
    }

    @Test
    void rejectsRedirectsAndPagesWithoutTemplate() {
        assertThrows(IllegalArgumentException.class,
                () -> WikiTemplateParser.parse("#REDIRECT [[Template:Data Aatrox/The Darkin Blade]]"));
        assertThrows(IllegalArgumentException.class, () -> WikiTemplateParser.parse("nur Text"));
        assertThrows(IllegalArgumentException.class, () -> WikiTemplateParser.parse("{{offen|a=1"));
        assertThrows(IllegalArgumentException.class, () -> WikiTemplateParser.parse(""));
    }
}
