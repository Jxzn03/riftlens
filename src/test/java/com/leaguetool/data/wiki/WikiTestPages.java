package com.leaguetool.data.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leaguetool.testutil.Fixtures;

import java.io.IOException;
import java.io.UncheckedIOException;

/** Liest Seiteninhalte aus den Wiki-API-Fixtures. */
final class WikiTestPages {

    static final String AHRI = "wiki/abilities-Ahri.json";
    static final String AATROX = "wiki/abilities-Aatrox.json";

    private WikiTestPages() {
    }

    /** Inhalt der Seite {@code title} (z. B. "Template:Data Ahri/Charm") aus einer API-Antwort. */
    static String page(String fixture, String title) {
        for (JsonNode p : read(fixture).path("query").path("pages")) {
            if (p.path("title").asText().equals(title)) {
                return p.path("revisions").path(0).path("slots").path("main").path("content").asText();
            }
        }
        throw new IllegalArgumentException("Seite nicht in " + fixture + ": " + title);
    }

    /** Lua-Inhalt von {@code Module:ChampionData/data} aus der Fixture. */
    static String championLua() {
        return page("wiki/championdata.json", "Module:ChampionData/data");
    }

    private static JsonNode read(String fixture) {
        try {
            return new ObjectMapper().readTree(Fixtures.read(fixture));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
