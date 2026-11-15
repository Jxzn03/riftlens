package com.leaguetool.model;

import java.util.List;
import java.util.stream.Stream;

/** Runenbaum (Precision, Domination ...) mit Reihen; Reihe 0 enthält die Keystones. */
public record RuneTree(int id, String key, String name, String icon, List<List<Rune>> slots) {

    public RuneTree {
        slots = slots.stream().map(List::copyOf).toList();
    }

    public List<Rune> keystones() {
        return slots.isEmpty() ? List.of() : slots.get(0);
    }

    public Stream<Rune> runes() {
        return slots.stream().flatMap(List::stream);
    }
}
