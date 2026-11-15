package com.leaguetool.model;

import java.util.List;

/**
 * Beschwörerzauber aus {@code summoner.json}.
 *
 * @param id    Data-Dragon-ID (z. B. {@code SummonerFlash})
 * @param key   numerische ID wie in Match-Daten ({@code summoner1Id}), z. B. 4
 * @param modes Spielmodi (z. B. {@code CLASSIC}, {@code ARAM})
 */
public record SummonerSpell(
        String id,
        int key,
        String name,
        String description,
        List<Double> cooldowns,
        int summonerLevel,
        List<String> modes,
        List<Double> ranges,
        String imageFile) {

    public SummonerSpell {
        cooldowns = List.copyOf(cooldowns);
        modes = List.copyOf(modes);
        ranges = List.copyOf(ranges);
    }

    /** Abklingzeit (erster Rang), 0 wenn unbekannt. */
    public double cooldown() {
        return cooldowns.isEmpty() ? 0 : cooldowns.get(0);
    }

    /** In normalen Summoner's-Rift-Spielen verfügbar. */
    public boolean isClassic() {
        return modes.contains("CLASSIC");
    }
}
