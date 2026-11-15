package com.leaguetool.data.lcu;

/**
 * Die einzigen Pfade, die RiftLens beim League-Client abfragt, jeweils nur lesend per GET. Weitere Endpunkte (vor allem
 * schreibende, Chat, Lobby- oder Spielerdaten) sind bewusst nicht vorgesehen, siehe {@code docs/client-sync.md}.
 */
public enum LcuEndpoint {
    /** Picks, Bans, Rollen und Ansagen der laufenden Champion-Auswahl. */
    CHAMP_SELECT_SESSION("/lol-champ-select/v1/session"),
    /** Champions, die der eigene Account in dieser Auswahl spielen darf. */
    PICKABLE_CHAMPIONS("/lol-champ-select/v1/pickable-champion-ids");

    private final String path;

    LcuEndpoint(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }
}
