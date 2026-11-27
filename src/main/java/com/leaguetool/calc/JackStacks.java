package com.leaguetool.calc;

import com.leaguetool.model.Item;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Stapel der Rune Jack of All Trades: je einer für jede verschiedene Werteart aus Items (Wiki „Jack of All Trades“,
 * Notes). Flach und prozentual zählen bei Lauftempo und Magiedurchdringung getrennt, bei der Regeneration nicht.
 * Von den Item-Effekten zählen nur die, die das Wiki als eigenen Item-Wert führt (Sterak's Gage, Yun Tal).
 */
public final class JackStacks {

    public static final int RUNE_ID = 8316;

    /** Yun Tal Wildarrows: die aufgebaute kritische Trefferchance zählt als Item-Wert. */
    private static final int YUN_TAL = 3032;

    /** Wertearten, die einen Stapel geben; adaptive Kraft und Verlangsamungsresistenz zählen laut Wiki nicht. */
    private static final Set<StatType> ELIGIBLE = EnumSet.of(StatType.HEALTH, StatType.MANA, StatType.ATTACK_DAMAGE,
            StatType.ABILITY_POWER, StatType.ARMOR, StatType.MAGIC_RESIST, StatType.ATTACK_SPEED, StatType.CRIT_CHANCE,
            StatType.CRIT_DAMAGE, StatType.MOVE_SPEED, StatType.MOVE_SPEED_PERCENT, StatType.ABILITY_HASTE,
            StatType.LETHALITY, StatType.ARMOR_PENETRATION, StatType.MAGIC_PENETRATION,
            StatType.MAGIC_PENETRATION_PERCENT, StatType.LIFE_STEAL, StatType.OMNIVAMP, StatType.HEALTH_REGEN,
            StatType.MANA_REGEN, StatType.HEAL_SHIELD_POWER, StatType.TENACITY, StatType.GOLD_PER_10);

    private JackStacks() {
    }

    /** Anzahl der Stapel für die Items eines Builds. */
    public static int count(List<Item> items) {
        Set<StatType> found = EnumSet.noneOf(StatType.class);
        for (Item item : items) {
            ItemStatParser.parse(item).stats().asMap().keySet().forEach(type -> found.add(normalized(type)));
            if (ItemPassives.of(item.id()).stream().anyMatch(p -> p.type() == StatType.AD_PER_BASE_AD)) {
                found.add(StatType.ATTACK_DAMAGE);
            }
            if (item.id() == YUN_TAL) {
                found.add(StatType.CRIT_CHANCE);
            }
        }
        found.retainAll(ELIGIBLE);
        return found.size();
    }

    private static StatType normalized(StatType type) {
        return switch (type) {
            case HEALTH_REGEN_FLAT -> StatType.HEALTH_REGEN;
            case MANA_REGEN_FLAT -> StatType.MANA_REGEN;
            default -> type;
        };
    }
}
