package com.leaguetool.calc;

import java.util.Objects;

/**
 * Immer aktiver Effekt einer Item-Passive.
 *
 * @param itemId   Item-ID
 * @param itemName Name des Items (englisch, für Berichte)
 * @param passive  Name der Passive; gleichnamige Passiven sind einzigartig und zählen nicht mehrfach
 * @param type     betroffener Wert
 * @param value    Höhe in der Einheit von {@code type}
 */
public record ItemPassive(int itemId, String itemName, String passive, StatType type, double value) {

    public ItemPassive {
        Objects.requireNonNull(itemName, "itemName");
        Objects.requireNonNull(passive, "passive");
        Objects.requireNonNull(type, "type");
    }
}
