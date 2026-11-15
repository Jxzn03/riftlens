package com.leaguetool.model;

import java.util.Locale;
import java.util.Optional;

/** Position wie in match-v5 {@code teamPosition}. */
public enum Role {
    TOP("Top"), JUNGLE("Jungle"), MIDDLE("Mid"), BOTTOM("ADC"), UTILITY("Support");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Parst {@code teamPosition}; leer/unbekannt ergibt {@link Optional#empty()}. */
    public static Optional<Role> fromTeamPosition(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Role.valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return label;
    }
}
