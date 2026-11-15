package com.leaguetool.model;

import java.util.List;

/**
 * Champion-Übersicht aus {@code champion.json}.
 *
 * @param id        Data-Dragon-ID (z. B. {@code MonkeyKing}), stabil über Sprachen
 * @param key       numerische Champion-ID (wie in Match-Daten, z. B. 62)
 * @param resource  Ressource ({@code partype}, z. B. "Mana")
 * @param imageFile Bilddatei, z. B. {@code Ahri.png}
 */
public record Champion(
        String id,
        int key,
        String name,
        String title,
        String blurb,
        List<String> tags,
        String resource,
        ChampionInfo info,
        BaseStats stats,
        String imageFile) {

    public Champion {
        tags = List.copyOf(tags);
    }
}
