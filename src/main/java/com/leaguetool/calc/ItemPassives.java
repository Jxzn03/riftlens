package com.leaguetool.calc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Immer aktive prozentuale und bedingungslose Passiven von Items.
 *
 * <p>{@link #baseline()} ist der mitgelieferte Stand von Patch 16.22. Beim Laden der Spieldaten ersetzt
 * {@code ItemPassiveUpdater} ihn über {@link #install} durch den Stand des aktuellen Patches. Bedingte Effekte (Stacks,
 * Kampf, fehlendes Leben) gehören nicht hierher.
 */
public final class ItemPassives {

    private static final List<ItemPassive> BASELINE = List.of(
            new ItemPassive(3089, "Rabadon's Deathcap", "Magical Opus", StatType.ABILITY_POWER_AMP, 30),
            new ItemPassive(3083, "Warmog's Armor", "Warmog's Vitality", StatType.ITEM_HEALTH_AMP, 12),
            new ItemPassive(4633, "Riftmaker", "Void Infusion", StatType.AP_PER_BONUS_HEALTH, 2),
            new ItemPassive(2501, "Overlord's Bloodmail", "Tyranny", StatType.AD_PER_BONUS_HEALTH, 2.5),
            new ItemPassive(3003, "Archangel's Staff", "Awe", StatType.AP_PER_BONUS_MANA, 1),
            new ItemPassive(3040, "Seraph's Embrace", "Awe", StatType.AP_PER_BONUS_MANA, 2),
            new ItemPassive(3004, "Manamune", "Awe", StatType.AD_PER_MAX_MANA, 2),
            new ItemPassive(3042, "Muramana", "Awe", StatType.AD_PER_MAX_MANA, 2),
            new ItemPassive(3119, "Winter's Approach", "Awe", StatType.HEALTH_PER_BONUS_MANA, 15),
            new ItemPassive(3121, "Fimbulwinter", "Awe", StatType.HEALTH_PER_BONUS_MANA, 15),
            new ItemPassive(3053, "Sterak's Gage", "The Claws that Catch", StatType.AD_PER_BASE_AD, 50),
            new ItemPassive(2526, "Whispering Circlet", "Harmony", StatType.HSP_PER_BONUS_MANA, 0.5),
            new ItemPassive(2530, "Diadem of Songs", "Harmony", StatType.HSP_PER_BONUS_MANA, 0.5),
            new ItemPassive(6621, "Dawncore", "First Light", StatType.AP_PER_100_BASE_MANA_REGEN, 15),
            new ItemPassive(6621, "Dawncore", "First Light", StatType.HSP_PER_100_BASE_MANA_REGEN, 2),
            new ItemPassive(2517, "Endless Hunger", "Famine", StatType.ABILITY_HASTE, 5),
            new ItemPassive(2517, "Endless Hunger", "Famine", StatType.HASTE_PER_BONUS_AD_MELEE, 13),
            new ItemPassive(2517, "Endless Hunger", "Famine", StatType.HASTE_PER_BONUS_AD_RANGED, 10),
            new ItemPassive(3170, "Swiftmarch", "Noxian Fervor", StatType.ADAPTIVE_PER_MOVE_SPEED, 5),
            new ItemPassive(3161, "Spear of Shojin", "Dragonforce", StatType.BASIC_ABILITY_HASTE, 25),
            new ItemPassive(2512, "Fiendhunter Bolts", "Night Vigil", StatType.ULTIMATE_HASTE, 30),
            new ItemPassive(3073, "Experimental Hexplate", "Hexcharged", StatType.ULTIMATE_HASTE, 30),
            new ItemPassive(3118, "Malignance", "Scorn", StatType.ULTIMATE_HASTE, 20),
            new ItemPassive(3050, "Zeke's Convergence", "Cryocombustion", StatType.ULTIMATE_HASTE, 15),
            new ItemPassive(4005, "Imperial Mandate", "Control", StatType.IMMOBILIZE_ABILITY_HASTE, 20),
            new ItemPassive(3158, "Ionian Boots of Lucidity", "Ionian Insight", StatType.SUMMONER_SPELL_HASTE, 10),
            new ItemPassive(3171, "Crimson Lucidity", "Ionian Insight", StatType.SUMMONER_SPELL_HASTE, 20));

    private static volatile Map<Integer, List<ItemPassive>> current = index(BASELINE);

    private ItemPassives() {
    }

    /** Mitgelieferter Stand (Rückfall ohne gespeicherten oder abrufbaren Patch-Stand). */
    public static List<ItemPassive> baseline() {
        return BASELINE;
    }

    /** Aktuell verwendete Passiven. */
    public static List<ItemPassive> current() {
        List<ItemPassive> all = new ArrayList<>();
        current.values().forEach(all::addAll);
        return all;
    }

    /** Ersetzt die verwendeten Passiven. */
    public static void install(List<ItemPassive> passives) {
        current = index(passives);
    }

    /** Passiven des Items; leer, wenn es keine hat. */
    static List<ItemPassive> of(int itemId) {
        return current.getOrDefault(itemId, List.of());
    }

    private static Map<Integer, List<ItemPassive>> index(List<ItemPassive> passives) {
        return passives.stream().collect(Collectors.groupingBy(ItemPassive::itemId));
    }
}
