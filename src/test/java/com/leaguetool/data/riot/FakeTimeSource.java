package com.leaguetool.data.riot;

import java.util.ArrayList;
import java.util.List;

/** Künstliche Zeit: {@link #sleepNanos(long)} schläft nicht, sondern stellt die Uhr vor. Threadsicher. */
final class FakeTimeSource implements RateLimiter.TimeSource {

    static final long SECOND = 1_000_000_000L;

    private long now;
    private final List<Long> sleeps = new ArrayList<>();

    @Override
    public synchronized long nanoTime() {
        return now;
    }

    @Override
    public synchronized void sleepNanos(long nanos) {
        sleeps.add(nanos);
        now += Math.max(0, nanos);
    }

    synchronized void advance(long nanos) {
        now += nanos;
    }

    /** Alle angeforderten Schlafdauern in Reihenfolge. */
    synchronized List<Long> sleeps() {
        return List.copyOf(sleeps);
    }

    synchronized long totalSlept() {
        return sleeps.stream().mapToLong(Long::longValue).sum();
    }
}
