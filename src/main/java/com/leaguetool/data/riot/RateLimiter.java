package com.leaguetool.data.riot;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Rate-Limiter mit mehreren gleitenden Fenstern (Sliding-Window-Log), z. B. 20 Anfragen/1 s und
 * 100 Anfragen/120 s für Riot-Dev-Keys. Zusätzlich kann der Limiter global bis zu einem Zeitpunkt pausiert
 * werden (Retry-After nach HTTP 429). Threadsicher; Zeit und Schlafen laufen über eine {@link TimeSource},
 * damit Tests ohne echtes Warten auskommen.
 */
public final class RateLimiter {

    /** Zeitquelle (Nanosekunden, monoton) und Schlafen. */
    public interface TimeSource {

        long nanoTime();

        void sleepNanos(long nanos) throws InterruptedException;

        /** Systemzeit ({@link System#nanoTime()}, {@link Thread#sleep}). */
        TimeSource SYSTEM = new TimeSource() {
            @Override
            public long nanoTime() {
                return System.nanoTime();
            }

            @Override
            public void sleepNanos(long nanos) throws InterruptedException {
                if (nanos > 0) {
                    Thread.sleep(nanos / 1_000_000L, (int) (nanos % 1_000_000L));
                }
            }
        };
    }

    /** Ein Fenster: höchstens {@code maxRequests} Anfragen innerhalb von {@code period}. */
    public record Window(int maxRequests, Duration period) {

        public Window {
            if (maxRequests < 1) {
                throw new IllegalArgumentException("maxRequests muss mindestens 1 sein: " + maxRequests);
            }
            if (period == null || period.isNegative() || period.isZero()) {
                throw new IllegalArgumentException("period muss positiv sein: " + period);
            }
        }
    }

    private final TimeSource time;
    private final List<Window> windows;
    private final List<ArrayDeque<Long>> logs = new ArrayList<>();
    private final Object lock = new Object();
    private long pausedUntil;
    private boolean paused;

    public RateLimiter(List<Window> windows) {
        this(TimeSource.SYSTEM, windows);
    }

    public RateLimiter(TimeSource time, List<Window> windows) {
        if (windows == null || windows.isEmpty()) {
            throw new IllegalArgumentException("Mindestens ein Fenster erforderlich");
        }
        this.time = time;
        this.windows = List.copyOf(windows);
        for (int i = 0; i < this.windows.size(); i++) {
            logs.add(new ArrayDeque<>());
        }
    }

    /** Limits eines Riot-Dev-Keys: 20 Anfragen/1 s und 100 Anfragen/120 s. */
    public static RateLimiter riotDevKey() {
        return riotDevKey(TimeSource.SYSTEM);
    }

    /** Wie {@link #riotDevKey()} mit eigener Zeitquelle (Tests). */
    public static RateLimiter riotDevKey(TimeSource time) {
        return new RateLimiter(time, List.of(
                new Window(20, Duration.ofSeconds(1)),
                new Window(100, Duration.ofSeconds(120))));
    }

    public List<Window> windows() {
        return windows;
    }

    public TimeSource timeSource() {
        return time;
    }

    /**
     * Blockiert, bis eine Anfrage in allen Fenstern erlaubt ist und keine Pause aktiv ist, und verbucht sie.
     *
     * @throws InterruptedException bei Unterbrechung (die Anfrage wird dann nicht verbucht)
     */
    public void acquire() throws InterruptedException {
        while (true) {
            long wait;
            synchronized (lock) {
                long now = time.nanoTime();
                wait = waitNanos(now);
                if (wait <= 0) {
                    for (ArrayDeque<Long> log : logs) {
                        log.addLast(now);
                    }
                    return;
                }
            }
            time.sleepNanos(wait);
        }
    }

    /** Wartezeit in Nanosekunden, bis eine Anfrage erlaubt wäre (0 = sofort). Verbucht nichts. */
    public long nanosUntilAvailable() {
        synchronized (lock) {
            return Math.max(0, waitNanos(time.nanoTime()));
        }
    }

    /** Pausiert alle Anfragen bis zum Zeitpunkt {@code nanoTime} (gemessen an der {@link TimeSource}). */
    public void pauseUntil(long nanoTime) {
        synchronized (lock) {
            if (!paused || nanoTime - pausedUntil > 0) {
                pausedUntil = nanoTime;
                paused = true;
            }
        }
    }

    private long waitNanos(long now) {
        long wait = 0;
        if (paused) {
            long remaining = pausedUntil - now;
            if (remaining > 0) {
                wait = remaining;
            } else {
                paused = false;
            }
        }
        for (int i = 0; i < windows.size(); i++) {
            long period = windows.get(i).period().toNanos();
            ArrayDeque<Long> log = logs.get(i);
            while (!log.isEmpty() && now - log.peekFirst() >= period) {
                log.pollFirst();
            }
            if (log.size() >= windows.get(i).maxRequests()) {
                wait = Math.max(wait, log.peekFirst() + period - now);
            }
        }
        return wait;
    }
}
