package com.leaguetool.stats;

/**
 * Einordnung von Item-IDs für die Build-Auswertung. Unbekannte IDs ergeben überall {@code false}.
 */
public interface ItemClassifier {

    /** Schuhe jeder Stufe (auch Basis-Boots 1001). */
    boolean isBoots(int itemId);

    /** Fertige Schuhe (aufgewertete Stufe, nicht die Basis-Boots 1001). */
    boolean isFinalBoots(int itemId);

    /** Legendäres (fertiges, teures) Item ohne Schuhe, Verbrauchsgegenstände und Trinkets. */
    boolean isLegendary(int itemId);

    /** Typisches Startitem (Doran's, Tränke, Jungle-Begleiter, günstige Basis-Items). */
    boolean isStarter(int itemId);
}
