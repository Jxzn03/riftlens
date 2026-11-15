package com.leaguetool.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Rune-Shard (nicht in {@code runesReforged.json} enthalten). IDs wie in Match-Daten ({@code perks.statPerks}). */
public record StatShard(int id, String name) {

    public static final List<StatShard> ALL = List.of(
            new StatShard(5008, "Adaptive Force"),
            new StatShard(5005, "Attack Speed"),
            new StatShard(5007, "Ability Haste"),
            new StatShard(5010, "Move Speed"),
            new StatShard(5001, "Health Scaling"),
            new StatShard(5011, "Health"),
            new StatShard(5013, "Tenacity and Slow Resist"),
            new StatShard(5002, "Armor"),
            new StatShard(5003, "Magic Resist"));

    private static final Map<Integer, StatShard> BY_ID =
            ALL.stream().collect(Collectors.toUnmodifiableMap(StatShard::id, Function.identity()));

    public static Optional<StatShard> byId(int id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    public static String nameOf(int id) {
        return byId(id).map(StatShard::name).orElse("Shard " + id);
    }
}
