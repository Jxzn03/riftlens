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
import java.nio.file.Path;
import java.time.Duration;
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

    @BeforeEach
    void setUp() {
        client = new LolalyticsClient(http, new FileCache(tmp), Duration.ZERO);
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
