package com.leaguetool.model;

/** Gold-Werte eines Items: Kombinationskosten, Gesamtkosten, Verkaufswert. */
public record ItemGold(int base, int total, int sell, boolean purchasable) {
}
