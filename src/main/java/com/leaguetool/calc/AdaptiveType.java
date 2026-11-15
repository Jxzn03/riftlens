package com.leaguetool.calc;

/** Adaptiver Typ eines Champions: entscheidet über adaptive Kraft, solange Bonus-AD und AP gleich sind. */
public enum AdaptiveType {
    PHYSICAL,
    MAGIC;

    /**
     * Typ laut Wiki ("Physical" oder "Magic"). Ohne Wiki-Angabe entscheiden die Data-Dragon-Werte.
     *
     * @param wikiType Angabe des Wikis, leer oder {@code null}, wenn sie fehlt
     * @param attack   Data-Dragon-Wert "Angriff"
     * @param magic    Data-Dragon-Wert "Magie"
     */
    public static AdaptiveType of(String wikiType, int attack, int magic) {
        if ("Magic".equalsIgnoreCase(wikiType)) {
            return MAGIC;
        }
        if ("Physical".equalsIgnoreCase(wikiType)) {
            return PHYSICAL;
        }
        return magic > attack ? MAGIC : PHYSICAL;
    }
}
