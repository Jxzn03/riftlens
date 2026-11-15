package com.leaguetool.data.ddragon;

import com.leaguetool.model.Ability;
import com.leaguetool.model.AbilitySlot;
import com.leaguetool.model.Champion;
import com.leaguetool.model.ChampionDetail;
import com.leaguetool.model.Item;
import com.leaguetool.model.RuneTree;
import com.leaguetool.model.SummonerSpell;
import com.leaguetool.testutil.Fixtures;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DDragonParserTest {

    private final DDragonParser parser = new DDragonParser();

    @Test
    void parsesVersionsNewestFirst() throws IOException {
        List<String> versions = parser.parseVersions(Fixtures.read("ddragon/versions.json"));
        assertEquals("16.22.1", versions.get(0));
        assertEquals(5, versions.size());
    }

    @Test
    void parsesChampionSummaries() throws IOException {
        List<Champion> champions = parser.parseChampions(Fixtures.read("ddragon/champion.json"));
        assertEquals(List.of("Aatrox", "Ahri", "Wukong"), champions.stream().map(Champion::name).toList());

        Champion ahri = champions.get(1);
        assertEquals("Ahri", ahri.id());
        assertEquals(103, ahri.key());
        assertEquals("the Nine-Tailed Fox", ahri.title());
        assertEquals("Mana", ahri.resource());
        assertEquals("Ahri.png", ahri.imageFile());
        assertEquals(590, ahri.stats().hp());
        assertEquals(104, ahri.stats().hpPerLevel());
        assertEquals(0.668, ahri.stats().attackSpeed());
        assertEquals(2.2, ahri.stats().attackSpeedPerLevel());
        assertEquals(550, ahri.stats().attackRange());
        assertTrue(ahri.tags().contains("Mage"));

        Champion wukong = champions.get(2);
        assertEquals("MonkeyKing", wukong.id());
        assertEquals(62, wukong.key());
    }

    @Test
    void parsesChampionDetailWithAbilities() throws IOException {
        ChampionDetail detail = parser.parseChampionDetail(Fixtures.read("ddragon/champion-Ahri.json"));
        assertEquals("Ahri", detail.champion().id());
        assertEquals(5, detail.abilities().size());
        assertEquals(List.of(AbilitySlot.P, AbilitySlot.Q, AbilitySlot.W, AbilitySlot.E, AbilitySlot.R),
                detail.abilities().stream().map(Ability::slot).toList());

        Ability passive = detail.ability(AbilitySlot.P).orElseThrow();
        assertEquals("Essence Theft", passive.name());
        assertEquals("Ahri_SoulEater2.png", passive.imageFile());
        assertEquals("passive", passive.imageGroup());
        assertTrue(passive.description().contains("\n"), "<br> wird zu Zeilenumbruch");
        assertFalse(passive.description().contains("<"));

        Ability q = detail.ability(AbilitySlot.Q).orElseThrow();
        assertEquals("AhriQ", q.id());
        assertEquals("Orb of Deception", q.name());
        assertEquals(5, q.maxRank());
        assertEquals(List.of(7.0, 7.0, 7.0, 7.0, 7.0), q.cooldowns());
        assertEquals(List.of(55.0, 65.0, 75.0, 85.0, 95.0), q.costs());
        assertEquals("Mana", q.costType(), "Template {{ abilityresourcename }} wird aufgelöst");
        assertEquals("AhriQ.png", q.imageFile());

        Ability r = detail.ability(AbilitySlot.R).orElseThrow();
        assertEquals(3, r.maxRank());
        assertFalse(detail.lore().isBlank());
        assertFalse(detail.allyTips().isEmpty());
    }

    @Test
    void parsesItems() throws IOException {
        Map<Integer, Item> items = parser.parseItems(Fixtures.read("ddragon/item.json")).stream()
                .collect(Collectors.toMap(Item::id, Function.identity()));
        assertEquals(11, items.size());

        Item rabadon = items.get(3089);
        assertEquals("Rabadon's Deathcap", rabadon.name());
        assertEquals(3500, rabadon.gold().total());
        assertEquals(1100, rabadon.gold().base());
        assertEquals(2450, rabadon.gold().sell());
        assertEquals(130.0, rabadon.stats().get("FlatMagicDamageMod"));
        assertEquals(List.of(1058, 1058), rabadon.from());
        assertTrue(rabadon.maps().contains(Item.SUMMONERS_RIFT));
        assertTrue(rabadon.isPurchasableOnSummonersRift());
        assertTrue(rabadon.isCompleted());

        Item boots = items.get(1001);
        assertTrue(boots.isBoots());
        assertFalse(boots.isCompleted(), "Boots bauen weiter");
        assertTrue(boots.into().contains(3020));

        assertTrue(items.get(2003).isConsumable());
        assertFalse(items.get(2003).isCompleted());
        assertTrue(items.get(3340).isTrinket());
        assertFalse(items.get(3340).isCompleted());

        Item championOnly = items.get(3330);
        assertFalse(championOnly.requiredChampion().isEmpty());
        assertFalse(championOnly.isPurchasableOnSummonersRift());

        assertFalse(items.get(1035).inStore(), "inStore=false/hideFromAll");
        assertFalse(items.get(1500).maps().contains(Item.SUMMONERS_RIFT));
    }

    @Test
    void parsesRuneTrees() throws IOException {
        List<RuneTree> trees = parser.parseRuneTrees(Fixtures.read("ddragon/runesReforged.json"));
        assertEquals(List.of("Precision", "Sorcery"), trees.stream().map(RuneTree::key).toList());
        RuneTree precision = trees.get(0);
        assertEquals(8000, precision.id());
        assertEquals(4, precision.slots().size());
        assertTrue(precision.keystones().stream().anyMatch(r -> r.id() == 8010 && r.name().equals("Conqueror")));
        assertTrue(precision.keystones().stream().allMatch(r -> r.isKeystone() && r.treeId() == 8000));
        assertTrue(precision.slots().get(1).stream().noneMatch(r -> r.isKeystone()));
        assertFalse(precision.keystones().get(0).icon().isBlank());
    }

    @Test
    void parsesSummonerSpells() throws IOException {
        Map<String, SummonerSpell> spells = parser.parseSummonerSpells(Fixtures.read("ddragon/summoner.json"))
                .stream().collect(Collectors.toMap(SummonerSpell::id, Function.identity()));
        SummonerSpell flash = spells.get("SummonerFlash");
        assertEquals(4, flash.key());
        assertEquals("Flash", flash.name());
        assertEquals(300.0, flash.cooldown());
        assertEquals(7, flash.summonerLevel());
        assertTrue(flash.isClassic());
        assertEquals(14, spells.get("SummonerDot").key());
        assertEquals(11, spells.get("SummonerSmite").key());
        assertFalse(spells.get("SummonerSnowball").isClassic(), "Mark/Dash nur ARAM");
    }

    @Test
    void resolvesCostTypes() {
        assertEquals("Mana", DDragonParser.resolveCostType(" {{ abilityresourcename }}", List.of(50.0), "Mana"));
        assertEquals("", DDragonParser.resolveCostType("No Cost", List.of(0.0), "Mana"));
        assertEquals("Health", DDragonParser.resolveCostType("Health", List.of(20.0), "Blood Well"));
        assertEquals("", DDragonParser.resolveCostType("", List.of(0.0, 0.0), "None"));
    }
}
