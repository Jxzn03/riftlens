package com.leaguetool.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leaguetool.data.lcu.ChampSelect;
import com.leaguetool.data.lcu.LcuSync;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientSyncEndpointTest {

    /** Echte Sitzung mit Namen, PUUIDs und Summoner-IDs: Nichts davon darf in der Antwort an das Fenster stehen. */
    @Test
    void responseContainsNoNamesOrAccountIds() throws Exception {
        String session = """
                {"gameId": 5, "localPlayerCellId": 0, "timer": {"phase": "BAN_PICK"}, "bans": {},
                 "actions": [[{"actorCellId": 0, "championId": 103, "completed": true, "type": "pick"}]],
                 "myTeam": [{"cellId": 0, "championId": 103, "assignedPosition": "middle", "summonerId": 424242,
                             "puuid": "puuid-geheim", "gameName": "Spielername", "tagLine": "EUW1"}],
                 "theirTeam": [{"cellId": 5, "championId": 0, "summonerId": 777777, "puuid": "puuid-gegner",
                                "obfuscatedPuuid": "verschleiert", "gameName": "Gegnername"}]}
                """;
        ChampSelect cs = ChampSelect.parse(new ObjectMapper().readTree(session));
        String json = new ObjectMapper().writeValueAsString(
                ClientSyncEndpoint.json(new LcuSync.State(LcuSync.Status.CHAMP_SELECT, "läuft", cs, List.of(103))));

        assertTrue(json.contains("\"champion\":103"), json);
        assertTrue(json.contains("\"role\":\"MIDDLE\""), json);
        for (String secret : List.of("puuid", "Spielername", "Gegnername", "424242", "777777", "verschleiert", "EUW1", "summoner")) {
            assertFalse(json.contains(secret), "Darf nicht enthalten sein: " + secret + " in " + json);
        }
    }
}
