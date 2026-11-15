package com.leaguetool.data.wiki;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuaTableParserTest {

    @Test
    void parsesKeysOfAllForms() {
        Map<String, Object> t = LuaTableParser.parseTable(
                "{ [\"name\"] = \"Ahri\", plain = 1, [3] = true, ['single'] = 'x' }");
        assertEquals("Ahri", t.get("name"));
        assertEquals(1.0, t.get("plain"));
        assertEquals(Boolean.TRUE, t.get("3"));
        assertEquals("x", t.get("single"));
        assertEquals(List.of("name", "plain", "3", "single"), List.copyOf(t.keySet()));
    }

    @Test
    void sequentialTablesBecomeLists() {
        assertEquals(List.of("Orb", "Fox"), LuaTableParser.parse("{[1] = \"Orb\", [2] = \"Fox\"}"));
        assertEquals(List.of("Burst", "Mage"), LuaTableParser.parse("{\"Burst\", \"Mage\",}"));
        assertEquals(List.of(1.0, 2.0, 3.0), LuaTableParser.parse("{[2] = 2, [1] = 1, [3] = 3}"));
        assertEquals(List.of(), LuaTableParser.parse("{}"));
    }

    @Test
    void nonSequentialOrMixedTablesBecomeMaps() {
        Object gap = LuaTableParser.parse("{[1] = 'a', [3] = 'c'}");
        assertEquals(Map.of("1", "a", "3", "c"), gap);
        Object mixed = LuaTableParser.parse("{'a', key = 2}");
        assertEquals(Map.of("1", "a", "key", 2.0), mixed);
    }

    @Test
    void parsesNestedTables() {
        Map<String, Object> t = LuaTableParser.parseTable(
                "{ stats = { hp = 590, aram = { dmg = 1.05 } }, roles = { 'Burst' }, skills = { [1] = { 'a', 'b' } } }");
        Map<?, ?> stats = assertInstanceOf(Map.class, t.get("stats"));
        assertEquals(590.0, stats.get("hp"));
        assertEquals(Map.of("dmg", 1.05), stats.get("aram"));
        assertEquals(List.of("Burst"), t.get("roles"));
        assertEquals(List.of(List.of("a", "b")), t.get("skills"));
    }

    @Test
    void parsesNumbers() {
        List<?> n = assertInstanceOf(List.class,
                LuaTableParser.parse("{ -0.1, 1e3, 2.5E-2, 0x10, .5, 7., 84+1000/17, (1+2)*3, -2^2, 10 - -2 }"));
        assertEquals(-0.1, n.get(0));
        assertEquals(1000.0, n.get(1));
        assertEquals(0.025, n.get(2));
        assertEquals(16.0, n.get(3));
        assertEquals(0.5, n.get(4));
        assertEquals(7.0, n.get(5));
        assertEquals(84 + 1000.0 / 17, (Double) n.get(6), 1e-12);
        assertEquals(9.0, n.get(7));
        assertEquals(-4.0, n.get(8));
        assertEquals(12.0, n.get(9));
    }

    @Test
    void parsesStringEscapes() {
        List<?> s = assertInstanceOf(List.class, LuaTableParser.parse(
                "{ \"say \\\"hi\\\"\", 'it\\'s', \"back\\\\slash\", \"line\\nbreak\", \"tab\\tx\", \"\\65\\066\", "
                        + "'Bel\\'Veth', \"Kai'Sa\", [[long \"raw\" \\n]], [==[with ]] inside]==] }"));
        assertEquals("say \"hi\"", s.get(0));
        assertEquals("it's", s.get(1));
        assertEquals("back\\slash", s.get(2));
        assertEquals("line\nbreak", s.get(3));
        assertEquals("tab\tx", s.get(4));
        assertEquals("AB", s.get(5));
        assertEquals("Bel'Veth", s.get(6));
        assertEquals("Kai'Sa", s.get(7));
        assertEquals("long \"raw\" \\n", s.get(8));
        assertEquals("with ]] inside", s.get(9));
    }

    @Test
    void byteEscapesAreDecodedAsUtf8() {
        // x41 = "A", u{20AC} = Euro, 195 169 = UTF-8-Bytes von "é", xC3 xA9 ebenso
        List<?> s = assertInstanceOf(List.class, LuaTableParser.parse(
                "{ \"\\x41\\u{20AC}\\195\\169\", 'Pok\\xC3\\xA9mon', \"\\0651\", \"\\u{1F600}\" }"));
        assertEquals("A€é", s.get(0));
        assertEquals("Pokémon", s.get(1));
        assertEquals("A1", s.get(2)); // \065 gefolgt von "1"
        assertEquals(new String(Character.toChars(0x1F600)), s.get(3));
    }

    @Test
    void rejectsInvalidEscapes() {
        for (String bad : List.of("\"\\x4\"", "\"\\x+1\"", "\"\\xG1\"", "\"\\u{+41}\"", "\"\\u{}\"", "\"\\u41\"",
                "\"\\u{110000}\"", "\"\\u{D800}\"", "\"\\256\"", "\"\\999\"", "\"\\xC3\"", "\"\\xFF\\xFE\"")) {
            LuaTableParser.LuaParseException e =
                    assertThrows(LuaTableParser.LuaParseException.class, () -> LuaTableParser.parse(bad), bad);
            assertEquals(1, e.line(), bad);
        }
    }

    @Test
    void parsesBooleansAndNil() {
        Map<String, Object> t = LuaTableParser.parseTable("{ a = true, b = false, c = nil }");
        assertEquals(Boolean.TRUE, t.get("a"));
        assertEquals(Boolean.FALSE, t.get("b"));
        assertTrue(t.containsKey("c"));
        assertNull(t.get("c"));
        assertEquals(Arrays.asList(1.0, null, 3.0), LuaTableParser.parse("{1, nil, 3}"));
    }

    @Test
    void skipsCommentsAndReturn() {
        String src = """
                -- <pre>
                return {
                  -- Zeilenkommentar
                  ["a"] = 1, -- dahinter
                  --[[ Block
                       über mehrere Zeilen ]]
                  ["b"] --[==[ zwischen Schlüssel und = ]==] = 2,
                  --["c"] = { auskommentiert
                  --},
                  ["d"] = "-- kein Kommentar",
                }
                -- </pre>
                -- [[Category:Lua]]
                """;
        Map<String, Object> t = LuaTableParser.parseTable(src);
        assertEquals(Map.of("a", 1.0, "b", 2.0, "d", "-- kein Kommentar"), t);
    }

    @Test
    void reportsErrorsWithPosition() {
        LuaTableParser.LuaParseException missingBrace =
                assertThrows(LuaTableParser.LuaParseException.class, () -> LuaTableParser.parse("{\n  a = 1,\n"));
        assertEquals(3, missingBrace.line());
        assertTrue(missingBrace.getMessage().contains("Zeile 3"), missingBrace.getMessage());
        assertTrue(missingBrace.getMessage().contains("'}' erwartet"), missingBrace.getMessage());

        LuaTableParser.LuaParseException missingComma =
                assertThrows(LuaTableParser.LuaParseException.class, () -> LuaTableParser.parse("{1 2}"));
        assertEquals(1, missingComma.line());
        assertEquals(4, missingComma.column());
        assertEquals(3, missingComma.position());

        assertThrows(LuaTableParser.LuaParseException.class, () -> LuaTableParser.parse("{ a = }"));
        assertThrows(LuaTableParser.LuaParseException.class, () -> LuaTableParser.parse("{ \"offen }"));
        assertThrows(LuaTableParser.LuaParseException.class, () -> LuaTableParser.parse("{ --[[ offen }"));
        assertThrows(LuaTableParser.LuaParseException.class, () -> LuaTableParser.parse("{ x = \"\\q\" }"));
        assertThrows(LuaTableParser.LuaParseException.class, () -> LuaTableParser.parse("{ [nil] = 1 }"));
        assertThrows(LuaTableParser.LuaParseException.class, () -> LuaTableParser.parse("{} extra"));
        assertThrows(LuaTableParser.LuaParseException.class, () -> LuaTableParser.parse(""));
        assertThrows(LuaTableParser.LuaParseException.class, () -> LuaTableParser.parseTable("{1, 2}"));
        assertEquals(Map.of(), LuaTableParser.parseTable("return {}"));
    }

    @Test
    void parsesRealChampionDataFixture() {
        Map<String, Object> root = LuaTableParser.parseTable(WikiTestPages.championLua());
        assertEquals(List.of("Aatrox", "Ahri", "Kai'Sa", "Kled & Skaarl", "Wukong"), List.copyOf(root.keySet()));

        Map<?, ?> ahri = assertInstanceOf(Map.class, root.get("Ahri"));
        assertEquals(103.0, ahri.get("id"));
        Map<?, ?> stats = assertInstanceOf(Map.class, ahri.get("stats"));
        assertEquals(590.0, stats.get("hp_base"));
        assertEquals(104.0, stats.get("hp_lvl"));
        assertEquals(3.0, stats.get("dam_lvl"));
        assertEquals(0.625, stats.get("as_ratio"));
        assertEquals(-0.1, stats.get("attack_delay_offset"));
        assertEquals(List.of("Burst"), ahri.get("role"));
        assertEquals(List.of("Orb of Deception"), ahri.get("skill_q"));
        assertEquals(List.of("Essence Theft", "Orb of Deception", "Fox-Fire", "Charm", "Spirit Rush"),
                ahri.get("skills"));

        Map<?, ?> wukong = assertInstanceOf(Map.class, root.get("Wukong"));
        assertEquals("MonkeyKing", wukong.get("apiname"));
        assertEquals(List.of("Crushing Blow"), wukong.get("skill_q"));

        Map<?, ?> aatrox = assertInstanceOf(Map.class, root.get("Aatrox"));
        assertEquals(List.of("The Darkin Blade", "The Darkin Blade 2", "The Darkin Blade 3"), aatrox.get("skill_q"));

        // echter Ausdruck und auskommentierter Block im Modul
        Map<?, ?> kledStats = assertInstanceOf(Map.class,
                assertInstanceOf(Map.class, root.get("Kled & Skaarl")).get("stats"));
        assertEquals(84 + 1000.0 / 17, (Double) kledStats.get("hp_lvl"), 1e-9);
        assertTrue(!kledStats.containsKey("aram"));
    }
}
