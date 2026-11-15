package com.leaguetool.model;

import java.util.List;
import java.util.Optional;

/** Champion inkl. Fähigkeiten (aus {@code champion/{id}.json}). Fähigkeiten in Reihenfolge P, Q, W, E, R. */
public record ChampionDetail(
        Champion champion,
        String lore,
        List<Ability> abilities,
        List<String> allyTips,
        List<String> enemyTips) {

    public ChampionDetail {
        abilities = List.copyOf(abilities);
        allyTips = List.copyOf(allyTips);
        enemyTips = List.copyOf(enemyTips);
    }

    public Optional<Ability> ability(AbilitySlot slot) {
        return abilities.stream().filter(a -> a.slot() == slot).findFirst();
    }
}
