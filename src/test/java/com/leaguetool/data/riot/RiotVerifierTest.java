package com.leaguetool.data.riot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leaguetool.data.GameData;
import com.leaguetool.data.ddragon.DDragonParser;
import com.leaguetool.model.Tier;
import com.leaguetool.testutil.Fixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiotVerifierTest {

    private static GameData data;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RiotVerifier verifier = new RiotVerifier();

    @BeforeAll
    static void loadGameData() throws IOException {
        DDragonParser p = new DDragonParser();
        data = new GameData("16.22.1", false,
                p.parseChampions(Fixtures.read("ddragon/champion.json")),
                p.parseItems(Fixtures.read("ddragon/item.json")),
                p.parseRuneTrees(Fixtures.read("ddragon/runesReforged.json")),
                p.parseSummonerSpells(Fixtures.read("ddragon/summoner.json")));
    }

    private static JsonNode json(String fixture) throws IOException {
        return MAPPER.readTree(Fixtures.read(fixture));
    }

    private static LeagueEntry entry(String puuid, String summonerId, int lp) {
        return new LeagueEntry(puuid, summonerId, RiotApi.RANKED_SOLO_QUEUE, Tier.CHALLENGER, "I", lp, 100, 90);
    }

    @Test
    void allChecksPassForKnownIds() throws IOException {
        FakeRiotApi api = new FakeRiotApi();
        api.apex = List.of(entry("p-1", "s-1", 1500));
        api.matchIds.put("p-1", List.of("EUW1_7000000001"));
        api.matches.put("EUW1_7000000001", json("riot/match-ok.json"));

        VerificationReport report = verifier.verify(api, data);
        assertEquals(List.of(RiotVerifier.CHECK_KEY, RiotVerifier.CHECK_MATCH, RiotVerifier.CHECK_PATCH,
                        RiotVerifier.CHECK_CHAMPIONS, RiotVerifier.CHECK_ITEMS, RiotVerifier.CHECK_SPELLS,
                        RiotVerifier.CHECK_RUNES),
                report.checks().stream().map(VerificationReport.Check::name).toList());
        assertTrue(report.allOk(), report.toString());
        assertTrue(report.check(RiotVerifier.CHECK_MATCH).orElseThrow().detail().contains("EUW1_7000000001"));
        assertEquals("Match 16.22 (16.22.712.5432), Daten 16.22 (16.22.1)",
                report.check(RiotVerifier.CHECK_PATCH).orElseThrow().detail());
        assertEquals(List.of("status", "apex", "ids:p-1:420:0:1:null", "match:EUW1_7000000001"), api.calls);
    }

    @Test
    void unknownIdsAndPatchMismatchAreListed() throws IOException {
        List<VerificationReport.Check> checks = verifier.verifyMatch(json("riot/match-unknown-ids.json"), data);
        Map<String, VerificationReport.Check> byName = new HashMap<>();
        checks.forEach(c -> byName.put(c.name(), c));

        assertFalse(byName.get(RiotVerifier.CHECK_PATCH).ok());
        assertEquals("Match 16.21 (16.21.705.1234), Daten 16.22 (16.22.1)", byName.get(RiotVerifier.CHECK_PATCH).detail());
        assertEquals("Unbekannte IDs: 999", byName.get(RiotVerifier.CHECK_CHAMPIONS).detail());
        assertEquals("Unbekannte IDs: 3364, 9999", byName.get(RiotVerifier.CHECK_ITEMS).detail());
        assertEquals("Unbekannte IDs: 99", byName.get(RiotVerifier.CHECK_SPELLS).detail());
        assertEquals("Unbekannte IDs: Bäume 8400; Runen 1234; Shards 5999", byName.get(RiotVerifier.CHECK_RUNES).detail());
        assertTrue(checks.stream().noneMatch(VerificationReport.Check::ok));
    }

    @Test
    void authProblemStopsFurtherChecks() {
        FakeRiotApi api = new FakeRiotApi();
        api.statusError = new RiotApiException(403, "Key ungültig oder abgelaufen (Dev-Keys laufen nach 24 h ab)");
        VerificationReport report = verifier.verify(api, data);
        assertEquals(1, report.checks().size());
        VerificationReport.Check key = report.checks().get(0);
        assertEquals(RiotVerifier.CHECK_KEY, key.name());
        assertFalse(key.ok());
        assertTrue(key.detail().contains("abgelaufen"), key.detail());
        assertFalse(report.allOk());
        assertEquals(List.of("status"), api.calls);
    }

    @Test
    void networkErrorStopsFurtherChecks() {
        FakeRiotApi api = new FakeRiotApi();
        api.statusError = new ConnectException("offline");
        VerificationReport report = verifier.verify(api, data);
        assertEquals(1, report.checks().size());
        assertTrue(report.checks().get(0).detail().startsWith("Riot-API nicht erreichbar"));
    }

    @Test
    void resolvesPuuidViaSummonerIdAndSkipsPlayersWithoutMatches() throws IOException {
        FakeRiotApi api = new FakeRiotApi();
        api.apex = List.of(entry(null, "s-1", 1600), entry(null, null, 1500), entry("p-2", null, 1400));
        api.puuids.put("s-1", "p-1");
        api.matchIds.put("p-1", List.of());
        api.matchIds.put("p-2", List.of("EUW1_7000000001"));
        api.matches.put("EUW1_7000000001", json("riot/match-ok.json"));

        VerificationReport report = verifier.verify(api, data);
        assertTrue(report.allOk(), report.toString());
        assertEquals(List.of("status", "apex", "puuid:s-1", "ids:p-1:420:0:1:null", "ids:p-2:420:0:1:null",
                "match:EUW1_7000000001"), api.calls);
    }

    @Test
    void emptyLeagueOrMatchErrorFailsMatchCheck() {
        FakeRiotApi empty = new FakeRiotApi();
        VerificationReport r1 = verifier.verify(empty, data);
        assertEquals(2, r1.checks().size());
        assertTrue(r1.checks().get(0).ok());
        assertFalse(r1.checks().get(1).ok());
        assertEquals(RiotVerifier.CHECK_MATCH, r1.checks().get(1).name());

        FakeRiotApi failing = new FakeRiotApi();
        failing.apex = List.of(entry("p-1", null, 1000));
        failing.matchIds.put("p-1", List.of("EUW1_X"));
        VerificationReport r2 = verifier.verify(failing, data);
        assertEquals(2, r2.checks().size());
        assertTrue(r2.checks().get(1).detail().contains("nicht gefunden"), r2.checks().get(1).detail());
    }

    @Test
    void errorForOnePlayerTriesTheNext() throws IOException {
        FakeRiotApi api = new FakeRiotApi();
        api.apex = List.of(entry(null, "s-weg", 1600), entry("p-kaputt", null, 1500), entry("p-3", null, 1400),
                entry("p-4", null, 1300));
        api.matchIdErrors.put("p-kaputt", new RiotApiException(503, "Riot-Server-Fehler (HTTP 503)"));
        api.matchIds.put("p-3", List.of("EUW1_7000000001"));
        api.matches.put("EUW1_7000000001", json("riot/match-ok.json"));

        VerificationReport report = verifier.verify(api, data);
        assertTrue(report.allOk(), report.toString());
        assertEquals(List.of("status", "apex", "puuid:s-weg", "ids:p-kaputt:420:0:1:null", "ids:p-3:420:0:1:null",
                "match:EUW1_7000000001"), api.calls);
    }

    @Test
    void allPlayersFailingReportsLastErrorAndAuthProblemAborts() {
        FakeRiotApi api = new FakeRiotApi();
        api.apex = List.of(entry("p-1", null, 3), entry("p-2", null, 2), entry("p-3", null, 1), entry("p-4", null, 0));
        api.matchIdErrors.put("p-1", new RiotApiException(500, "Fehler 1"));
        api.matchIdErrors.put("p-2", new RiotApiException(500, "Fehler 2"));
        api.matchIdErrors.put("p-3", new RiotApiException(500, "Fehler 3"));
        VerificationReport report = verifier.verify(api, data);
        assertEquals(2, report.checks().size());
        assertTrue(report.checks().get(1).detail().contains("Fehler 3"), report.checks().get(1).detail());
        assertFalse(api.calls.contains("ids:p-4:420:0:1:null"));

        FakeRiotApi auth = new FakeRiotApi();
        auth.apex = List.of(entry("p-1", null, 2), entry("p-2", null, 1));
        auth.matchIdErrors.put("p-1", new RiotApiException(403, "Key ungültig oder abgelaufen"));
        auth.matchIds.put("p-2", List.of("EUW1_7000000001"));
        VerificationReport authReport = verifier.verify(auth, data);
        assertEquals(2, authReport.checks().size());
        assertTrue(authReport.checks().get(1).detail().contains("abgelaufen"), authReport.checks().get(1).detail());
        assertEquals(List.of("status", "apex", "ids:p-1:420:0:1:null"), auth.calls);
    }

    @Test
    void matchWithoutParticipants() throws IOException {
        List<VerificationReport.Check> checks = verifier.verifyMatch(
                MAPPER.readTree("{\"metadata\":{\"matchId\":\"EUW1_1\"},\"info\":{\"participants\":[]}}"), data);
        assertEquals(5, checks.size());
        assertEquals("Match enthält keine gameVersion", checks.get(0).detail());
        assertTrue(checks.stream().noneMatch(VerificationReport.Check::ok));
    }

    @Test
    void endToEndWithHttpClient() throws IOException {
        FakeTimeSource time = new FakeTimeSource();
        String euw = "https://euw1.api.riotgames.com";
        String europe = "https://europe.api.riotgames.com";
        ScriptedFetcher http = new ScriptedFetcher()
                .respond(euw + "/lol/status/v4/platform-data", Fixtures.read("riot/platform-status.json"))
                .respond(euw + "/lol/league/v4/challengerleagues/by-queue/RANKED_SOLO_5x5",
                        Fixtures.read("riot/challenger-league-no-puuid.json"))
                .respond(euw + "/lol/summoner/v4/summoners/test-summoner-04", "{\"puuid\":\"test-puuid-c04\"}")
                .respond(europe + "/lol/match/v5/matches/by-puuid/test-puuid-c04/ids?queue=420&start=0&count=1",
                        "[\"EUW1_7000000001\"]")
                .respond(europe + "/lol/match/v5/matches/EUW1_7000000001", Fixtures.read("riot/match-ok.json"));
        RiotApiClient api = new RiotApiClient(http, () -> Optional.of("RGAPI-test"), Platform.EUW1,
                RateLimiter.riotDevKey(time), time);
        VerificationReport report = verifier.verify(api, data);
        assertTrue(report.allOk(), report.toString());
        assertEquals(5, http.requests().size());
        assertTrue(time.sleeps().isEmpty());
    }

    /** Fake-Implementierung mit konfigurierbaren Antworten; protokolliert alle Aufrufe. */
    private static final class FakeRiotApi implements RiotApi {
        IOException statusError;
        List<LeagueEntry> apex = List.of();
        final Map<String, String> puuids = new HashMap<>();
        final Map<String, List<String>> matchIds = new HashMap<>();
        final Map<String, IOException> matchIdErrors = new HashMap<>();
        final Map<String, JsonNode> matches = new HashMap<>();
        final List<String> calls = new ArrayList<>();

        @Override
        public Platform platform() {
            return Platform.EUW1;
        }

        @Override
        public List<LeagueEntry> leagueEntries(String queue, Tier tier, String division, int page) {
            calls.add("entries");
            return List.of();
        }

        @Override
        public List<LeagueEntry> apexLeague(String queue, Tier tier) {
            calls.add("apex");
            assertEquals(RiotApi.RANKED_SOLO_QUEUE, queue);
            assertEquals(Tier.CHALLENGER, tier);
            return apex;
        }

        @Override
        public String puuidBySummonerId(String summonerId) throws IOException {
            calls.add("puuid:" + summonerId);
            String puuid = puuids.get(summonerId);
            if (puuid == null) {
                throw new RiotApiException(404, "Daten nicht gefunden (HTTP 404)");
            }
            return puuid;
        }

        @Override
        public RiotAccount accountByRiotId(String gameName, String tagLine) {
            calls.add("account");
            return new RiotAccount("p", gameName, tagLine);
        }

        @Override
        public List<String> matchIdsByPuuid(String puuid, int queueId, int start, int count, Long startTimeEpochSec)
                throws IOException {
            calls.add("ids:" + puuid + ":" + queueId + ":" + start + ":" + count + ":" + startTimeEpochSec);
            if (matchIdErrors.containsKey(puuid)) {
                throw matchIdErrors.get(puuid);
            }
            return matchIds.getOrDefault(puuid, List.of());
        }

        @Override
        public JsonNode match(String matchId) throws IOException {
            calls.add("match:" + matchId);
            JsonNode m = matches.get(matchId);
            if (m == null) {
                throw new RiotApiException(404, "Daten nicht gefunden (HTTP 404): /lol/match/v5/matches/" + matchId);
            }
            return m;
        }

        @Override
        public JsonNode timeline(String matchId) {
            calls.add("timeline:" + matchId);
            return MAPPER.createObjectNode();
        }

        @Override
        public JsonNode platformStatus() throws IOException {
            calls.add("status");
            if (statusError != null) {
                throw statusError;
            }
            return MAPPER.createObjectNode();
        }
    }
}
