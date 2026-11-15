package com.leaguetool.data.lcu;

import com.fasterxml.jackson.databind.JsonNode;
import com.leaguetool.model.Role;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Was RiftLens aus der Champion-Auswahl übernimmt. Bewusst nur, was im Client ohnehin sichtbar ist: gesperrte Picks,
 * abgeschlossene Bans, die eigenen zugewiesenen Rollen und die Ansagen der eigenen Mitspieler. Namen, PUUIDs,
 * Summoner-IDs, Chat und Rollen der Gegner werden nicht gelesen.
 *
 * @param gameId Kennung der Auswahl; wechselt sie, beginnt ein neuer Draft
 * @param phase  z. B. {@code PLANNING}, {@code BAN_PICK}, {@code FINALIZATION}
 * @param bans   Champions der abgeschlossenen Bans beider Teams
 */
public record ChampSelect(long gameId, String phase, List<Member> ally, List<Member> enemy, List<Integer> bans) {

    /**
     * @param champion gesperrter Champion oder 0
     * @param intent   angesagter bzw. gerade gezeigter Champion eines eigenen Mitspielers, solange er nicht gesperrt hat;
     *                 bei Gegnern immer 0
     * @param role     zugewiesene Rolle; nur im eigenen Team, sonst {@code null}
     * @param local    der eigene Platz
     * @param picking  dieser Platz wählt gerade
     */
    public record Member(int cellId, int champion, int intent, Role role, boolean local, boolean picking) {
    }

    public ChampSelect {
        ally = List.copyOf(ally);
        enemy = List.copyOf(enemy);
        bans = List.copyOf(bans);
    }

    public static ChampSelect parse(JsonNode session) {
        int localCell = session.path("localPlayerCellId").asInt(-1);
        Map<Integer, Integer> locked = new HashMap<>();
        Map<Integer, Integer> hovering = new HashMap<>();
        Set<Integer> picking = new HashSet<>();
        Set<Integer> cellsWithPickAction = new HashSet<>();
        Set<Integer> bans = new LinkedHashSet<>();
        for (JsonNode group : session.path("actions")) {
            for (JsonNode action : group) {
                String type = action.path("type").asText("");
                int cell = action.path("actorCellId").asInt(-1);
                int champion = action.path("championId").asInt(0);
                boolean completed = action.path("completed").asBoolean(false);
                if ("ban".equals(type) && completed && champion > 0) {
                    bans.add(champion);
                } else if ("pick".equals(type)) {
                    cellsWithPickAction.add(cell);
                    if (completed && champion > 0) {
                        locked.put(cell, champion);
                    } else if (action.path("isInProgress").asBoolean(false)) {
                        picking.add(cell);
                        // Den Hover zeigt der Client nur im eigenen Team; bei Gegnern wird er unten verworfen.
                        if (champion > 0) {
                            hovering.put(cell, champion);
                        }
                    }
                }
            }
        }
        // Manche Modi melden Bans nur hier (z. B. ohne Ban-Aktionen); es sind dieselben sichtbaren Bans.
        for (String field : List.of("myTeamBans", "theirTeamBans")) {
            for (JsonNode ban : session.path("bans").path(field)) {
                if (ban.asInt(0) > 0) {
                    bans.add(ban.asInt());
                }
            }
        }

        List<Member> ally = new ArrayList<>();
        for (JsonNode player : session.path("myTeam")) {
            int cell = player.path("cellId").asInt(-1);
            int champion = champion(player, cell, locked, cellsWithPickAction);
            int intent = 0;
            if (champion == 0) {
                intent = hovering.getOrDefault(cell, player.path("championPickIntent").asInt(0));
            }
            Role role = Role.fromTeamPosition(player.path("assignedPosition").asText("")).orElse(null);
            ally.add(new Member(cell, champion, Math.max(0, intent), role, cell == localCell, picking.contains(cell)));
        }
        List<Member> enemy = new ArrayList<>();
        for (JsonNode player : session.path("theirTeam")) {
            int cell = player.path("cellId").asInt(-1);
            enemy.add(new Member(cell, champion(player, cell, locked, cellsWithPickAction), 0, null, false,
                    picking.contains(cell)));
        }
        return new ChampSelect(session.path("gameId").asLong(0), session.path("timer").path("phase").asText(""),
                ally, enemy, new ArrayList<>(bans));
    }

    /**
     * Gesperrter Champion eines Platzes. Ohne Pick-Aktionen (z. B. ARAM) gilt der Champion des Platzes selbst, denn
     * dort gibt es keinen Hover, nur die feste Zuteilung.
     */
    private static int champion(JsonNode player, int cell, Map<Integer, Integer> locked, Set<Integer> cellsWithPickAction) {
        if (locked.containsKey(cell)) {
            return locked.get(cell);
        }
        if (!cellsWithPickAction.contains(cell)) {
            return Math.max(0, player.path("championId").asInt(0));
        }
        return 0;
    }
}
