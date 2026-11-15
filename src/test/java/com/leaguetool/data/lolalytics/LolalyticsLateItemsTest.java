package com.leaguetool.data.lolalytics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LolalyticsLateItemsTest {

    private static final int CHAMPION_GAMES = 10_000;

    private static LolalyticsBuild.Entry entry(int item, double winRate, int games) {
        return new LolalyticsBuild.Entry(String.valueOf(item), winRate, 0, games);
    }

    private static LolalyticsBuild.ItemSlot slot(int slot, LolalyticsBuild.Entry... entries) {
        return new LolalyticsBuild.ItemSlot(slot, List.of(entries));
    }

    private static List<LolalyticsLateItems.Line> merge(List<LolalyticsBuild.ItemSlot> slots) {
        return LolalyticsLateItems.of(slots, List.of(), 100, 10, CHAMPION_GAMES);
    }

    @Test
    void sumsGamesAndWeightsTheWinRateAcrossAllSlots() {
        LolalyticsLateItems.Line line = merge(List.of(
                slot(3, entry(1, 0.50, 1000)),
                slot(4, entry(1, 0.60, 500)),
                slot(5, entry(1, 0.70, 500)))).get(0);

        assertEquals(2000, line.games());
        assertEquals((500 + 300 + 350) / 2000.0, line.winRate(), 1e-12);
        assertEquals(0.2, line.pickRate(), 1e-12);
        assertEquals(List.of(new LolalyticsLateItems.SlotGames(3, 1000), new LolalyticsLateItems.SlotGames(4, 500),
                new LolalyticsLateItems.SlotGames(5, 500)), line.slots());
    }

    @Test
    void anItemInSeveralSlotsCanOvertakeTheTopOfOneSlot() {
        List<LolalyticsLateItems.Line> lines = merge(List.of(
                slot(3, entry(1, 0.5, 1000), entry(2, 0.5, 800)),
                slot(4, entry(2, 0.5, 400))));

        assertEquals(List.of(2, 1), lines.stream().map(LolalyticsLateItems.Line::item).toList());
    }

    @Test
    void slotsWithoutTheItemAreNotListed() {
        assertEquals(List.of(new LolalyticsLateItems.SlotGames(4, 300)),
                merge(List.of(slot(3), slot(4, entry(1, 0.5, 300)), slot(5))).get(0).slots());
    }

    @Test
    void theMinimumCountsAllSlotsTogether() {
        List<LolalyticsLateItems.Line> lines = merge(List.of(
                slot(3, entry(1, 0.5, 60), entry(2, 0.5, 99)),
                slot(4, entry(1, 0.5, 40))));

        assertEquals(List.of(1), lines.stream().map(LolalyticsLateItems.Line::item).toList());
    }

    @Test
    void excludedItemsAndUnknownKeysAreDropped() {
        LolalyticsBuild.ItemSlot slots = slot(3, entry(1, 0.5, 500), entry(2, 0.5, 400), new LolalyticsBuild.Entry("x", 0.5, 0, 900));

        assertEquals(List.of(2), LolalyticsLateItems.of(List.of(slots), List.of(1), 100, 10, CHAMPION_GAMES).stream()
                .map(LolalyticsLateItems.Line::item).toList());
    }

    @Test
    void theLimitKeepsTheMostFrequentItems() {
        List<LolalyticsLateItems.Line> lines = LolalyticsLateItems.of(List.of(
                slot(3, entry(1, 0.5, 300), entry(2, 0.5, 500), entry(3, 0.5, 400))), List.of(), 100, 2, CHAMPION_GAMES);

        assertEquals(List.of(2, 3), lines.stream().map(LolalyticsLateItems.Line::item).toList());
    }

    @Test
    void noSlotsGiveNoLines() {
        assertTrue(merge(List.of()).isEmpty());
        assertTrue(merge(List.of(slot(3), slot(4), slot(5))).isEmpty());
    }

    @Test
    void noChampionGamesDoNotDivideByZero() {
        assertEquals(0, LolalyticsLateItems.of(List.of(slot(3, entry(1, 0.5, 200))), List.of(), 100, 10, 0).get(0).pickRate());
    }
}
