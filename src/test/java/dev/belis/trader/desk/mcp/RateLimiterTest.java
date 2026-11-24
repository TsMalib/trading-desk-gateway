package dev.belis.trader.desk.mcp;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-24T16:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    final MutableClock clock = new MutableClock();
    final RateLimiter limiter = new RateLimiter(30, Duration.ofMinutes(1), clock);

    @Test
    void thirtyPerMinuteThenBlocked() {
        for (int i = 0; i < 30; i++) {
            assertTrue(limiter.tryAcquire("k").isEmpty(), "request " + (i + 1));
        }
        assertEquals(60_000L, limiter.tryAcquire("k").getAsLong());
    }

    @Test
    void windowSlides() {
        for (int i = 0; i < 30; i++) {
            limiter.tryAcquire("k");
            clock.now = clock.now.plusSeconds(1);
        }
        // first hit was 30s ago: 30s until it leaves the window
        assertEquals(30_000L, limiter.tryAcquire("k").getAsLong());
        clock.now = clock.now.plusSeconds(30);
        assertTrue(limiter.tryAcquire("k").isEmpty());
        assertTrue(limiter.tryAcquire("k").isPresent());
    }

    @Test
    void blockedRequestsDoNotCount() {
        for (int i = 0; i < 31; i++) {
            limiter.tryAcquire("k");
        }
        clock.now = clock.now.plusSeconds(60);
        for (int i = 0; i < 30; i++) {
            assertTrue(limiter.tryAcquire("k").isEmpty());
        }
    }

    @Test
    void keysAreIndependent() {
        for (int i = 0; i < 30; i++) {
            limiter.tryAcquire("a");
        }
        assertTrue(limiter.tryAcquire("a").isPresent());
        assertTrue(limiter.tryAcquire("b").isEmpty());
    }
}
