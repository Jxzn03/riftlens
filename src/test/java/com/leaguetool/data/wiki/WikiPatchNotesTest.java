package com.leaguetool.data.wiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WikiPatchNotesTest {

    private static final String PATCH_PAGE = """
            == New Cosmetics ==
            <gallery>
            Sona Spins It Emote.png|Sona Spins It
            </gallery>

            == League of Legends V26.23 ==
            === Client ===
            ;Champ Select
            * Hostaging vote.

            === Game ===
            ;Team Voice Chat<ref>Quelle</ref>
            * {{sbc|New:}} Team Voice chat.
            ** {{Quote|{{sbc|Communications Agreement}}<br>Voice chat is optional.
            }}

            === Champions ===
            ;{{ci|Annie}}
            * {{ai|Summon: Tibbers|Annie}}
            ** Tibbers aura base damage per tick increased to {{ap|2.5 to 5 3}} from {{ap|2 to 4 3}}.
            ;{{ci|Ahri}} - Gameplay Update
            * {{ai|Charm|Ahri}}
            ** {{sbc|Bug Fix:}} Charm now works.
            * {{ai|Orb of Deception|Ahri}}
            ** Damage increased.
            ** {{sbc|Bug Fix:}} Orb returns properly.
            *** Detail of the fix.

            === Items ===
            ;{{ii|Voltaic Cyclosword}}
            * Firmament damage reduced to {{as|{{rd|8%|6%}} of target's '''current''' health}} from {{as|{{rd|9%|7%}}|hp}}.

            === ARAM ===
            ;Mode-specific balancing
            * {{ci|Kalista}}
            ** Outgoing damage modifier changed to +5% from +10%.

            === ARAM: Mayhem ===
            * Champions

            === Arena ===
            * Something.

            == Hotfixes ==
            === November 18th Hotfix ===
            ;{{ci|Fiora}}
            * {{ai|Riposte|Fiora}}
            ** {{sbc|Bug Fix:}} No longer fails to deal damage.
            ;ARAM
            * Mode change.

            {{References}}

            == See Also ==
            {{Release history}}
            """;

    private static final String HISTORY = """
            {{LoL navigation}}

            ==Current version==
            ;[[V26.06]]
            * {{ai|Orb of Deception|Ahri}}
            ** Base damage per hit reduced to {{ap|35 to 135}} from {{ap|40 to 140}}.

            ;[[V25.24#December 17th Hotfix|V25.24]] - December 17th Hotfix
            * {{ai|Charm|Ahri}}
            ** Duration increased.

            ;[[V25.24]]
            * {{csl|Ahri|Risen Legend}} / {{csl|Ahri|Immortalized Legend}}
            ** Updated skin border.

            ;[[V25.08]]
            * Stats
            ** Armor growth reduced to {{fd|4.2}} from {{fd|4.7}}.
            * {{csl|Ahri|Arcade}}
            ** {{sbc|Bug Fix:}} Tails overlap.
            * ARAM: Damage dealt reduced.

            == Second rework ==
            ;[[V14.9]]
            * {{ai|Fox-Fire|Ahri}}
            *** Subsequent flame base damage reduced.

            ==See also==
            ;[[V1.0]]
            * Release.
            """;

    @Test
    void patchTitleFollowsTheGamePatch() {
        assertEquals("V26.23", WikiPatchNotes.wikiPatchTitle("16.23.1"));
        assertEquals("V26.06", WikiPatchNotes.wikiPatchTitle("16.6.1"));
        assertEquals("V25.24", WikiPatchNotes.wikiPatchTitle("15.24"));
        assertThrows(IllegalArgumentException.class, () -> WikiPatchNotes.wikiPatchTitle("lolpatch_7.17"));
    }

    @Test
    void recentPatchesComeFromDataDragonVersions() {
        List<String> versions = List.of("16.24.1", "16.23.1", "16.22.2", "16.22.1", "16.21.1", "16.20.1", "16.19.1",
                "16.18.1", "16.17.1");
        assertEquals(List.of("V26.23", "V26.22", "V26.21", "V26.20", "V26.19", "V26.18"),
                WikiPatchNotes.recentPatchTitles(versions, "16.23.1", 5));
        assertEquals(List.of("V26.23", "V26.22"), WikiPatchNotes.recentPatchTitles(List.of("16.23.1", "16.22.1"), "16.23.1", 5));
        assertEquals(List.of("V26.23"), WikiPatchNotes.recentPatchTitles(List.of(), "16.23.1", 5));
    }

    @Test
    void patchPageKeepsOnlySummonersRiftWithHotfixesFirstAndBugfixesLast() {
        List<WikiPatchNotes.Section> sections = WikiPatchNotes.parsePatchPage(PATCH_PAGE);

        assertEquals(List.of("November 18th Hotfix", "Game", "Champions", "Items", "Bugfixes"),
                sections.stream().map(WikiPatchNotes.Section::title).toList());
        assertEquals(List.of("hotfix", "patch", "patch", "patch", "bugfix"),
                sections.stream().map(WikiPatchNotes.Section::kind).toList());
    }

    @Test
    void bugfixesMoveWithTheirContextAndEmptyParentsVanish() {
        List<WikiPatchNotes.Section> sections = WikiPatchNotes.parsePatchPage(PATCH_PAGE);
        WikiPatchNotes.Group ahri = sections.get(2).groups().get(1);
        assertEquals("Ahri - Gameplay Update", ahri.heading());
        assertEquals("Ahri", ahri.ref()); // Name aus {{ci|Ahri}} für Icon und Link
        assertEquals(List.of("1 Orb of Deception", "2 Damage increased."),
                ahri.lines().stream().map(l -> l.depth() + " " + l.text()).toList());

        WikiPatchNotes.Group fixes = sections.get(4).groups().get(0);
        assertEquals("Ahri - Gameplay Update", fixes.heading());
        assertEquals("Ahri", fixes.ref());
        assertEquals("champion", fixes.kind());
        assertEquals(List.of("Charm", "Bug Fix: Charm now works.", "Orb of Deception", "Bug Fix: Orb returns properly.",
                "Detail of the fix."), fixes.lines().stream().map(WikiPatchNotes.Line::text).toList());
        assertEquals(3, fixes.lines().get(4).depth());
    }

    @Test
    void groupsCarryKindAndPlainText() {
        List<WikiPatchNotes.Section> sections = WikiPatchNotes.parsePatchPage(PATCH_PAGE);
        WikiPatchNotes.Group annie = sections.get(2).groups().get(0);
        assertEquals("Annie", annie.heading());
        assertEquals("champion", annie.kind());
        assertEquals(1, annie.lines().get(0).depth());
        assertEquals(List.of(new WikiPatchNotes.Part("Summon: Tibbers", "ability", "Annie|Summon: Tibbers")),
                annie.lines().get(0).parts());
        assertEquals(2, annie.lines().get(1).depth());
        assertEquals("Tibbers aura base damage per tick increased to 2.5 – 5 from 2 – 4.", annie.lines().get(1).text());

        WikiPatchNotes.Group item = sections.get(3).groups().get(0);
        assertEquals("item", item.kind());
        assertEquals("Firmament damage reduced to 8% melee / 6% ranged of target's current health from 9% melee / 7% ranged.",
                item.lines().get(0).text());
    }

    @Test
    void partsCarryWikiColorsLabelsAndReferences() {
        WikiPatchNotes.Line ap = WikiPatchNotes.line(2, "AP ratio increased to {{as|100% AP}} from {{as|80% AP}}.");
        assertEquals(List.of(new WikiPatchNotes.Part("AP ratio increased to ", null, null),
                new WikiPatchNotes.Part("100% AP", "ap", null), new WikiPatchNotes.Part(" from ", null, null),
                new WikiPatchNotes.Part("80% AP", "ap", null), new WikiPatchNotes.Part(".", null, null)), ap.parts());
        assertEquals("AP ratio increased to 100% AP from 80% AP.", ap.text());

        WikiPatchNotes.Line effect = WikiPatchNotes.line(2,
                "{{sbc|New Effect:}} Tibbers health now also scales with {{as|150% '''bonus''' health}}.");
        assertEquals("label", effect.parts().get(0).style());
        assertEquals(new WikiPatchNotes.Part("150% bonus health", "health", null), effect.parts().get(2));

        assertEquals("ad", WikiPatchNotes.line(2, "Ratio {{as|115% '''bonus''' AD}}.").parts().get(1).style());
        assertEquals(new WikiPatchNotes.Part("Voltaic Cyclosword", "item", "Voltaic Cyclosword"),
                WikiPatchNotes.line(1, "{{ii|Voltaic Cyclosword}}").parts().get(0));
    }

    @Test
    void trendFollowsTheChangedValue() {
        assertEquals("buff", WikiPatchNotes.trendOf("AP ratio increased to 100% AP from 80% AP."));
        assertEquals("nerf", WikiPatchNotes.trendOf("Base damage per hit reduced to 35 – 135 from 40 – 140."));
        assertEquals("buff", WikiPatchNotes.trendOf("Cooldown reduced to 9 – 5 seconds from 10 – 6."));
        assertEquals("nerf", WikiPatchNotes.trendOf("Mana cost increased to 60 from 50."));
        assertEquals("buff", WikiPatchNotes.trendOf("New Effect: Tibbers health now also scales with bonus health."));
        assertEquals("nerf", WikiPatchNotes.trendOf("Removed: Bonus movement speed."));
        assertNull(WikiPatchNotes.trendOf("Bug Fix: Healing is now properly granted."));
        assertNull(WikiPatchNotes.trendOf("Summon: Tibbers"));
    }

    @Test
    void steppedLevelValuesUseTheWikiFormula() {
        assertEquals("Base health (1275 at level 6, then + 75 per level starting at level 7).",
                WikiPatchNotes.plain("Base health {{pp|1275; then +75*x for 4|6 to 18"
                        + "|formula=1275 at level 6, then + 75 per level starting at level 7}}."));
        assertEquals("Health 1150 (based on level).", WikiPatchNotes.plain("Health {{pp|1150; then +50*x for 4|6 to 18}}."));
    }

    @Test
    void multiLineTemplatesStayOneLineAndReferencesVanish() {
        WikiPatchNotes.Group voice = WikiPatchNotes.parsePatchPage(PATCH_PAGE).get(1).groups().get(0);
        assertEquals("Team Voice Chat", voice.heading());
        assertNull(voice.kind());
        assertEquals(2, voice.lines().size());
        assertTrue(voice.lines().get(1).text().contains("Voice chat is optional."));
    }

    @Test
    void hotfixDropsModeGroupsButKeepsItsBugfixes() {
        WikiPatchNotes.Section hotfix = WikiPatchNotes.parsePatchPage(PATCH_PAGE).get(0);
        assertEquals(List.of("Fiora"), hotfix.groups().stream().map(WikiPatchNotes.Group::heading).toList());
        assertEquals("Bug Fix: No longer fails to deal damage.", hotfix.groups().get(0).lines().get(1).text());
    }

    @Test
    void championHistorySkipsSkinsAndModesAndStopsAtSeeAlso() {
        List<WikiPatchNotes.Entry> entries = WikiPatchNotes.parseChampionHistory(HISTORY);

        assertEquals(List.of("V26.06", "V25.24", "V25.08", "V14.9"), entries.stream().map(WikiPatchNotes.Entry::patch).toList());
        assertEquals("December 17th Hotfix", entries.get(1).hotfix());
        assertNull(entries.get(0).hotfix());
        assertEquals(List.of(new WikiPatchNotes.Line(1, "Stats"),
                        new WikiPatchNotes.Line(2, "Armor growth reduced to 4.2 from 4.7.")),
                entries.get(2).lines());
        assertEquals("Base damage per hit reduced to 35 – 135 from 40 – 140.", entries.get(0).lines().get(1).text());
        assertEquals(3, entries.get(3).lines().get(1).depth());
    }

    @Test
    void emptyOrMissingPagesGiveNothing() {
        assertTrue(WikiPatchNotes.parsePatchPage("").isEmpty());
        assertTrue(WikiPatchNotes.parseChampionHistory(null).isEmpty());
    }
}
