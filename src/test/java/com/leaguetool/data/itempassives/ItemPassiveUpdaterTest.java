package com.leaguetool.data.itempassives;

import com.leaguetool.calc.ItemPassive;
import com.leaguetool.calc.StatType;
import com.leaguetool.data.itempassives.ItemPassiveUpdater.Merge;
import com.leaguetool.data.itempassives.ItemPassiveUpdater.SourceItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemPassiveUpdaterTest {

    private static final ItemPassive RABADON = new ItemPassive(3089, "Rabadon's Deathcap", "Magical Opus", StatType.ABILITY_POWER_AMP, 30);
    private static final ItemPassive STERAK = new ItemPassive(3053, "Sterak's Gage", "The Claws that Catch", StatType.AD_PER_BASE_AD, 50);

    private static SourceItem item(String name, String passive, String... lines) {
        return new SourceItem(name, Map.of(passive, List.of(lines)));
    }

    private static SourceItem rabadon(String line) {
        return item("Rabadon's Deathcap", "Magical Opus", line);
    }

    private static SourceItem sterak(String line) {
        return item("Sterak's Gage", "The Claws that Catch", line);
    }

    private static final String RABADON_30 = "Increase your ability power by 30%.";
    private static final String STERAK_50 = "Gain bonus attack damage equal to 50% base AD.";

    /** Data Dragon nennt bei Sterak's keine Zahl. */
    private static final Map<Integer, SourceItem> DATA_DRAGON = Map.of(
            3089, rabadon("Increases your total Ability Power by 30%."),
            3053, sterak("Gain bonus Attack Damage."));

    private static double value(Merge merge, int id) {
        return merge.passives().stream().filter(p -> p.itemId() == id).findFirst().orElseThrow().value();
    }

    @Test
    void unchangedPatchReportsNoChanges() {
        Merge merge = ItemPassiveUpdater.merge(List.of(RABADON, STERAK), DATA_DRAGON,
                Map.of(3089, rabadon(RABADON_30), 3053, sterak(STERAK_50)));
        assertEquals(2, merge.passives().size());
        assertTrue(merge.changes().isEmpty());
        assertTrue(merge.notes().isEmpty());
    }

    @Test
    void valueChangeFromWikiIsAppliedAndReported() {
        Merge merge = ItemPassiveUpdater.merge(List.of(RABADON, STERAK), DATA_DRAGON,
                Map.of(3089, rabadon(RABADON_30), 3053, sterak("Gain bonus attack damage equal to 45% base AD.")));
        assertEquals(45, value(merge, 3053));
        assertEquals(List.of("Sterak's Gage: Angriffsschaden aus Basis-AD 50 % → 45 %"), merge.changes());
    }

    @Test
    void dataDragonWinsOverWikiAndTheConflictIsNoted() {
        Merge merge = ItemPassiveUpdater.merge(List.of(RABADON, STERAK), DATA_DRAGON,
                Map.of(3089, rabadon("Increase your ability power by 35%."), 3053, sterak(STERAK_50)));
        assertEquals(30, value(merge, 3089));
        assertTrue(merge.changes().isEmpty());
        assertEquals(1, merge.notes().size());
        assertTrue(merge.notes().get(0).contains("Data Dragon verwendet"));
    }

    @Test
    void newItemWithKnownWordingIsAddedAutomatically() {
        Map<Integer, SourceItem> dd = new java.util.HashMap<>(DATA_DRAGON);
        dd.put(9999, item("New Staff", "Fresh", "Gain 3% of your bonus Health as Ability Power."));
        Merge merge = ItemPassiveUpdater.merge(List.of(RABADON, STERAK), dd,
                Map.of(3089, rabadon(RABADON_30), 3053, sterak(STERAK_50)));
        assertEquals(3, value(merge, 9999));
        assertEquals(List.of("Neu: New Staff – Fähigkeitsstärke aus Bonus-Leben 3 %"), merge.changes());
    }

    @Test
    void itemRemovedFromGameDisappears() {
        Merge merge = ItemPassiveUpdater.merge(List.of(RABADON, STERAK), Map.of(3089, DATA_DRAGON.get(3089)),
                Map.of(3089, rabadon(RABADON_30), 3053, sterak(STERAK_50)));
        assertEquals(List.of(RABADON), merge.passives());
        assertTrue(merge.changes().get(0).startsWith("Item nicht mehr im Spiel"));
    }

    @Test
    void removedPassiveDisappearsWhenItsNameIsGone() {
        Map<Integer, SourceItem> dd = Map.of(3089, DATA_DRAGON.get(3089), 3053, item("Sterak's Gage", "Lifeline", "Gain a Shield."));
        Merge merge = ItemPassiveUpdater.merge(List.of(RABADON, STERAK), dd,
                Map.of(3089, rabadon(RABADON_30), 3053, item("Sterak's Gage", "Lifeline", "Gain a Shield.")));
        assertEquals(List.of(RABADON), merge.passives());
        assertTrue(merge.changes().get(0).startsWith("Entfallen"));
    }

    @Test
    void rewordedPassiveKeepsItsLastValueAndIsFlagged() {
        Merge merge = ItemPassiveUpdater.merge(List.of(RABADON, STERAK), DATA_DRAGON,
                Map.of(3089, rabadon(RABADON_30), 3053, sterak("Your base AD empowers you in a new way.")));
        assertEquals(50, value(merge, 3053));
        assertTrue(merge.changes().isEmpty());
        assertTrue(merge.review().get(0).contains("letzter Wert bleibt aktiv"));
    }

    @Test
    void withoutWikiPreviousValuesStayAndDataDragonStillUpdates() {
        Map<Integer, SourceItem> dd = Map.of(3089, rabadon("Increases your total Ability Power by 40%."), 3053, sterak("Gain bonus Attack Damage."));
        Merge merge = ItemPassiveUpdater.merge(List.of(RABADON, STERAK), dd, null);
        assertEquals(40, value(merge, 3089));
        assertEquals(50, value(merge, 3053));
    }

    @Test
    void wikiWithUnreadableFormatIsIgnored() {
        Merge merge = ItemPassiveUpdater.merge(List.of(RABADON, STERAK, new ItemPassive(4633, "Riftmaker", "Void Infusion", StatType.AP_PER_BONUS_HEALTH, 2)),
                Map.of(3089, DATA_DRAGON.get(3089), 3053, DATA_DRAGON.get(3053), 4633, item("Riftmaker", "Void Infusion", "Gain AP.")),
                Map.of(3089, rabadon("garbled"), 3053, sterak("garbled"), 4633, item("Riftmaker", "Void Infusion", "garbled")));
        assertEquals(3, merge.passives().size());
        assertTrue(merge.changes().isEmpty());
        assertTrue(merge.notes().get(0).contains("Wiki-Format"));
    }

    @Test
    void implausibleValueIsRejectedAndFlagged() {
        Merge merge = ItemPassiveUpdater.merge(List.of(RABADON, STERAK), DATA_DRAGON,
                Map.of(3089, rabadon(RABADON_30), 3053, sterak("Gain bonus attack damage equal to 5000% base AD.")));
        assertEquals(50, value(merge, 3053));
        assertTrue(merge.review().stream().anyMatch(r -> r.contains("unplausibler Wert")));
    }

    @Test
    void unknownPermanentEffectAppearsInReview() {
        Map<Integer, SourceItem> dd = new java.util.HashMap<>(DATA_DRAGON);
        dd.put(9998, item("Odd Orb", "Strange", "Gain ability power equal to 3% maximum mana."));
        Merge merge = ItemPassiveUpdater.merge(List.of(RABADON, STERAK), dd,
                Map.of(3089, rabadon(RABADON_30), 3053, sterak(STERAK_50)));
        assertTrue(merge.review().contains("Odd Orb (Strange): Gain ability power equal to 3% maximum mana."));
    }
}
