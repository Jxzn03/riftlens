package com.leaguetool.model;

import java.util.List;

/**
 * Fähigkeit eines Champions (Data Dragon).
 *
 * @param id          Data-Dragon-Spell-ID (z. B. {@code AhriQ}); für Passive der Bilddateiname ohne Endung
 * @param description Beschreibung als Klartext
 * @param maxRank     maximaler Rang (Passive: 1)
 * @param cooldowns   Abklingzeiten je Rang in Sekunden (Passive: leer)
 * @param costs       Kosten je Rang (Passive: leer)
 * @param costType    Ressource (z. B. "Mana"), leer wenn unbekannt/keine
 * @param ranges      Reichweite je Rang (Passive: leer)
 * @param imageFile   Bilddatei
 * @param imageGroup  Data-Dragon-Bildgruppe: {@code spell} oder {@code passive}
 */
public record Ability(
        String championId,
        AbilitySlot slot,
        String id,
        String name,
        String description,
        int maxRank,
        List<Double> cooldowns,
        List<Double> costs,
        String costType,
        List<Double> ranges,
        String imageFile,
        String imageGroup) {

    public Ability {
        cooldowns = List.copyOf(cooldowns);
        costs = List.copyOf(costs);
        ranges = List.copyOf(ranges);
    }

    public boolean isPassive() {
        return slot == AbilitySlot.P;
    }
}
