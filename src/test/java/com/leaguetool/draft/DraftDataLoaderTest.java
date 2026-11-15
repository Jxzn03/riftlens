package com.leaguetool.draft;

import com.leaguetool.cache.FileCache;
import com.leaguetool.data.lolalytics.LolalyticsClient;
import com.leaguetool.data.lolalytics.LolalyticsPages;
import com.leaguetool.data.lolalytics.LolalyticsPeriod;
import com.leaguetool.draft.DraftDataset.Pick;
import com.leaguetool.draft.RolePredictor.Slot;
import com.leaguetool.model.Role;
import com.leaguetool.testutil.FakeHttpFetcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DraftDataLoaderTest {

    private static final String TIER = "diamond_plus";

    @TempDir
    Path tmp;

    private final FakeHttpFetcher http = new FakeHttpFetcher();
    private LolalyticsClient client;
    private FileCache cache;

    @BeforeEach
    void setUp() {
        cache = new FileCache(tmp);
        client = new LolalyticsClient(http, cache, Duration.ZERO);
        client.setRefreshExecutor(Runnable::run);
        double[][] champions = {{1, 53, 80_000}, {2, 51, 60_000}, {3, 52, 70_000}, {4, 50, 50_000}, {5, 54, 40_000}, {6, 49, 45_000}};
        for (Role role : Role.values()) {
            String lane = LolalyticsClient.lane(role);
            String page = LolalyticsPages.laneTierList(lane, 52, champions);
            http.respond("https://lolalytics.com/lol/tierlist/?tier=" + TIER + "&lane=" + lane + "&patch=30", page);
        }
    }

    private static String buildUrl(int champion, String lane) {
        return "https://lolalytics.com/lol/c" + champion + "/build/?tier=" + TIER + "&lane=" + lane + "&patch=30";
    }

    private static String teamUrl(int champion, String lane) {
        return "https://a1.lolalytics.com/mega/?ep=build-team&v=1&patch=30&c=c" + champion + "&lane=" + lane + "&tier=" + TIER
                + "&queue=ranked&region=all";
    }

    private DraftDataLoader.Request request(List<Slot> ally, List<Slot> enemy) {
        return new DraftDataLoader.Request(ally, enemy, TIER, LolalyticsPeriod.LAST_30_DAYS);
    }

    private DraftDataLoader.Loaded load(DraftDataLoader loader, List<Slot> ally, List<Slot> enemy) throws IOException {
        return loader.load(client, request(ally, enemy), key -> "Champ" + key, (done, total) -> { }, () -> false);
    }

    @Test
    void picksLoadTheirPageAndTeamValues() throws IOException {
        http.respond(buildUrl(1, "middle"), LolalyticsPages.build());
        http.respond(teamUrl(1, "middle"), LolalyticsPages.team(2, 55, 9000));

        DraftDataLoader.Loaded loaded = load(new DraftDataLoader(), List.of(new Slot(1, Role.MIDDLE)), List.of());

        Pick pick = new Pick(1, Role.MIDDLE);
        assertTrue(loaded.warnings().isEmpty(), loaded.warnings().toString());
        assertEquals(1630, loaded.dataset().matchup(pick, new Pick(266, Role.TOP)).games(), 1e-9);
        assertEquals(0.55 * 9000, loaded.dataset().synergy(pick, new Pick(2, Role.TOP)).wins(), 1e-6);
        assertNotNull(loaded.builds().get(pick));
        assertEquals(0.52, loaded.dataset().fullAverage(), 1e-9);
        assertEquals("16.22", loaded.patch());
    }

    @Test
    void fifthTeammateNeedsNoFurtherTeamRequest() throws IOException {
        List<Slot> team = new ArrayList<>();
        Role[] roles = Role.values();
        for (int i = 0; i < roles.length; i++) {
            team.add(new Slot(i + 1, roles[i]));
        }
        load(new DraftDataLoader(), team, List.of());
        long teamRequests = http.requests().stream().filter(u -> u.contains("ep=build-team")).count();
        assertEquals(DraftDataLoader.MAX_TEAMMATE_REQUESTS_PER_TEAM, teamRequests);
    }

    @Test
    void failedSourceBecomesWarningAndIsNotRequestedAgainRightAway() throws IOException {
        DraftDataLoader loader = new DraftDataLoader();
        http.respond(buildUrl(1, "middle"), LolalyticsPages.build());
        List<Slot> ally = List.of(new Slot(1, Role.MIDDLE));

        DraftDataLoader.Loaded first = load(loader, ally, List.of());
        assertEquals(1, first.warnings().size(), first.warnings().toString());
        assertTrue(first.warnings().get(0).startsWith("Synergien für Champ1 (Mid)"));
        assertNotNull(first.builds().get(new Pick(1, Role.MIDDLE)), "Matchups kommen trotzdem");
        long teamRequests = http.requests().stream().filter(u -> u.contains("ep=build-team")).count();

        DraftDataLoader.Loaded second = load(loader, ally, List.of());
        assertEquals(1, second.warnings().size());
        assertEquals(teamRequests, http.requests().stream().filter(u -> u.contains("ep=build-team")).count());
    }

    @Test
    void championWithoutGamesOnTheRoleCausesNoRequest() throws IOException {
        DraftDataLoader.Loaded loaded = load(new DraftDataLoader(), List.of(new Slot(99, Role.MIDDLE)), List.of());
        assertEquals(1, loaded.warnings().size());
        assertTrue(loaded.warnings().get(0).contains("zu wenige Spiele"));
        assertTrue(http.requests().stream().noneMatch(u -> u.contains("c99")));
    }

    @Test
    void progressReachesTheTotal() throws IOException {
        http.respond(buildUrl(1, "middle"), LolalyticsPages.build());
        http.respond(teamUrl(1, "middle"), LolalyticsPages.team(2, 55, 9000));
        List<int[]> steps = new ArrayList<>();
        new DraftDataLoader().load(client, request(List.of(new Slot(1, Role.MIDDLE)), List.of()), key -> "Champ",
                (done, total) -> steps.add(new int[] {done, total}), () -> false);
        int[] last = steps.get(steps.size() - 1);
        assertEquals(last[1], last[0]);
        assertEquals(7, last[1], "5 Tierlisten, 1 Seite, 1 Team-Abruf");
    }

    @Test
    void skippedPickDoesNotUseUpATeamRequest() throws IOException {
        List<Slot> team = List.of(new Slot(99, Role.TOP), new Slot(1, Role.JUNGLE), new Slot(2, Role.MIDDLE),
                new Slot(3, Role.BOTTOM), new Slot(4, Role.UTILITY));
        load(new DraftDataLoader(), team, List.of());
        long teamRequests = http.requests().stream().filter(u -> u.contains("ep=build-team")).count();
        assertEquals(4, teamRequests);
        assertTrue(http.requests().contains(teamUrl(4, "support")), "Der letzte Pick bekommt den frei gebliebenen Abruf");
    }

    @Test
    void progressReachesTheTotalWhenPicksAreSkipped() throws IOException {
        List<int[]> steps = new ArrayList<>();
        new DraftDataLoader().load(client, request(List.of(new Slot(99, Role.TOP), new Slot(98, Role.MIDDLE)), List.of()),
                key -> "Champ", (done, total) -> steps.add(new int[] {done, total}), () -> false);
        int[] last = steps.get(steps.size() - 1);
        assertEquals(last[1], last[0]);
    }

    private DraftDataLoader.Loaded loadQuick(DraftDataLoader loader, List<Slot> ally, List<Slot> enemy, List<int[]> steps)
            throws IOException {
        return loader.load(client, new DraftDataLoader.Request(ally, enemy, TIER, LolalyticsPeriod.LAST_30_DAYS, false, true),
                key -> "Champ" + key, (done, total) -> steps.add(new int[] {done, total}), () -> false);
    }

    private void respondPick(int champion, String lane) {
        http.respond(buildUrl(champion, lane), LolalyticsPages.build());
        http.respond(teamUrl(champion, lane), LolalyticsPages.team(2, 55, 9000));
    }

    @Test
    void quickLoadFetchesOnlyWhatRanksTheSuggestions() throws IOException {
        respondPick(1, "middle");
        respondPick(3, "top");
        List<int[]> steps = new ArrayList<>();

        DraftDataLoader.Loaded loaded = loadQuick(new DraftDataLoader(), List.of(new Slot(1, Role.MIDDLE)),
                List.of(new Slot(3, Role.TOP)), steps);

        assertTrue(http.requests().contains(teamUrl(1, "middle")), "Synergien des eigenen Picks mit dem Kandidaten");
        assertTrue(http.requests().contains(buildUrl(3, "top")), "Matchups des Gegners gegen den Kandidaten");
        assertTrue(!http.requests().contains(buildUrl(1, "middle")) && !http.requests().contains(teamUrl(3, "top")));
        assertEquals(2, loaded.deferred());
        assertTrue(loaded.warnings().isEmpty(), loaded.warnings().toString());
        int[] last = steps.get(steps.size() - 1);
        assertEquals(last[1], last[0]);
        assertEquals(7, last[1], "5 Tierlisten, 1 Team-Abruf, 1 Seite");
    }

    @Test
    void fullLoadAfterQuickFetchesOnlyTheRest() throws IOException {
        respondPick(1, "middle");
        respondPick(3, "top");
        DraftDataLoader loader = new DraftDataLoader();
        List<Slot> ally = List.of(new Slot(1, Role.MIDDLE));
        List<Slot> enemy = List.of(new Slot(3, Role.TOP));
        loadQuick(loader, ally, enemy, new ArrayList<>());
        int before = http.requests().size();

        DraftDataLoader.Loaded full = load(loader, ally, enemy);

        assertEquals(List.of(buildUrl(1, "middle"), teamUrl(3, "top")), http.requests().subList(before, http.requests().size()));
        assertEquals(0, full.deferred());
        assertNotNull(full.builds().get(new Pick(1, Role.MIDDLE)));
    }

    @Test
    void quickLoadUsesFreshCacheForDeferredData() throws IOException {
        respondPick(1, "middle");
        respondPick(3, "top");
        DraftDataLoader loader = new DraftDataLoader();
        List<Slot> ally = List.of(new Slot(1, Role.MIDDLE));
        List<Slot> enemy = List.of(new Slot(3, Role.TOP));
        load(loader, ally, enemy);
        int before = http.requests().size();

        DraftDataLoader.Loaded quick = loadQuick(loader, ally, enemy, new ArrayList<>());

        assertEquals(before, http.requests().size());
        assertEquals(0, quick.deferred());
        assertNotNull(quick.builds().get(new Pick(1, Role.MIDDLE)), "Eigene Seite aus dem Cache");
        assertEquals(9000, quick.dataset().synergy(new Pick(3, Role.TOP), new Pick(2, Role.TOP)).games(), 1e-9,
                "Gegner-Synergien aus dem Cache");
    }

    private void age(String key) throws IOException {
        Path file = cache.file(FileCache.GLOBAL, key).orElseThrow();
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(LolalyticsClient.MAX_AGE).minus(Duration.ofHours(1))));
    }

    private void respondPatchLists(double winRate) {
        for (Role role : Role.values()) {
            String lane = LolalyticsClient.lane(role);
            http.respond("https://lolalytics.com/lol/tierlist/?tier=" + TIER + "&lane=" + lane,
                    LolalyticsPages.laneTierList(lane, 50, new double[][] {{1, winRate, 9000}}));
        }
    }

    private static final String TEAM_KEY = "lolalytics/team_v1_c1_" + TIER + "_middle_30d.json";

    @Test
    void outdatedThirtyDayDataIsUsedAtOnceAndRefreshedAfterwards() throws IOException {
        respondPick(1, "middle");
        DraftDataLoader loader = new DraftDataLoader();
        List<Slot> ally = List.of(new Slot(1, Role.MIDDLE));
        Pick pick = new Pick(1, Role.MIDDLE);
        load(loader, ally, List.of());
        age(TEAM_KEY);
        http.respond(teamUrl(1, "middle"), LolalyticsPages.team(2, 60, 5000));
        int before = http.requests().size();

        DraftDataLoader.Loaded stale = load(loader, ally, List.of());

        assertEquals(9000, stale.dataset().synergy(pick, new Pick(2, Role.TOP)).games(), 1e-9, "Alter Eintrag sofort");
        assertEquals(List.of(teamUrl(1, "middle")), http.requests().subList(before, http.requests().size()),
                "Genau eine Erneuerung, sonst nichts");
        DraftDataLoader.Loaded fresh = load(loader, ally, List.of());
        assertEquals(5000, fresh.dataset().synergy(pick, new Pick(2, Role.TOP)).games(), 1e-9, "Danach der neue Stand");
        assertEquals(before + 1, http.requests().size());
    }

    @Test
    void failedRefreshKeepsTheOldEntryAndPauses() throws IOException {
        respondPick(1, "middle");
        DraftDataLoader loader = new DraftDataLoader();
        List<Slot> ally = List.of(new Slot(1, Role.MIDDLE));
        load(loader, ally, List.of());
        age(TEAM_KEY);
        http.setOffline(true);
        int before = http.requests().size();

        DraftDataLoader.Loaded first = load(loader, ally, List.of());
        DraftDataLoader.Loaded second = load(loader, ally, List.of());

        assertEquals(before + 1, http.requests().size(), "Kein zweiter Versuch während der Pause");
        assertTrue(first.warnings().isEmpty() && second.warnings().isEmpty(), second.warnings().toString());
        assertEquals(9000, second.dataset().synergy(new Pick(1, Role.MIDDLE), new Pick(2, Role.TOP)).games(), 1e-9);
    }

    @Test
    void currentPatchTierListIsNeverServedOutdated() throws IOException {
        respondPatchLists(51);
        DraftDataLoader.Request patch = new DraftDataLoader.Request(List.of(), List.of(), TIER, LolalyticsPeriod.CURRENT_PATCH);
        DraftDataLoader loader = new DraftDataLoader();
        loader.load(client, patch, key -> "Champ", (done, total) -> { }, () -> false);
        for (Role role : Role.values()) {
            age("lolalytics/full_v4_" + TIER + "_" + LolalyticsClient.lane(role) + "_patch.json");
            age("lolalytics/full_v4_" + TIER + "_" + LolalyticsClient.lane(role) + "_30d.json");
        }
        respondPatchLists(56);
        int before = http.requests().size();

        DraftDataLoader.Loaded loaded = loader.load(client, patch, key -> "Champ", (done, total) -> { }, () -> false);

        DraftDataset.Stat selected = loaded.dataset().selected(new Pick(1, Role.MIDDLE));
        assertEquals(0.56, selected.wins() / selected.games(), 1e-9, "Patch-Winrate schon in dieser Antwort neu");
        List<String> fetched = http.requests().subList(before, http.requests().size());
        assertEquals(2 * Role.values().length, fetched.size(), "Patch-Listen neu, 30-Tage-Listen im Hintergrund erneuert");
        assertEquals(Role.values().length, fetched.stream().filter(u -> !u.contains("&patch=")).count());
    }

    @Test
    void supersededRequestStopsBeforeFetching() {
        assertThrows(DraftDataLoader.SupersededException.class, () -> new DraftDataLoader().load(client,
                request(List.of(new Slot(1, Role.MIDDLE)), List.of()), key -> "Champ", (done, total) -> { }, () -> true));
        assertEquals(0, http.requests().size());
    }

    @Test
    void requestSupersededMidwayStopsAtTheNextFetch() {
        http.respond(buildUrl(1, "middle"), LolalyticsPages.build());
        BooleanSupplier supersededAfterTierLists = () -> http.requests().size() >= Role.values().length;
        assertThrows(DraftDataLoader.SupersededException.class, () -> new DraftDataLoader().load(client,
                request(List.of(new Slot(1, Role.MIDDLE)), List.of()), key -> "Champ", (done, total) -> { }, supersededAfterTierLists));
        assertEquals(Role.values().length, http.requests().size(), "Keine Seite mehr nach den Tierlisten");
    }
}
