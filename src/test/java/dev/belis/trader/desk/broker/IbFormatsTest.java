package dev.belis.trader.desk.broker;

import com.ib.client.Decimal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IbFormatsTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    // Thanksgiving week 2026: Thu 26 closed, Fri 27 closes at 13:00.
    private static final String LIQUID_HOURS =
            "20261124:0930-20261124:1600;20261125:0930-20261125:1600;20261126:CLOSED;"
                    + "20261127:0930-20261127:1300;20261128:CLOSED;20261129:CLOSED";

    @Test
    void regularDay() {
        TradingSession s = IbFormats.sessionOn(LIQUID_HOURS, NY, LocalDate.of(2026, 11, 24)).orElseThrow();
        assertEquals(LocalDate.of(2026, 11, 24), s.date());
        assertEquals(Instant.parse("2026-11-24T14:30:00Z"), s.open());
        assertEquals(Instant.parse("2026-11-24T21:00:00Z"), s.close());
    }

    @Test
    void holidayIsEmpty() {
        assertEquals(Optional.empty(), IbFormats.sessionOn(LIQUID_HOURS, NY, LocalDate.of(2026, 11, 26)));
    }

    @Test
    void weekendAndUnlistedDayAreEmpty() {
        assertEquals(Optional.empty(), IbFormats.sessionOn(LIQUID_HOURS, NY, LocalDate.of(2026, 11, 28)));
        assertEquals(Optional.empty(), IbFormats.sessionOn(LIQUID_HOURS, NY, LocalDate.of(2026, 12, 1)));
        assertEquals(Optional.empty(), IbFormats.sessionOn("", NY, LocalDate.of(2026, 11, 24)));
        assertEquals(Optional.empty(), IbFormats.sessionOn(null, NY, LocalDate.of(2026, 11, 24)));
    }

    @Test
    void halfDayClosesEarly() {
        TradingSession s = IbFormats.sessionOn(LIQUID_HOURS, NY, LocalDate.of(2026, 11, 27)).orElseThrow();
        assertEquals(Instant.parse("2026-11-27T18:00:00Z"), s.close());
    }

    @Test
    void summerTimeOffsetIsApplied() {
        TradingSession s = IbFormats.sessionOn("20260701:0930-20260701:1600", NY, LocalDate.of(2026, 7, 1))
                .orElseThrow();
        assertEquals(Instant.parse("2026-07-01T13:30:00Z"), s.open());
    }

    @Test
    void olderFormatWithSeveralRanges() {
        TradingSession s = IbFormats.sessionOn("20261124:0900-1130,1230-1730;20261125:CLOSED",
                ZoneId.of("Asia/Tokyo"), LocalDate.of(2026, 11, 24)).orElseThrow();
        assertEquals(Instant.parse("2026-11-24T00:00:00Z"), s.open());
        assertEquals(Instant.parse("2026-11-24T08:30:00Z"), s.close());
    }

    @Test
    void zoneAliases() {
        assertEquals(NY, IbFormats.zone("EST"));
        assertEquals(NY, IbFormats.zone(null));
        assertEquals(NY, IbFormats.zone("Not/AZone"));
        assertEquals(ZoneId.of("Europe/London"), IbFormats.zone("Europe/London"));
    }

    @Test
    void usEasternIsDstAware() {
        ZoneId z = IbFormats.zone("US/Eastern");
        assertEquals(-4 * 3600, z.getRules().getOffset(Instant.parse("2026-07-01T12:00:00Z")).getTotalSeconds());
        assertEquals(-5 * 3600, z.getRules().getOffset(Instant.parse("2026-12-01T12:00:00Z")).getTotalSeconds());
    }

    @Test
    void executionTimes() {
        assertEquals(Instant.parse("2026-11-24T15:30:01Z"),
                IbFormats.executionTime("20261124 10:30:01 US/Eastern", ZoneId.of("UTC")));
        assertEquals(Instant.parse("2026-11-24T15:30:01Z"),
                IbFormats.executionTime("20261124  10:30:01", NY));
        assertEquals(Instant.parse("2026-11-24T15:30:01Z"),
                IbFormats.executionTime("20261124-15:30:01", NY));
        assertThrows(IllegalArgumentException.class, () -> IbFormats.executionTime("garbage", NY));
    }

    @Test
    void executionFilterTimeIsUtc() {
        assertEquals("20261124-15:30:01", IbFormats.executionFilterTime(Instant.parse("2026-11-24T15:30:01Z")));
    }

    @Test
    void priceSentinels() {
        assertNull(IbFormats.price(-1));
        assertNull(IbFormats.price(0));
        assertNull(IbFormats.price(Double.MAX_VALUE));
        assertNull(IbFormats.price(Double.NaN));
        assertEquals(new BigDecimal("187.12"), IbFormats.price(187.12));
    }

    @Test
    void amountsKeepSignButDropUnset() {
        assertEquals(new BigDecimal("-12.5"), IbFormats.amount(-12.5));
        assertEquals(new BigDecimal("0.0"), IbFormats.amount(0));
        assertNull(IbFormats.amount(Double.MAX_VALUE));
    }

    @Test
    void quantities() {
        assertEquals(new BigDecimal("100"), IbFormats.quantity(Decimal.parse("100")));
        assertEquals(new BigDecimal("0.5"), IbFormats.quantity(Decimal.parse("0.50")));
        assertNull(IbFormats.quantity(Decimal.INVALID));
        assertNull(IbFormats.quantity(null));
        assertTrue(IbFormats.quantity(Decimal.parse("0")).signum() == 0);
    }
}
