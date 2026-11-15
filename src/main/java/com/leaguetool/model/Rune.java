package com.leaguetool.model;

/**
 * Rune aus {@code runesReforged.json}.
 *
 * @param icon      relativer Icon-Pfad (z. B. {@code perk-images/Styles/...png})
 * @param shortDesc Kurzbeschreibung (HTML-Markup)
 * @param longDesc  Langbeschreibung (HTML-Markup)
 * @param treeId    ID des Baums (z. B. 8000 Precision)
 * @param slot      Reihe im Baum (0 = Keystone)
 */
public record Rune(int id, String key, String name, String shortDesc, String longDesc, String icon, int treeId,
                   int slot) {

    public boolean isKeystone() {
        return slot == 0;
    }
}
