package com.leaguetool.data.riot;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.leaguetool.data.riot.FakeTimeSource.SECOND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    private final FakeTimeSource time = new FakeTimeSource();
    private final RateLimiter limiter = RateLimiter.riotDevKey(time);

    @Test
    void devKeyLimits() {
        assertEquals(List.of(new RateLimiter.Window(20, Duration.ofSeconds(1)),
                new RateLimiter.Window(100, Duration.ofSeconds(120))), limiter.windows());
    }

    @Test
    void twentyFirstRequestWithinOneSecondWaits() throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            limiter.acquire();
        }
        assertTrue(time.sleeps().isEmpty());
        assertEquals(SECOND, limiter.nanosUntilAvailable());

        limiter.acquire();
        assertEquals(List.of(SECOND), time.sleeps());
        assertEquals(SECOND, time.nanoTime());
    }

    @Test
    void slidingWindowOnlyWaitsForTheOldestRequest() throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            limiter.acquire();
            time.advance(40_000_000L); // 40 ms
        }
        // 0.8 s vergangen, ältester Eintrag bei 0 → noch 0.2 s
        limiter.acquire();
        assertEquals(List.of(200_000_000L), time.sleeps());
    }

    @Test
    void hundredFirstRequestWithin120SecondsWaits() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            limiter.acquire();
            time.advance(100_000_000L); // 10 Anfragen/s: das 1-s-Fenster greift nicht
        }
        assertTrue(time.sleeps().isEmpty());
        assertEquals(10 * SECOND, time.nanoTime());

        limiter.acquire();
        assertEquals(List.of(110 * SECOND), time.sleeps());
        assertEquals(120 * SECOND, time.nanoTime());
    }

    @Test
    void pauseUntilBlocksAllRequests() throws InterruptedException {
        limiter.pauseUntil(time.nanoTime() + 5 * SECOND);
        limiter.pauseUntil(time.nanoTime() + 2 * SECOND); // kürzere Pause verkürzt nicht
        assertEquals(5 * SECOND, limiter.nanosUntilAvailable());
        limiter.acquire();
        assertEquals(List.of(5 * SECOND), time.sleeps());

        limiter.pauseUntil(time.nanoTime() - SECOND); // Vergangenheit: kein Warten
        limiter.acquire();
        assertEquals(1, time.sleeps().size());
    }

    @Test
    void customWindowsAndValidation() throws InterruptedException {
        RateLimiter single = new RateLimiter(time, List.of(new RateLimiter.Window(2, Duration.ofSeconds(10))));
        single.acquire();
        single.acquire();
        single.acquire();
        assertEquals(List.of(10 * SECOND), time.sleeps());

        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(time, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter.Window(0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter.Window(1, Duration.ZERO));
    }

    @Test
    void interruptedSleepPropagates() {
        RateLimiter.TimeSource interrupting = new RateLimiter.TimeSource() {
            @Override
            public long nanoTime() {
                return 0;
            }

            @Override
            public void sleepNanos(long nanos) throws InterruptedException {
                throw new InterruptedException("Test");
            }
        };
        RateLimiter l = new RateLimiter(interrupting, List.of(new RateLimiter.Window(1, Duration.ofSeconds(1))));
        assertThrows(InterruptedException.class, () -> {
            l.acquire();
            l.acquire();
        });
    }

    @Test
    void concurrentAcquireRespectsLimits() throws InterruptedException {
        RateLimiter l = new RateLimiter(time, List.of(new RateLimiter.Window(20, Duration.ofSeconds(1))));
        AtomicInteger acquired = new AtomicInteger();
        List<Throwable> errors = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            Thread thread = new Thread(() -> {
                try {
                    for (int i = 0; i < 15; i++) {
                        l.acquire();
                        acquired.incrementAndGet();
                    }
                } catch (Throwable e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(60, acquired.get());
        // 60 Anfragen bei 20/s brauchen mindestens 2 s (künstliche) Zeit
        assertTrue(time.nanoTime() >= 2 * SECOND, "Zeit: " + time.nanoTime());
    }
}
