package com.leaguetool.model;

import java.util.Locale;

/** Ranglisten-Tier (league-v4). Apex-Tiers (Master+) haben keine Divisionen. */
public enum Tier {
    IRON, BRONZE, SILVER, GOLD, PLATINUM, EMERALD, DIAMOND, MASTER, GRANDMASTER, CHALLENGER;

    public boolean isApex() {
        return this == MASTER || this == GRANDMASTER || this == CHALLENGER;
    }

    public String label() {
        String n = name();
        return n.charAt(0) + n.substring(1).toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return label();
    }
}
