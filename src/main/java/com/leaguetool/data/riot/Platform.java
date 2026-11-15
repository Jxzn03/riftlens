package com.leaguetool.data.riot;

import java.util.Locale;
import java.util.Optional;

/**
 * Riot-Plattform (Server) mit zugehörigem regionalem Routing.
 * Plattform-Endpunkte (league-v4, summoner-v4, status): {@code https://<id>.api.riotgames.com};
 * regionale Endpunkte (match-v5, account-v1): {@code https://<region>.api.riotgames.com}.
 */
public enum Platform {
    BR1("americas", "americas"),
    EUN1("europe", "europe"),
    EUW1("europe", "europe"),
    JP1("asia", "asia"),
    KR("asia", "asia"),
    LA1("americas", "americas"),
    LA2("americas", "americas"),
    ME1("europe", "europe"),
    NA1("americas", "americas"),
    OC1("sea", "americas"),
    PH2("sea", "asia"),
    RU("europe", "europe"),
    SG2("sea", "asia"),
    TH2("sea", "asia"),
    TR1("europe", "europe"),
    TW2("sea", "asia"),
    VN2("sea", "asia");

    private final String matchRegion;
    private final String accountRegion;

    Platform(String matchRegion, String accountRegion) {
        this.matchRegion = matchRegion;
        this.accountRegion = accountRegion;
    }

    /** Host-Präfix, z. B. {@code euw1}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Regionales Routing für match-v5, z. B. {@code europe}. */
    public String matchRegion() {
        return matchRegion;
    }

    /** Regionales Routing für account-v1. */
    public String accountRegion() {
        return accountRegion;
    }

    public String platformBaseUrl() {
        return "https://" + id() + ".api.riotgames.com";
    }

    public String matchBaseUrl() {
        return "https://" + matchRegion + ".api.riotgames.com";
    }

    public String accountBaseUrl() {
        return "https://" + accountRegion + ".api.riotgames.com";
    }

    public static Optional<Platform> fromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(id.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
