package com.leaguetool.stats;

import com.leaguetool.model.Item;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link ItemClassifier} auf Basis der Data-Dragon-Items ({@code item.json}) eines Patches.
 *
 * <p>Annahmen:
 * <ul>
 *   <li><b>Boots</b>: Tag {@code "Boots"}. <b>Fertige Boots</b> zusätzlich {@code depth ≥ 2}; die Basis-Boots
 *       (1001) haben keine {@code depth} (Data Dragon lässt das Feld weg, der Parser setzt dann 1). Auch
 *       weitere Aufwertungsstufen (depth 3) gelten als fertige Boots.</li>
 *   <li><b>Legendär</b>: keine Boots, kein Verbrauchsgegenstand, kein Trinket, Gesamtpreis ≥
 *       {@value #LEGENDARY_MIN_GOLD} Gold und {@code into} ist leer oder enthält nur Items, die im
 *       Summoner's-Rift-Shop nicht kaufbar sind (z. B. Ornn-Aufwertungen). Unbekannte {@code into}-IDs zählen
 *       als nicht kaufbar, weil Data Dragon alle kaufbaren Items enthält. Die Preisgrenze trennt fertige Items
 *       von teuren Komponenten wie Needlessly Large Rod (1200) oder Seeker's Armguard (1600); sie ist ein
 *       Erfahrungswert und muss nach größeren Item-Reworks ggf. angepasst werden.</li>
 *   <li><b>Startitem</b>: kein Trinket, Preis &gt; 0 und entweder (Preis ≤ {@value #STARTER_MAX_GOLD} und
 *       {@code depth ≤ 1}) oder Tag {@code "Lane"}/{@code "Jungle"}. Mit 500 Startgold sind nur solche Items
 *       zu Spielbeginn kaufbar; Lane/Jungle markiert Doran's-Items, Tränke und Jungle-Begleiter.</li>
 * </ul>
 * Unbekannte IDs ergeben überall {@code false}. Die Klasse ist unveränderlich und threadsicher.
 */
public final class GameItemClassifier implements ItemClassifier {

    /** Mindestpreis eines legendären Items. */
    public static final int LEGENDARY_MIN_GOLD = 2000;
    /** Höchstpreis eines Startitems ohne Lane/Jungle-Tag (Startgold). */
    public static final int STARTER_MAX_GOLD = 500;

    private final Map<Integer, Item> items;

    public GameItemClassifier(Collection<Item> items) {
        Map<Integer, Item> byId = new HashMap<>();
        for (Item item : items) {
            byId.put(item.id(), item);
        }
        this.items = Map.copyOf(byId);
    }

    @Override
    public boolean isBoots(int itemId) {
        Item item = items.get(itemId);
        return item != null && item.isBoots();
    }

    @Override
    public boolean isFinalBoots(int itemId) {
        Item item = items.get(itemId);
        return item != null && item.isBoots() && item.depth() >= 2;
    }

    @Override
    public boolean isLegendary(int itemId) {
        Item item = items.get(itemId);
        if (item == null || item.isBoots() || item.isConsumable() || item.isTrinket()
                || item.gold().total() < LEGENDARY_MIN_GOLD) {
            return false;
        }
        for (int into : item.into()) {
            Item next = items.get(into);
            if (next != null && next.isPurchasableOnSummonersRift()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isStarter(int itemId) {
        Item item = items.get(itemId);
        if (item == null || item.isTrinket() || item.gold().total() <= 0) {
            return false;
        }
        boolean cheapBase = item.gold().total() <= STARTER_MAX_GOLD && item.depth() <= 1;
        boolean laneOrJungle = item.tags().contains("Lane") || item.tags().contains("Jungle");
        return cheapBase || laneOrJungle;
    }
}
