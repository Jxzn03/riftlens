package com.leaguetool.data.wiki;

import com.leaguetool.model.AbilitySlot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikiAbilityParserTest {

    private static WikiAbility ahri(String ability) {
        return WikiAbilityParser.parse(WikiTestPages.page(WikiTestPages.AHRI, "Template:Data Ahri/" + ability));
    }

    private static WikiAbility aatrox(String ability) {
        return WikiAbilityParser.parse(WikiTestPages.page(WikiTestPages.AATROX, "Template:Data Aatrox/" + ability));
    }

    @Test
    void ahriQ() {
        WikiAbility q = ahri("Orb of Deception");
        assertEquals("Ahri", q.champion());
        assertEquals(AbilitySlot.Q, q.slot());
        assertEquals("Orb of Deception", q.name());
        assertEquals("7", q.cooldownText());
        assertEquals(List.of(7.0, 7.0, 7.0, 7.0, 7.0), q.cooldownValues());
        assertEquals("55 – 95", q.costText());
        assertEquals("mana", q.costType());
        assertEquals("900", q.rangeText());
        assertEquals("0.25", q.castTime());
        assertEquals("Magic True", q.damageType());
        assertEquals("Direction", q.targeting());
        assertEquals("Enemies", q.affects());
        assertEquals("Special", q.spellshield());
        assertEquals("True", q.projectile());
        assertTrue(q.description().startsWith("Active: Ahri sends her orb"), q.description());
        assertTrue(q.description().endsWith("\n\nEnemies can be hit only once per pass."), q.description());

        assertEquals(2, q.levelings().size());
        WikiLeveling perPass = q.levelings().get(0);
        assertEquals("Damage Per Pass", perPass.label());
        assertEquals(List.of(35.0, 60.0, 85.0, 110.0, 135.0), perPass.valuesPerRank());
        assertEquals(new WikiRatio(List.of(50.0, 50.0, 50.0, 50.0, 50.0), "AP"), perPass.ratios().get(0));
    }

    @Test
    void ahriE() {
        WikiAbility e = ahri("Charm");
        assertEquals(AbilitySlot.E, e.slot());
        assertEquals(List.of(12.0, 12.0, 12.0, 12.0, 12.0), e.cooldownValues());
        assertEquals("60", e.costText());
        WikiLeveling disable = e.levelings().stream()
                .filter(l -> l.label().equals("Disable Duration")).findFirst().orElseThrow();
        assertEquals(List.of(1.2, 1.35, 1.5, 1.65, 1.8), disable.valuesPerRank());
        assertEquals("1000", e.rangeText());
    }

    @Test
    void ahriPassiveWAndUltimate() {
        WikiAbility p = ahri("Essence Theft");
        assertEquals(AbilitySlot.P, p.slot());
        assertTrue(p.levelings().isEmpty());
        assertTrue(p.cooldownValues().isEmpty());
        assertEquals("Passive", p.targeting());
        assertTrue(p.description().contains("heal herself for 35 – 95 (based on level) (+ 20% AP)"), p.description());

        WikiAbility w = ahri("Fox-Fire");
        assertEquals(List.of(9.0, 8.0, 7.0, 6.0, 5.0), w.cooldownValues());
        assertEquals("9 – 5", w.cooldownText());
        assertEquals(5, w.levelings().size()); // leveling2 + leveling4

        WikiAbility r = ahri("Spirit Rush");
        assertEquals(AbilitySlot.R, r.slot());
        assertEquals(List.of(140.0, 120.0, 100.0), r.cooldownValues());
        assertEquals(List.of(75.0, 125.0, 175.0), r.levelings().get(0).valuesPerRank());
        assertEquals(List.of(35.0, 35.0, 35.0), r.levelings().get(0).ratios().get(0).percentsPerRank());
        assertEquals("500", r.rangeText()); // aus "target range"
    }

    @Test
    void aatroxDarkinBladeWithVariables() {
        WikiAbility q = aatrox("The Darkin Blade");
        assertEquals("The Darkin Blade", q.name());
        assertEquals(List.of(14.0, 12.0, 10.0, 8.0, 6.0), q.cooldownValues());
        assertEquals(8, q.levelings().size());
        WikiLeveling first = q.levelings().get(0);
        assertEquals("First Cast Damage", first.label());
        assertEquals(List.of(10.0, 25.0, 40.0, 55.0, 70.0), first.valuesPerRank());
        assertEquals(new WikiRatio(List.of(60.0, 67.5, 75.0, 82.5, 90.0), "AD"), first.ratios().get(0));
        WikiLeveling sweet = q.levelings().get(1);
        assertEquals(List.of(17.5, 43.75, 70.0, 96.25, 122.5), sweet.valuesPerRank());
        assertTrue(q.description().contains("take 75% bonus damage"), q.description());
        assertTrue(q.description().contains("625×180-unit"), q.description());
        assertEquals("", q.costText());
    }

    @Test
    void aatroxUltimateAndStaticPassiveCooldown() {
        WikiAbility r = aatrox("World Ender");
        assertEquals(List.of(120.0, 100.0, 80.0), r.cooldownValues());
        assertEquals(List.of("Bonus Movement Speed", "Bonus Attack Damage", "Increased Healing"),
                r.levelings().stream().map(WikiLeveling::label).toList());
        assertEquals(List.of(60.0, 80.0, 100.0), r.levelings().get(0).valuesPerRank());

        WikiAbility p = aatrox("Deathbringer Stance");
        assertEquals(AbilitySlot.P, p.slot());
        assertEquals("22 – 10 (based on level)", p.cooldownText());
        assertTrue(p.cooldownValues().isEmpty());
        assertTrue(p.description().contains("reduced to 25% against minions"), p.description());
    }

    @Test
    void fallbacksAndErrors() {
        WikiAbility a = WikiAbilityParser.parse("{{X\n|description = Text\n}}", "Ahri", AbilitySlot.W, "Fallback");
        assertEquals("Ahri", a.champion());
        assertEquals(AbilitySlot.W, a.slot());
        assertEquals("Fallback", a.name());
        assertEquals("Text", a.description());
        assertThrows(IllegalArgumentException.class, () -> WikiAbilityParser.parse("{{X\n|skill = Z\n}}"));
        assertEquals(AbilitySlot.P, WikiAbilityParser.slotFromSkill("I").orElseThrow());
        assertTrue(WikiAbilityParser.slotFromSkill("Z").isEmpty());
    }
}
