package dev.belis.trader.desk.config;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * The service's notion of "now". A bean (not a static clock) so tests can replace it with QuarkusMock.
 */
@ApplicationScoped
public class DeskClock {

    /** UTC system clock; override in tests. */
    public Clock clock() {
        return Clock.systemUTC();
    }

    public Instant instant() {
        return clock().instant();
    }

    /** Now in {@link DeskConfig#MARKET_ZONE}. */
    public ZonedDateTime marketNow() {
        return instant().atZone(DeskConfig.MARKET_ZONE);
    }

    /** Today's date in {@link DeskConfig#MARKET_ZONE}. */
    public LocalDate marketToday() {
        return marketNow().toLocalDate();
    }

    /** Midnight of today's market date, as an instant. */
    public Instant marketDayStart() {
        return marketToday().atStartOfDay(DeskConfig.MARKET_ZONE).toInstant();
    }
}
