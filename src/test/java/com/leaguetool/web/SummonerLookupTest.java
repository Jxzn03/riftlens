package com.leaguetool.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leaguetool.cache.FileCache;
import com.leaguetool.data.http.HttpResult;
import com.leaguetool.data.riot.Platform;
import com.leaguetool.data.riot.RateLimiter;
import com.leaguetool.data.riot.RiotApiClient;
import com.leaguetool.testutil.FakeHttpFetcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummonerLookupTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EUROPE = "https://europe.api.riotgames.com";
    private static final String MATCHES = EUROPE + "/lol/match/v5/matches/";
    private static final String IDS = MATCHES + "by-puuid/p1/ids";
    private static final String PLATFORM = "https://euw1.api.riotgames.com/lol/";

    @TempDir
    Path home;

    private final FakeHttpFetcher http = new FakeHttpFetcher();
    // Fortschritt kommt aus mehreren Download-Threads gleichzeitig.
    private final List<int[]> progress = Collections.synchronizedList(new ArrayList<>());

    private SummonerLookup lookup() {
        // Ohne Drosselung: das echte Rate-Limit testet RiotApiClientTest.
        RateLimiter unlimited = new RateLimiter(List.of(new RateLimiter.Window(100_000, Duration.ofSeconds(1))));
        RiotApiClient api = new RiotApiClient(http, () -> Optional.of("RGAPI-test"), Platform.EUW1, unlimited);
        return new SummonerLookup(api, cache(), "16.22.1", (done, total, cached) -> progress.add(new int[] {done, total, cached}));
    }

    private FileCache cache() {
        return new FileCache(home);
    }

    private void respondProfile() {
        http.respond(EUROPE + "/riot/account/v1/accounts/by-riot-id/Ich/EUW", "{\"puuid\":\"p1\",\"gameName\":\"Ich\",\"tagLine\":\"EUW\"}")
                .respond(PLATFORM + "summoner/v4/summoners/by-puuid/p1", "{\"summonerLevel\":30,\"profileIconId\":1}")
                .respond(PLATFORM + "league/v4/entries/by-puuid/p1", "[]")
                .respond(PLATFORM + "champion-mastery/v4/champion-masteries/by-puuid/p1", "[]");
    }

    private void respondIds(int start, int count, String... ids) throws Exception {
        http.respond(IDS + "?start=" + start + "&count=" + count, JSON.writeValueAsString(ids));
    }

    /** MatchDto mit p1 und {@code teammates} in Team 100, vier Gegnern in Team 200. */
    private static String match(String id, boolean win, int seconds, String... teammates) throws Exception {
        ObjectNode info = JSON.createObjectNode().put("queueId", 420).put("gameMode", "CLASSIC").put("gameDuration", seconds)
                .put("gameCreation", 1_700_000_000_000L + id.hashCode()).put("gameEndTimestamp", 1_700_000_000_000L);
        ArrayNode participants = info.putArray("participants");
        participants.addObject().put("puuid", "p1").put("teamId", 100).put("championId", 103).put("championName", "Ahri")
                .put("win", win).put("kills", 5).put("deaths", 1).put("assists", 3);
        for (String mate : teammates) {
            participants.addObject().put("puuid", mate).put("teamId", 100)
                    .put("riotIdGameName", Character.toUpperCase(mate.charAt(0)) + mate.substring(1)).put("riotIdTagline", "EUW");
        }
        for (int i = 0; i < 4; i++) {
            participants.addObject().put("puuid", "foe-" + i).put("teamId", 200).put("riotIdGameName", "Foe").put("riotIdTagline", "X");
        }
        ObjectNode root = JSON.createObjectNode();
        root.set("info", info);
        return JSON.writeValueAsString(root);
    }

    private long requests(String suffix) {
        return http.requests().stream().filter(u -> u.endsWith(suffix)).count();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> result, String key) {
        return (List<Map<String, Object>>) result.get(key);
    }

    @Test
    void matchIdsArePagedInStepsOfHundred() throws Exception {
        respondProfile();
        String[] first = IntStream.range(0, 100).mapToObj(i -> "EUW1_" + i).toArray(String[]::new);
        respondIds(0, 100, first);
        respondIds(100, 50, "EUW1_100");
        http.respond(MATCHES + "EUW1_100", match("EUW1_100", true, 1800));

        Map<String, Object> result = lookup().lookup("Ich#EUW", 150);

        assertEquals(1, list(result, "matches").size(), "unbekannte Matches (404) fallen weg");
        assertEquals(1, requests("?start=100&count=50"));
        assertEquals(2, http.requests().stream().filter(u -> u.startsWith(IDS)).count(), "kürzere Seite beendet das Blättern");
        assertEquals(101, progress.getLast()[1]);

        int before = http.requests().size();
        lookup().lookup("Ich#EUW", 150);
        assertEquals(before + 6, http.requests().size(), "zweiter Abruf: 4 Profil-Aufrufe und 2 Seiten, keine Matches");
    }

    @Test
    void remakesAreReplacedByOlderMatchesUpToTheRequestedCount() throws Exception {
        respondProfile();
        respondIds(0, 3, "EUW1_1", "EUW1_2", "EUW1_3");
        respondIds(3, 1, "EUW1_4");
        http.respond(MATCHES + "EUW1_1", match("EUW1_1", true, 1800))
                .respond(MATCHES + "EUW1_2", match("EUW1_2", false, 120))
                .respond(MATCHES + "EUW1_3", match("EUW1_3", true, 1800))
                .respond(MATCHES + "EUW1_4", match("EUW1_4", false, 1700));

        List<Map<String, Object>> rows = list(lookup().lookup("Ich#EUW", 3), "matches");

        assertEquals(4, rows.size(), "Remake bleibt in der Liste, ein älteres Spiel kommt dazu");
        assertEquals(3, rows.stream().filter(r -> !(Boolean) r.get("remake")).count());
        assertEquals(1, requests("?start=3&count=1"));
    }

    @Test
    void thousandMatchesNeedTenPagesAndFitIntoTheHistory() throws Exception {
        respondProfile();
        for (int page = 0; page < 10; page++) {
            int base = page * 100;
            respondIds(base, 100, IntStream.range(base, base + 100).mapToObj(i -> "EUW1_" + i).toArray(String[]::new));
        }
        lookup().lookup("Ich#EUW", SummonerLookup.MAX_MATCHES);

        assertEquals(1000, SummonerLookup.MAX_MATCHES);
        assertEquals(10, http.requests().stream().filter(u -> u.startsWith(IDS)).count());
        assertEquals(1000, progress.getLast()[1]);
        assertTrue(SummonerHistory.MAX_STORED > SummonerLookup.MAX_MATCHES,
                "sonst fielen bei der größten Abfrage gespeicherte Matches weg und würden neu geladen");
    }

    @Test
    void secondLookupOnlyLoadsNewMatchesFromCompressedHistory() throws Exception {
        respondProfile();
        respondIds(0, 3, "EUW1_A", "EUW1_B", "EUW1_C");
        for (String id : List.of("EUW1_A", "EUW1_B", "EUW1_C", "EUW1_D")) {
            http.respond(MATCHES + id, match(id, true, 1800));
        }
        lookup().lookup("Ich#EUW", 3);
        Path file = cache().file(FileCache.GLOBAL, SummonerHistory.cacheKey("p1")).orElseThrow();
        assertEquals(0x1f, Files.readAllBytes(file)[0] & 0xff, "gzip-komprimiert");

        respondIds(0, 3, "EUW1_D", "EUW1_A", "EUW1_B");
        Map<String, Object> second = lookup().lookup("Ich#EUW", 3);

        assertEquals(List.of("EUW1_D", "EUW1_A", "EUW1_B"), list(second, "matches").stream().map(m -> m.get("id")).toList());
        assertEquals(1, requests("/EUW1_A"));
        assertEquals(1, requests("/EUW1_D"));
        int[] last = progress.getLast();
        assertEquals(List.of(1, 1, 2), List.of(last[0], last[1], last[2]), "1 neues Match, 2 aus dem Cache");
        assertFalse(cache().contains(FileCache.GLOBAL, SummonerHistory.LEGACY_PREFIX + "EUW1_A.json"),
                "der alte Cache je Match wird nicht mehr geschrieben");
    }

    @Test
    void frequentTeammatesAreListedWithReferencesPerMatch() throws Exception {
        respondProfile();
        respondIds(0, 5, "M1", "M2", "M3", "M4", "M5");
        http.respond(MATCHES + "M1", match("M1", true, 1800, "duo", "pair"))
                .respond(MATCHES + "M2", match("M2", true, 1800, "duo", "pair"))
                .respond(MATCHES + "M3", match("M3", false, 1800, "duo"))
                .respond(MATCHES + "M4", match("M4", false, 120, "duo", "pair"))
                .respond(MATCHES + "M5", match("M5", false, 1800, "solo"));

        Map<String, Object> result = lookup().lookup("Ich#EUW", 5);

        List<Map<String, Object>> mates = list(result, "mates");
        assertEquals(1, mates.size(), "pair hat ohne das Remake nur 2 Spiele, Gegner zählen nie");
        assertEquals("Duo", mates.getFirst().get("name"));
        assertEquals("EUW", mates.getFirst().get("tag"));
        assertFalse(mates.getFirst().containsKey("puuid"), "PUUIDs gehen nicht an die Oberfläche");
        assertEquals(SummonerLookup.MIN_SHARED_GAMES, result.get("minSharedGames"));
        List<Object> refs = list(result, "matches").stream().map(m -> m.get("mates")).collect(Collectors.toList());
        assertEquals(List.of(List.of(0), List.of(0), List.of(0), List.of(0), List.of()), refs);
    }

    @Test
    void legacyMatchesAreAdoptedAndMissingNamesResolved() throws Exception {
        respondProfile();
        respondIds(0, 3, "L1", "L2", "L3");
        for (String id : List.of("L1", "L2", "L3")) {
            // Alter Cache: alle zehn Teilnehmer ohne teamId und Riot-ID, Team 100 zuerst.
            ObjectNode info = JSON.createObjectNode().put("queueId", 420).put("gameDuration", 1800)
                    .put("gameEndTimestamp", 1L).put("gameCreation", 1L);
            ArrayNode participants = info.putArray("participants");
            for (int i = 0; i < 10; i++) {
                String puuid = i == 2 ? "p1" : i == 4 ? "old-duo" : id + "-" + i;
                participants.addObject().put("puuid", puuid).put("win", true);
            }
            cache().writeText(FileCache.GLOBAL, SummonerHistory.LEGACY_PREFIX + id + ".json", JSON.writeValueAsString(info));
        }
        http.respond(EUROPE + "/riot/account/v1/accounts/by-puuid/old-duo", "{\"puuid\":\"old-duo\",\"gameName\":\"Alt\",\"tagLine\":\"DE\"}");

        Map<String, Object> result = lookup().lookup("Ich#EUW", 3);

        assertEquals(3, list(result, "matches").size());
        assertEquals(1, requests("/L1"), "erst Riot fragen: der alte Cache kennt keine Positionen und Gegner");
        assertFalse(list(result, "matches").getFirst().containsKey("opponent"), "Ersatz aus dem alten Cache ohne Details");
        assertEquals("Alt", list(result, "mates").getFirst().get("name"));
        lookup().lookup("Ich#EUW", 3);
        assertEquals(1, requests("/L1"), "Riot kennt das Match nicht mehr: kein zweiter Versuch");
        assertEquals(1, requests("/by-puuid/old-duo"), "nachgetragener Name wird gespeichert");
    }

    /** Volles 5-gegen-5-Match: p1 Ahri (Mid, Team 100) gegen Talon (Mid, Team 200), dazu je vier weitere Spieler. */
    private static String fullMatch(boolean win) throws Exception {
        ObjectNode info = JSON.createObjectNode().put("queueId", 420).put("gameMode", "CLASSIC").put("gameDuration", 1800)
                .put("gameCreation", 1_700_000_000_000L).put("gameEndTimestamp", 1_700_001_800_000L);
        ArrayNode participants = info.putArray("participants");
        ObjectNode own = participants.addObject().put("puuid", "p1").put("teamId", 100).put("teamPosition", "MIDDLE")
                .put("championId", 103).put("championName", "Ahri").put("win", win).put("kills", 6).put("deaths", 2)
                .put("assists", 4).put("totalDamageDealtToChampions", 30000).put("visionScore", 25).put("goldEarned", 12000)
                .put("doubleKills", 2).put("visionWardsBoughtInGame", 3);
        own.putObject("challenges").put("killParticipation", 0.5).put("teamDamagePercentage", 0.31)
                .put("laningPhaseGoldExpAdvantage", 1).put("soloKills", 2);
        String[] positions = {"TOP", "JUNGLE", "BOTTOM", "UTILITY"};
        for (int i = 0; i < 4; i++) {
            participants.addObject().put("puuid", "ally-" + i).put("teamId", 100).put("teamPosition", positions[i])
                    .put("championId", 10 + i).put("kills", 3);
        }
        participants.addObject().put("puuid", "foe-mid").put("teamId", 200).put("teamPosition", "MIDDLE")
                .put("championId", 91).put("championName", "Talon").put("kills", 4);
        for (int i = 0; i < 4; i++) {
            participants.addObject().put("puuid", "foe-" + i).put("teamId", 200).put("teamPosition", positions[i])
                    .put("championId", 20 + i);
        }
        ObjectNode root = JSON.createObjectNode();
        root.set("info", info);
        return JSON.writeValueAsString(root);
    }

    @Test
    void matchRowsCarryLaneOpponentAndOpggStyleDetails() throws Exception {
        respondProfile();
        respondIds(0, 1, "F1");
        http.respond(MATCHES + "F1", fullMatch(false));

        Map<String, Object> row = list(lookup().lookup("Ich#EUW", 1), "matches").getFirst();

        assertEquals(Map.of("key", 91, "name", "Talon"), row.get("opponent"));
        assertEquals("blue", row.get("side"));
        assertEquals(0.5, row.get("kp"));
        assertEquals(0.31, row.get("damageShare"));
        assertEquals(30000L, row.get("damage"));
        assertEquals(true, row.get("laneLead"));
        assertEquals(List.of(2, 0, 0, 0), row.get("multi"));
        assertEquals(3, row.get("controlWards"));
        assertEquals(2, row.get("soloKills"));
        assertEquals(1_700_000_000_000L, row.get("startedAt"));
    }

    @Test
    void staleMatchesFromTheOldFormatAreReloadedOnce() throws Exception {
        respondProfile();
        respondIds(0, 1, "F1");
        http.respond(MATCHES + "F1", fullMatch(true));
        // Historie im alten Format: Match ohne Schema, Lane-Gegner und Details.
        SummonerHistory old = new SummonerHistory("p1");
        old.add("F1", JSON.readTree(fullMatch(true)).path("info"), 1);
        cache().writeBytes(FileCache.GLOBAL, SummonerHistory.cacheKey("p1"), old.changedGzip());

        Map<String, Object> first = lookup().lookup("Ich#EUW", 1);
        lookup().lookup("Ich#EUW", 1);

        assertEquals(1, requests("/F1"), "einmal neu geladen, danach aus dem Cache");
        assertTrue(list(first, "matches").getFirst().containsKey("opponent"));
    }

    @Test
    void allMasteriesKeepGradesAndProgress() throws Exception {
        var rows = SummonerLookup.mastery(JSON.readTree("[{\"championId\":412,\"championLevel\":20,\"championPoints\":236876,"
                + "\"lastPlayTime\":1,\"championPointsSinceLastLevel\":51276,\"championPointsUntilNextLevel\":-40276,"
                + "\"milestoneGrades\":[\"A-\",\"S\"],\"championSeasonMilestone\":2,"
                + "\"nextSeasonMilestone\":{\"requireGradeCounts\":{\"A-\":1},\"rewardMarks\":1}}]"));
        Map<String, Object> row = rows.getFirst();
        assertEquals(412, row.get("championKey"));
        assertEquals(List.of("A-", "S"), row.get("grades"));
        assertEquals(-40276L, row.get("untilNext"));
        assertEquals(2, row.get("seasonMilestone"));
        assertEquals(Map.of("A-", 1), row.get("required"));
    }

    @Test
    void loadedMatchesSurviveAnExpiredKey() throws Exception {
        respondProfile();
        respondIds(0, 2, "OK1", "BAD");
        http.respond(MATCHES + "OK1", match("OK1", true, 1800))
                .respond(MATCHES + "BAD", new HttpResult(403, new byte[0], Map.of()));

        ApiException error = assertThrows(ApiException.class, () -> lookup().lookup("Ich#EUW", 2));
        assertEquals(403, error.status());

        Path file = cache().file(FileCache.GLOBAL, SummonerHistory.cacheKey("p1")).orElseThrow();
        assertTrue(SummonerHistory.read("p1", Files.readAllBytes(file)).contains("OK1"));
    }


    /** Gespeicherte Historie für {@code puuid} aus Matches im Format von {@link #match}. */
    private void storeHistory(String puuid, Map<String, String> matches) throws Exception {
        SummonerHistory history = new SummonerHistory(puuid);
        for (Map.Entry<String, String> e : matches.entrySet()) {
            assertTrue(history.add(e.getKey(), JSON.readTree(e.getValue().replace("\"p1\"", "\"" + puuid + "\"")).path("info")));
        }
        cache().writeBytes(FileCache.GLOBAL, SummonerHistory.cacheKey(puuid), history.changedGzip());
    }

    @Test
    @SuppressWarnings("unchecked")
    void combinedViewMergesAllOwnAccountsFromTheCache() throws Exception {
        storeHistory("p1", Map.of("EUW1_A1", match("EUW1_A1", true, 1800, "duo"), "EUW1_A2", match("EUW1_A2", false, 1800, "duo"),
                "EUW1_S", match("EUW1_S", true, 1800, "duo", "p2")));
        // Zweitkonto: ein eigenes Spiel mit dem Duo und dasselbe Spiel wie oben aus seiner Sicht (mit p1 im Team).
        storeHistory("p2", Map.of("EUW1_B1", match("EUW1_B1", true, 1800, "duo"), "EUW1_S", match("EUW1_S", true, 1800, "own1")
                .replace("\"own1\"", "\"p1\"").replace("\"Own1\"", "\"Ich\"")));

        Map<String, Object> result = SummonerLookup.combinedMatches(cache(), List.of("p1", "p2"));

        List<Map<String, Object>> rows = list(result, "matches");
        assertEquals(5, rows.size(), "gemeinsames Spiel zählt für beide Konten");
        assertEquals(5, rows.stream().map(r -> r.get("id")).distinct().count(), "eindeutige Zeilen-IDs");
        assertEquals(List.of(0, 1), rows.stream().map(r -> r.get("account")).distinct().sorted().toList());
        assertTrue(rows.stream().map(r -> ((Number) r.get("startedAt")).longValue()).toList()
                .equals(rows.stream().map(r -> ((Number) r.get("startedAt")).longValue()).sorted(Collections.reverseOrder()).toList()));
        List<Map<String, Object>> mates = list(result, "mates");
        assertEquals(List.of("Duo"), mates.stream().map(m -> m.get("name")).toList(), "eigene Konten sind keine Mitspieler");
        assertEquals(4, rows.stream().filter(r -> ((List<Integer>) r.get("mates")).contains(0)).count());
    }

    @Test
    void combinedMasterySumsPointsAndKeepsTheHighestLevel() {
        Map<String, Object> main = Dto.obj("championKey", 157, "level", 12, "points", 150_000L, "lastPlayed", 10L, "untilNext", -5L);
        Map<String, Object> smurf = Dto.obj("championKey", 157, "level", 4, "points", 20_000L, "lastPlayed", 99L, "untilNext", 1_000L);
        Map<String, Object> other = Dto.obj("championKey", 1, "level", 5, "points", 30_000L, "lastPlayed", 5L, "untilNext", 0L);

        List<Map<String, Object>> merged = SummonerLookup.combinedMastery(List.of(List.of(main, other), List.of(smurf)));

        assertEquals(List.of(157, 1), merged.stream().map(m -> m.get("championKey")).toList());
        assertEquals(170_000L, merged.getFirst().get("points"));
        assertEquals(12, merged.getFirst().get("level"));
        assertEquals(-5L, merged.getFirst().get("untilNext"), "Fortschritt vom Konto mit der höchsten Stufe");
        assertEquals(99L, merged.getFirst().get("lastPlayed"));
        assertEquals(2, merged.getFirst().get("accounts"));
    }
    @Test
    void durationIsNormalizedToSeconds() {
        ObjectNode info = new ObjectMapper().createObjectNode();
        assertEquals(-1, SummonerLookup.durationSeconds(info));
        info.put("gameDuration", 1_500_000);
        assertEquals(1500, SummonerLookup.durationSeconds(info));
        info.put("gameEndTimestamp", 123L);
        info.put("gameDuration", 1500);
        assertEquals(1500, SummonerLookup.durationSeconds(info));
    }

    @Test
    void arenaIsRecognisedByGameModeForAnyQueueNumber() {
        assertEquals("Arena", SummonerLookup.modeLabel(1750, "CHERRY"));
        assertEquals("Arena", SummonerLookup.modeLabel(1700, "CHERRY"));
    }

    @Test
    void rankedQueuesBeyondSoloAndFlexAreLabelled() {
        assertEquals("Solo/Duo", SummonerLookup.queueLabel("RANKED_SOLO_5x5"));
        assertEquals("Flex", SummonerLookup.queueLabel("RANKED_FLEX_SR"));
        assertEquals("Teamranked 5v5", SummonerLookup.queueLabel("RANKED_TEAM_5x5"));
        assertEquals("Flex tt", SummonerLookup.queueLabel("RANKED_FLEX_TT"));
    }

    @Test
    void queue710IsShownAsTeamRanked() {
        assertEquals("5v5 Teamranked", SummonerLookup.modeLabel(710, "CLASSIC"));
    }

    @Test
    void practiceToolQueueIsExcluded() {
        assertEquals(true, SummonerLookup.isExcluded(3140));
        assertEquals(false, SummonerLookup.isExcluded(420));
    }

    @Test
    void customGameQueuesAreMergedToCustomGames() {
        assertEquals("Custom Games", SummonerLookup.modeLabel(0, "CLASSIC"));
        assertEquals("Custom Games", SummonerLookup.modeLabel(3100, "CLASSIC"));
        assertEquals("Custom Games", SummonerLookup.modeLabel(3130, "CLASSIC"));
    }

    @Test
    void undocumentedQueuesKeepModeAndNumber() {
        assertEquals("Summoner's Rift (Queue 9999)", SummonerLookup.modeLabel(9999, "CLASSIC"));
        assertEquals("Queue 9999", SummonerLookup.modeLabel(9999, ""));
    }
}
