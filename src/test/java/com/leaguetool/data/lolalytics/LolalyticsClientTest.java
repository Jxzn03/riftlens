package com.leaguetool.data.lolalytics;

import com.leaguetool.cache.FileCache;
import com.leaguetool.testutil.FakeHttpFetcher;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LolalyticsClientTest {

    private static final Duration INTERVAL = Duration.ofMillis(400);

    @TempDir
    Path tmp;

    private static String buildUrl(String slug) {
        return "https://lolalytics.com/lol/" + slug + "/build/?tier=emerald_plus&patch=30";
    }

    @Test
    void waitingRequestGoesBeforeAPendingBackgroundRefreshWithTheSameSpacing() throws Exception {
        List<Long> starts = new ArrayList<>();
        FakeHttpFetcher fake = new FakeHttpFetcher();
        for (String slug : List.of("ahri", "zed", "lux")) {
            fake.respond(buildUrl(slug), LolalyticsPages.build());
        }
        // Zeitpunkt jedes Abrufs mitschreiben, um den Mindestabstand zu prüfen.
        com.leaguetool.data.http.HttpFetcher timed = (url, headers) -> {
            synchronized (starts) {
                starts.add(System.nanoTime());
            }
            return fake.get(url, headers);
        };
        FileCache cache = new FileCache(tmp);
        LolalyticsClient client = new LolalyticsClient(timed, cache, INTERVAL);

        // Zed liegt veraltet im Cache, Ahri wird gerade abgerufen (belegt den Platz der Drosselung).
        client.build("zed", "emerald_plus", null, LolalyticsPeriod.LAST_30_DAYS);
        Files.setLastModifiedTime(cache.pathFor(FileCache.GLOBAL, "lolalytics/build_v4_zed_emerald_plus_main_30d.json"),
                FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        Thread.sleep(INTERVAL.toMillis() + 50);
        client.build("ahri", "emerald_plus", null, LolalyticsPeriod.LAST_30_DAYS);

        // Der alte Eintrag kommt sofort, seine Erneuerung wartet auf den nächsten Platz.
        client.build("zed", "emerald_plus", null, LolalyticsPeriod.LAST_30_DAYS, true);
        // Die Erneuerung wartet jetzt schon in der Drosselung (der Platz wird erst 400 ms nach Ahri frei).
        Thread.sleep(150);
        // Lux braucht jemand jetzt: bekommt den nächsten Platz vor der Erneuerung.
        client.build("lux", "emerald_plus", null, LolalyticsPeriod.LAST_30_DAYS);

        Instant deadline = Instant.now().plusSeconds(10);
        while (fake.requests().stream().filter(buildUrl("zed")::equals).count() < 2 && Instant.now().isBefore(deadline)) {
            Thread.sleep(20);
        }
        Thread.sleep(100);
        assertEquals(List.of(buildUrl("zed"), buildUrl("ahri"), buildUrl("lux"), buildUrl("zed")), fake.requests());
        synchronized (starts) {
            for (int i = 1; i < starts.size(); i++) {
                long gapMs = (starts.get(i) - starts.get(i - 1)) / 1_000_000;
                assertTrue(gapMs >= INTERVAL.toMillis() - 5, "Abstand " + i + ": " + gapMs + " ms");
            }
        }
    }

    @Test
    void backgroundRefreshNeverBlocksAWaitingRequestForTheSameEntry() throws Exception {
        FakeHttpFetcher fake = new FakeHttpFetcher().respond(buildUrl("zed"), LolalyticsPages.build());
        FileCache cache = new FileCache(tmp);
        LolalyticsClient client = new LolalyticsClient(fake, cache, INTERVAL);
        client.build("zed", "emerald_plus", null, LolalyticsPeriod.LAST_30_DAYS);
        Path file = cache.pathFor(FileCache.GLOBAL, "lolalytics/build_v4_zed_emerald_plus_main_30d.json");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofDays(2))));

        // Erneuerung angestoßen, dann derselbe Eintrag ohne alte Werte: höchstens ein weiterer Abruf.
        client.build("zed", "emerald_plus", null, LolalyticsPeriod.LAST_30_DAYS, true);
        client.build("zed", "emerald_plus", null, LolalyticsPeriod.LAST_30_DAYS);
        Thread.sleep(INTERVAL.toMillis() * 3);
        assertEquals(2, fake.requests().size(), "der Eintrag wird nur einmal erneuert");
        assertTrue(Files.getLastModifiedTime(file).toInstant().isAfter(Instant.now().minusSeconds(60)));
    }
}
