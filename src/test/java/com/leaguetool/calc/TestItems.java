package com.leaguetool.calc;

import com.leaguetool.data.ddragon.DDragonParser;
import com.leaguetool.model.Item;
import com.leaguetool.model.ItemGold;
import com.leaguetool.testutil.Fixtures;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Items für die Calc-Tests: aus der Fixture {@code ddragon/item.json} oder synthetisch. */
final class TestItems {

    static final int BOOTS = 1001;
    static final int SORCERERS_SHOES = 3020;
    static final int RABADONS_DEATHCAP = 3089;
    static final int ZHONYAS_HOURGLASS = 3157;
    static final int NEEDLESSLY_LARGE_ROD = 1058;
    static final int SEEKERS_ARMGUARD = 2420;
    static final int HEALTH_POTION = 2003;
    static final int STEALTH_WARD = 3340;

    private static Map<Integer, Item> fixture;

    private TestItems() {
    }

    /** Alle Items der Fixture nach ID. */
    static synchronized Map<Integer, Item> fixture() {
        if (fixture == null) {
            try {
                fixture = new DDragonParser().parseItems(Fixtures.read("ddragon/item.json")).stream()
                        .collect(Collectors.toUnmodifiableMap(Item::id, Function.identity()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return fixture;
    }

    static Item byId(int id) {
        Item item = fixture().get(id);
        if (item == null) {
            throw new IllegalArgumentException("Item nicht in der Fixture: " + id);
        }
        return item;
    }

    /** Synthetisches Summoner's-Rift-Item mit Beschreibung und Data-Dragon-Stats. */
    static Item synthetic(String name, String descriptionHtml, Map<String, Double> stats, int totalGold) {
        return new Item(9000 + Math.abs(name.hashCode() % 1000), name, "", descriptionHtml,
                new ItemGold(totalGold, totalGold, totalGold / 2, true),
                List.of(), stats, List.of(), List.of(), Set.of(Item.SUMMONERS_RIFT),
                true, 1, "", false, "", 0);
    }

    /** Item ohne eigene Werte mit fester ID (für die Tabelle der Passiven). */
    static Item withId(int id) {
        return new Item(id, "Item " + id, "", "", new ItemGold(0, 0, 0, true),
                List.of(), Map.of(), List.of(), List.of(), Set.of(Item.SUMMONERS_RIFT),
                true, 1, "", false, "", 0);
    }

    /** Beschreibung im Data-Dragon-Format mit dem angegebenen {@code <stats>}-Inhalt. */
    static String description(String statsContent) {
        return "<mainText><stats>" + statsContent + "</stats><br><br></mainText>";
    }
}
