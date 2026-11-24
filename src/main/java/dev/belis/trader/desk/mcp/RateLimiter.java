package dev.belis.trader.desk.mcp;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Sliding-window rate limiter: at most {@code limit} requests per {@code window} per key. In memory, one JVM.
 * Stale keys are dropped so the map cannot grow without bound from many client addresses.
 */
final class RateLimiter {

    private static final int MAX_KEYS = 10_000;

    private final int limit;
    private final long windowMillis;
    private final Clock clock;
    private final Map<String, Deque<Long>> hits = new HashMap<>();

    RateLimiter(int limit, Duration window, Clock clock) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        this.limit = limit;
        this.windowMillis = window.toMillis();
        this.clock = clock;
    }

    /**
     * Records a request for {@code key} if it is within the limit.
     *
     * @return empty when allowed; otherwise the milliseconds until the next request would be allowed
     */
    synchronized OptionalLong tryAcquire(String key) {
        long now = clock.millis();
        if (hits.size() > MAX_KEYS) {
            hits.values().removeIf(q -> { drop(q, now); return q.isEmpty(); });
        }
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        drop(q, now);
        if (q.size() >= limit) {
            return OptionalLong.of(Math.max(1, q.peekFirst() + windowMillis - now));
        }
        q.addLast(now);
        return OptionalLong.empty();
    }

    synchronized void reset() {
        hits.clear();
    }

    private void drop(Deque<Long> q, long now) {
        while (!q.isEmpty() && q.peekFirst() <= now - windowMillis) {
            q.removeFirst();
        }
    }
}
