package dev.belis.trader.desk.support;

import dev.belis.trader.desk.config.DeskClock;
import io.quarkus.test.junit.QuarkusMock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/** A movable {@link DeskClock} for tests, installed with QuarkusMock. */
public class FixedClock extends DeskClock {

    /** Tuesday 24 Nov 2026, 11:00 New York: inside the entry window. */
    public static final Instant DEFAULT = Instant.parse("2026-11-24T16:00:00Z");

    private volatile Instant now;

    public FixedClock(Instant now) {
        this.now = now;
    }

    public static FixedClock install() {
        FixedClock c = new FixedClock(DEFAULT);
        QuarkusMock.installMockForType(c, DeskClock.class);
        return c;
    }

    public void set(Instant now) {
        this.now = now;
    }

    @Override
    public Clock clock() {
        return Clock.fixed(now, ZoneOffset.UTC);
    }
}
