package com.leaguetool.data;

import com.leaguetool.model.Champion;
import com.leaguetool.model.Item;
import com.leaguetool.model.Rune;
import com.leaguetool.model.RuneTree;
import com.leaguetool.model.SummonerSpell;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Unveränderlicher Snapshot aller statischen Daten eines Patches mit Lookup-Tabellen. */
public final class GameData {

    private final String patch;
    private final boolean offline;
    private final List<Champion> champions;
    private final List<Item> items;
    private final List<RuneTree> runeTrees;
    private final List<SummonerSpell> summonerSpells;

    private final Map<String, Champion> championsById;
    private final Map<Integer, Champion> championsByKey;
    private final Map<Integer, Item> itemsById;
    private final Map<Integer, Rune> runesById;
    private final Map<Integer, RuneTree> treesById;
    private final Map<Integer, SummonerSpell> spellsByKey;

    public GameData(String patch, boolean offline, List<Champion> champions, List<Item> items,
                    List<RuneTree> runeTrees, List<SummonerSpell> summonerSpells) {
        this.patch = patch;
        this.offline = offline;
        this.champions = List.copyOf(champions);
        this.items = List.copyOf(items);
        this.runeTrees = List.copyOf(runeTrees);
        this.summonerSpells = List.copyOf(summonerSpells);

        this.championsById = index(this.champions, Champion::id);
        this.championsByKey = index(this.champions, Champion::key);
        this.itemsById = index(this.items, Item::id);
        this.treesById = index(this.runeTrees, RuneTree::id);
        this.runesById = index(this.runeTrees.stream().flatMap(RuneTree::runes).toList(), Rune::id);
        // Mehrere Modi-Varianten können denselben key haben; die CLASSIC-Variante gewinnt.
        Map<Integer, SummonerSpell> spells = new HashMap<>();
        for (SummonerSpell s : this.summonerSpells) {
            spells.merge(s.key(), s, (a, b) -> a.isClassic() || !b.isClassic() ? a : b);
        }
        this.spellsByKey = Map.copyOf(spells);
    }

    private static <K, V> Map<K, V> index(List<V> values, Function<V, K> key) {
        return values.stream().collect(Collectors.toUnmodifiableMap(key, Function.identity(), (a, b) -> a));
    }

    public String patch() {
        return patch;
    }

    /** {@code true}, wenn die Daten ohne Netz aus dem Cache geladen wurden. */
    public boolean offline() {
        return offline;
    }

    public List<Champion> champions() {
        return champions;
    }

    public List<Item> items() {
        return items;
    }

    public List<RuneTree> runeTrees() {
        return runeTrees;
    }

    public List<SummonerSpell> summonerSpells() {
        return summonerSpells;
    }

    public Optional<Champion> championById(String id) {
        return Optional.ofNullable(championsById.get(id));
    }

    public Optional<Champion> championByKey(int key) {
        return Optional.ofNullable(championsByKey.get(key));
    }

    public Optional<Item> item(int id) {
        return Optional.ofNullable(itemsById.get(id));
    }

    public Optional<Rune> rune(int id) {
        return Optional.ofNullable(runesById.get(id));
    }

    public Optional<RuneTree> runeTree(int id) {
        return Optional.ofNullable(treesById.get(id));
    }

    public Optional<SummonerSpell> summonerSpell(int key) {
        return Optional.ofNullable(spellsByKey.get(key));
    }
}
