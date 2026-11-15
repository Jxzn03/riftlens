package com.leaguetool.data.lcu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leaguetool.model.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChampSelectTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Ranked-Draft, Pick-Phase: Ahri (eigener Platz 2) gesperrt, Lee Sin zeigt gerade Jarvan, Platz 3 hat Thresh
     * angesagt. Beim Gegner ist Aatrox gesperrt, Platz 6 hovert gerade (für uns unsichtbar). Namen und Kennungen stehen
     * absichtlich darin: Sie dürfen nicht übernommen werden.
     */
    static final String DRAFT = """
            {
              "gameId": 7001, "localPlayerCellId": 2, "isSpectating": false,
              "timer": {"phase": "BAN_PICK"},
              "bans": {"myTeamBans": [], "theirTeamBans": [], "numBans": 4},
              "actions": [
                [{"actorCellId": 0, "championId": 157, "completed": true, "isAllyAction": true, "isInProgress": false, "type": "ban"},
                 {"actorCellId": 5, "championId": 238, "completed": true, "isAllyAction": false, "isInProgress": false, "type": "ban"},
                 {"actorCellId": 6, "championId": 0, "completed": true, "isAllyAction": false, "isInProgress": false, "type": "ban"}],
                [{"actorCellId": 2, "championId": 103, "completed": true, "isAllyAction": true, "isInProgress": false, "type": "pick"}],
                [{"actorCellId": 5, "championId": 266, "completed": true, "isAllyAction": false, "isInProgress": false, "type": "pick"},
                 {"actorCellId": 6, "championId": 64, "completed": false, "isAllyAction": false, "isInProgress": true, "type": "pick"}],
                [{"actorCellId": 1, "championId": 59, "completed": false, "isAllyAction": true, "isInProgress": true, "type": "pick"},
                 {"actorCellId": 3, "championId": 0, "completed": false, "isAllyAction": true, "isInProgress": false, "type": "pick"}],
                [{"actorCellId": 7, "championId": 0, "completed": false, "isAllyAction": false, "isInProgress": false, "type": "pick"}]
              ],
              "myTeam": [
                {"cellId": 1, "championId": 59, "championPickIntent": 0, "assignedPosition": "jungle", "summonerId": 111,
                 "puuid": "puuid-ally-1", "gameName": "Mitspieler", "tagLine": "EUW", "nameVisibilityType": "HIDDEN"},
                {"cellId": 2, "championId": 103, "championPickIntent": 0, "assignedPosition": "middle", "summonerId": 222,
                 "puuid": "puuid-self", "gameName": "Ich", "tagLine": "EUW"},
                {"cellId": 3, "championId": 0, "championPickIntent": 412, "assignedPosition": "utility", "summonerId": 333,
                 "puuid": "puuid-ally-3", "gameName": "Supporter", "tagLine": "EUW"}
              ],
              "theirTeam": [
                {"cellId": 5, "championId": 266, "championPickIntent": 0, "assignedPosition": "top", "summonerId": 555,
                 "puuid": "puuid-enemy-5", "gameName": "Gegner", "obfuscatedPuuid": "x"},
                {"cellId": 6, "championId": 64, "championPickIntent": 64, "assignedPosition": "jungle", "summonerId": 666},
                {"cellId": 7, "championId": 0, "championPickIntent": 0, "assignedPosition": "", "summonerId": 0}
              ]
            }
            """;

    static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void takesLockedPicksBansAndOwnRoles() {
        ChampSelect cs = ChampSelect.parse(json(DRAFT));

        assertEquals(7001, cs.gameId());
        assertEquals("BAN_PICK", cs.phase());
        assertEquals(List.of(157, 238), cs.bans(), "nur abgeschlossene Bans mit Champion");

        assertEquals(List.of(0, 103, 0), cs.ally().stream().map(ChampSelect.Member::champion).toList(),
                "Lee Sin hovert nur, Thresh ist nur angesagt");
        assertEquals(List.of(Role.JUNGLE, Role.MIDDLE, Role.UTILITY), cs.ally().stream().map(ChampSelect.Member::role).toList());
        assertTrue(cs.ally().get(1).local());
        assertFalse(cs.ally().get(0).local());
    }

    @Test
    void allyHoversAndIntentsAreHintsOnly() {
        ChampSelect cs = ChampSelect.parse(json(DRAFT));
        assertEquals(59, cs.ally().get(0).intent(), "Hover während des eigenen Zugs");
        assertTrue(cs.ally().get(0).picking());
        assertEquals(412, cs.ally().get(2).intent(), "Ansage vor dem Zug");
        assertEquals(0, cs.ally().get(1).intent(), "Gesperrte Picks haben keine Ansage mehr");
    }

    @Test
    void enemyHoversAndRolesStayHidden() {
        ChampSelect cs = ChampSelect.parse(json(DRAFT));
        assertEquals(List.of(266, 0, 0), cs.enemy().stream().map(ChampSelect.Member::champion).toList(),
                "Der Hover des Gegners ist im Client nicht sichtbar und wird nicht übernommen");
        cs.enemy().forEach(m -> {
            assertEquals(0, m.intent());
            assertNull(m.role(), "Rollen der Gegner zeigt der Client nicht");
            assertFalse(m.local());
        });
        assertTrue(cs.enemy().get(1).picking(), "Wer gerade wählt, zeigt der Client an");
    }

    @Test
    void modesWithoutPickActionsUseTheAssignedChampion() {
        String aram = """
                {"gameId": 9, "localPlayerCellId": 0, "actions": [], "timer": {"phase": "FINALIZATION"},
                 "bans": {"myTeamBans": [], "theirTeamBans": []},
                 "myTeam": [{"cellId": 0, "championId": 22, "assignedPosition": ""},
                            {"cellId": 1, "championId": 51, "assignedPosition": ""}],
                 "theirTeam": [{"cellId": 5, "championId": 0}]}
                """;
        ChampSelect cs = ChampSelect.parse(json(aram));
        assertEquals(List.of(22, 51), cs.ally().stream().map(ChampSelect.Member::champion).toList());
        assertNull(cs.ally().get(0).role());
        assertEquals(0, cs.enemy().get(0).champion());
    }

    @Test
    void bansFromTheBanListAreMergedWithoutDuplicates() {
        String text = """
                {"gameId": 1, "actions": [[{"actorCellId": 0, "championId": 10, "completed": true, "type": "ban"}]],
                 "bans": {"myTeamBans": [10, 11], "theirTeamBans": [0, 12]}, "myTeam": [], "theirTeam": []}
                """;
        assertEquals(List.of(10, 11, 12), ChampSelect.parse(json(text)).bans());
    }
}
