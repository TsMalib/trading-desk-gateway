package dev.belis.trader.desk.risk;

import dev.belis.trader.desk.broker.Quote;
import dev.belis.trader.desk.config.DeskConfig;
import dev.belis.trader.desk.risk.RiskContext.OpenPosition;
import dev.belis.trader.desk.risk.RiskContext.PendingEntry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskEngineTest {

    static final ZoneId NY = ZoneId.of("America/New_York");
    static final long AAPL = 265598L;

    /** The defaults from DeskConfig.Risk. */
    static final RiskLimits LIMITS = new RiskLimits(bd("0.5"), bd("10"), 5, 3, bd("2.0"), bd("1.0"), bd("1"),
            bd("15"), LocalTime.of(9, 45), 30, bd("2.0"), 2);

    final RiskEngine engine = new RiskEngine(LIMITS);

    // ----------------------------------------------------------------------------------------------- fixture

    static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    static EntryProposal proposal(String entry, String stop, String target) {
        return new EntryProposal(42L, AAPL, "AAPL", "Technology", "USD",
                entry == null ? null : bd(entry), stop == null ? null : bd(stop), target == null ? null : bd(target));
    }

    static EntryProposal proposal() {
        return proposal("100", "94", "115");
    }

    static Quote quote(String last) {
        return new Quote("AAPL", last == null ? null : bd(last), null, null, bd("99"), false,
                Instant.parse("2026-11-24T16:00:00Z"));
    }

    /** Tuesday 24 Nov 2026, 11:00 New York (EST), regular 16:00 close. */
    static Ctx ctx() {
        return new Ctx();
    }

    static final class Ctx {
        String base = "EUR";
        BigDecimal equity = bd("100000");
        BigDecimal sod = bd("100000");
        BigDecimal pnl = BigDecimal.ZERO;
        BigDecimal fx = bd("0.92");
        BigDecimal cash = bd("50000");
        List<OpenPosition> open = new ArrayList<>();
        List<PendingEntry> pending = new ArrayList<>();
        int entriesToday = 0;
        Optional<Quote> quote = Optional.of(quote("100.20"));
        ZonedDateTime now = ZonedDateTime.of(2026, 11, 24, 11, 0, 0, 0, NY);
        Optional<Instant> close = Optional.of(Instant.parse("2026-11-24T21:00:00Z"));
        boolean kill = false;
        Set<String> whitelist = Set.of("AAPL", "MSFT");
        boolean earnings = false;

        Ctx at(ZonedDateTime t) { now = t; return this; }
        Ctx at(String utc) { now = Instant.parse(utc).atZone(NY); return this; }
        Ctx close(String utc) { close = Optional.ofNullable(utc).map(Instant::parse); return this; }
        Ctx holiday() { close = Optional.empty(); return this; }
        Ctx open(OpenPosition... p) { open.addAll(Arrays.asList(p)); return this; }
        Ctx pending(PendingEntry... p) { pending.addAll(Arrays.asList(p)); return this; }

        RiskContext build() {
            return new RiskContext(base, equity, sod, pnl, fx, cash, open, pending, entriesToday, quote, now, close,
                    kill, whitelist, earnings, List.of());
        }
    }

    static OpenPosition position(long conid, String symbol, String industry) {
        return new OpenPosition(conid, symbol, industry, bd("10"));
    }

    static PendingEntry pendingEntry(Long id, long conid, String symbol, String industry, String ccy, String qty,
                                     String entry) {
        return new PendingEntry(id, conid, symbol, industry, ccy, bd(qty), bd(entry));
    }

    RiskResult eval(Ctx c) {
        return engine.evaluate(proposal(), c.build());
    }

    static void assertOnlyFailure(RiskResult r, RiskRule... rules) {
        List<RiskRule> failed = r.failures().stream().map(RiskCheck::rule).toList();
        assertEquals(List.of(rules), failed, () -> "failures: " + r.failures());
        assertFalse(r.passed());
    }

    static void assertBd(String expected, BigDecimal actual) {
        assertTrue(actual != null && bd(expected).compareTo(actual) == 0, "expected " + expected + " got " + actual);
    }

    // ----------------------------------------------------------------------------------------------- happy path

    @Test
    void happyPathPassesEveryRuleAndSizesFromRisk() {
        RiskResult r = eval(ctx());

        assertTrue(r.passed(), () -> "failures: " + r.failures());
        // budget 500 EUR / ((100 - 94) * 0.92 = 5.52 EUR per share) = 90.58 -> 90
        assertBd("90", r.quantity());
        assertBd("496.80", r.riskEur());
        assertEquals(2, r.riskEur().scale());
        assertBd("2.50", r.rewardRisk());
    }

    @Test
    void everyRuleIsReportedInOrder() {
        RiskResult r = eval(ctx());
        assertEquals(List.of(RiskRule.values()), r.checks().stream().map(RiskCheck::rule).toList());
        r.checks().forEach(c -> {
            assertTrue(c.actual() != null && !c.actual().isBlank(), c::toString);
            assertTrue(c.limit() != null && !c.limit().isBlank(), c::toString);
        });
    }

    @Test
    void allRulesAreEvaluatedEvenAfterAFailure() {
        Ctx c = ctx();
        c.kill = true;
        c.earnings = true;
        c.entriesToday = 3;
        assertOnlyFailure(eval(c), RiskRule.KILL_SWITCH, RiskRule.EARNINGS, RiskRule.MAX_ENTRIES_PER_DAY);
    }

    @Test
    void limitsFromConfig() {
        DeskConfig.Risk cfg = new DeskConfig.Risk() {
            public BigDecimal riskPerTradePct() { return bd("0.5"); }
            public BigDecimal maxPositionPct() { return bd("10"); }
            public int maxOpenPositions() { return 5; }
            public int maxEntriesPerDay() { return 3; }
            public BigDecimal minRewardRisk() { return bd("2.0"); }
            public BigDecimal maxDriftPct() { return bd("1.0"); }
            public BigDecimal minStopPct() { return bd("1"); }
            public BigDecimal maxStopPct() { return bd("15"); }
            public LocalTime windowStart() { return LocalTime.of(9, 45); }
            public int closeBufferMinutes() { return 30; }
            public BigDecimal dailyLossPct() { return bd("2.0"); }
            public int maxPerIndustry() { return 2; }
            public Optional<List<String>> whitelist() { return Optional.of(List.of("AAPL")); }
        };
        assertEquals(LIMITS, RiskLimits.from(cfg));
    }

    // ----------------------------------------------------------------------------------------------- sizing

    @Nested
    class Sizing {

        @Test
        void cappedByMaxPositionPct() {
            // by risk: 500 / (2 * 0.92) = 271.7; by position: 10000 EUR / (100 * 0.92) = 108.7 -> 108
            RiskResult r = engine.evaluate(proposal("100", "98", "106"), ctx().build());
            assertTrue(r.passed(), () -> "failures: " + r.failures());
            assertBd("108", r.quantity());
            assertBd("198.72", r.riskEur());
            assertEquals("9936.00 EUR", r.check(RiskRule.POSITION_SIZE).actual());
            assertEquals("<= 10000.00 EUR", r.check(RiskRule.POSITION_SIZE).limit());
        }

        @Test
        void quantityIsFlooredNeverRounded() {
            Ctx c = ctx();
            c.fx = BigDecimal.ONE;
            c.base = "USD";
            c.equity = bd("100788"); // budget 503.94 / 6 = 83.99 -> 83
            RiskResult r = eval(c);
            assertBd("83", r.quantity());
            assertBd("498.00", r.riskEur());
        }

        @Test
        void quantityRoundingToZeroFails() {
            Ctx c = ctx();
            c.equity = bd("1000"); // budget 5 EUR / 5.52 = 0.9 -> 0
            RiskResult r = eval(c);
            assertBd("0", r.quantity());
            assertBd("0.00", r.riskEur());
            assertOnlyFailure(r, RiskRule.MIN_QUANTITY);
        }

        @Test
        void positionCapRoundingToZeroFails() {
            // budget 0.5 % of 5000 = 25; by risk: 25 / (10 * 0.92) = 2.7; by position: 500 / (1000 * 0.92) = 0.54 -> 0
            Ctx c = ctx();
            c.equity = bd("5000");
            c.quote = Optional.of(quote("1000"));
            RiskResult r = engine.evaluate(proposal("1000", "990", "1030"), c.build());
            assertBd("0", r.quantity());
            assertOnlyFailure(r, RiskRule.MIN_QUANTITY);
        }

        @Test
        void exactlyOneShareIsEnough() {
            Ctx c = ctx();
            c.equity = bd("1104"); // budget 5.52 / 5.52 = 1
            RiskResult r = eval(c);
            assertBd("1", r.quantity());
            assertTrue(r.check(RiskRule.MIN_QUANTITY).passed());
        }

        @Test
        void zeroOrMissingEquityGivesZeroQuantity() {
            Ctx c = ctx();
            c.equity = BigDecimal.ZERO;
            assertBd("0", eval(c).quantity());
            c.equity = null;
            RiskResult r = eval(c);
            assertBd("0", r.quantity());
            assertFalse(r.check(RiskRule.MIN_QUANTITY).passed());
            assertFalse(r.check(RiskRule.POSITION_SIZE).passed());
        }

        @Test
        void baseCurrencyInstrumentUsesFxOne() {
            Ctx c = ctx();
            c.fx = BigDecimal.ONE;
            c.cash = bd("50000");
            EntryProposal p = new EntryProposal(7L, 1L, "SAP", "Software", "EUR", bd("100"), bd("94"), bd("115"));
            c.whitelist = Set.of("SAP");
            RiskResult r = engine.evaluate(p, c.build());
            assertTrue(r.passed(), () -> "failures: " + r.failures());
            assertBd("83", r.quantity()); // 500 / 6
            assertBd("498.00", r.riskEur());
        }

        @Test
        void baseCurrencyInstrumentWithFxOtherThanOneFails() {
            EntryProposal p = new EntryProposal(7L, 1L, "SAP", "Software", "EUR", bd("100"), bd("94"), bd("115"));
            Ctx c = ctx();
            c.whitelist = Set.of("SAP");
            RiskResult r = engine.evaluate(p, c.build());
            assertFalse(r.check(RiskRule.FX_RATE).passed());
            assertBd("0", r.quantity());
        }

        @Test
        void missingOrNonPositiveFxFails() {
            for (BigDecimal fx : Arrays.asList(null, BigDecimal.ZERO, bd("-0.92"))) {
                Ctx c = ctx();
                c.fx = fx;
                RiskResult r = eval(c);
                assertFalse(r.check(RiskRule.FX_RATE).passed(), "fx " + fx);
                assertBd("0", r.quantity());
                assertNull(r.riskEur());
                assertFalse(r.passed());
            }
        }

        @Test
        void strongerUsdMeansFewerShares() {
            Ctx c = ctx();
            c.fx = bd("1.10"); // 500 / 6.6 = 75.7
            assertBd("75", eval(c).quantity());
        }
    }

    // ----------------------------------------------------------------------------------------------- prices

    @Nested
    class Prices {

        @Test
        void stopEqualToEntryFailsWithoutDividingByZero() {
            RiskResult r = engine.evaluate(proposal("100", "100", "115"), ctx().build());
            assertFalse(r.check(RiskRule.PRICE_ORDER).passed());
            assertFalse(r.check(RiskRule.STOP_DISTANCE).passed());
            assertFalse(r.check(RiskRule.REWARD_RISK).passed());
            assertFalse(r.check(RiskRule.MIN_QUANTITY).passed());
            assertBd("0", r.quantity());
            assertNull(r.riskEur());
            assertNull(r.rewardRisk());
        }

        @ParameterizedTest(name = "stop {1}, entry {0}, target {2}")
        @CsvSource({
                "100, 101, 115",  // stop above entry
                "100, 94, 100",   // target equal to entry
                "100, 94, 99",    // target below entry
                "100, 0, 115",    // stop zero
                "100, -5, 115",   // negative stop
        })
        void pricesOutOfOrderFail(String entry, String stop, String target) {
            RiskResult r = engine.evaluate(proposal(entry, stop, target), ctx().build());
            assertFalse(r.check(RiskRule.PRICE_ORDER).passed());
            assertBd("0", r.quantity());
            assertFalse(r.passed());
        }

        @Test
        void missingPricesFail() {
            RiskResult r = engine.evaluate(proposal(null, null, null), ctx().build());
            assertFalse(r.check(RiskRule.PRICE_ORDER).passed());
            assertFalse(r.check(RiskRule.DRIFT).passed());
            assertFalse(r.check(RiskRule.CASH).passed());
            assertBd("0", r.quantity());
        }

        @ParameterizedTest(name = "stop {0} -> {1}")
        @CsvSource({
                "99,    true",   // exactly 1 %
                "99.01, false",  // 0.99 %
                "85,    true",   // exactly 15 %
                "84.99, false",  // 15.01 %
        })
        void stopDistanceBounds(String stop, boolean passes) {
            // target far enough that reward/risk never interferes
            RiskResult r = engine.evaluate(proposal("100", stop, "200"), ctx().build());
            assertEquals(passes, r.check(RiskRule.STOP_DISTANCE).passed(), r.check(RiskRule.STOP_DISTANCE)::toString);
        }

        @Test
        void rewardRiskExactlyAtMinimumPasses() {
            RiskResult r = engine.evaluate(proposal("100", "94", "112"), ctx().build());
            assertBd("2.00", r.rewardRisk());
            assertTrue(r.check(RiskRule.REWARD_RISK).passed());
        }

        @Test
        void rewardRiskJustBelowMinimumIsTruncatedAndFails() {
            // 11.99 / 6 = 1.998: must not round up to 2.00
            RiskResult r = engine.evaluate(proposal("100", "94", "111.99"), ctx().build());
            assertBd("1.99", r.rewardRisk());
            assertFalse(r.check(RiskRule.REWARD_RISK).passed());
        }
    }

    // ----------------------------------------------------------------------------------------------- quote / drift

    @Nested
    class QuoteAndDrift {

        @ParameterizedTest(name = "entry {0} vs last 100 -> {1}")
        @CsvSource({
                "101,       true",   // +1 % exactly at the limit
                "99,        true",   // -1 % exactly at the limit
                "101.01,    false",
                "101.0001,  false",
                "98.99,     false",
                "100,       true",
        })
        void driftAtAndAroundTheLimit(String entry, boolean passes) {
            Ctx c = ctx();
            c.quote = Optional.of(quote("100"));
            BigDecimal e = bd(entry);
            // keep stop 5 % below and reward/risk 3 so only drift varies
            EntryProposal p = new EntryProposal(42L, AAPL, "AAPL", "Technology", "USD", e,
                    e.multiply(bd("0.95")), e.multiply(bd("1.15")));
            RiskResult r = engine.evaluate(p, c.build());
            assertEquals(passes, r.check(RiskRule.DRIFT).passed(), r.check(RiskRule.DRIFT)::toString);
            if (passes) {
                assertTrue(r.passed(), () -> "failures: " + r.failures());
            }
        }

        @Test
        void driftExactlyAtLimitReportsOnePercent() {
            Ctx c = ctx();
            c.quote = Optional.of(quote("100"));
            RiskCheck drift = engine.evaluate(proposal("101", "95", "115"), c.build()).check(RiskRule.DRIFT);
            assertEquals("1%", drift.actual());
            assertEquals("<= 1.0%", drift.limit());
        }

        @Test
        void midpointIsUsedWithoutALastPrice() {
            Ctx c = ctx();
            c.quote = Optional.of(new Quote("AAPL", null, bd("99.90"), bd("100.10"), null, false, Instant.now()));
            RiskResult r = eval(c);
            assertTrue(r.check(RiskRule.QUOTE).passed());
            assertTrue(r.check(RiskRule.DRIFT).passed());
            assertEquals("0%", r.check(RiskRule.DRIFT).actual());
        }

        @Test
        void delayedQuoteFails() {
            Ctx c = ctx();
            c.quote = Optional.of(new Quote("AAPL", bd("100.20"), null, null, null, true, Instant.now()));
            assertOnlyFailure(eval(c), RiskRule.QUOTE);
        }

        @Test
        void missingQuoteFailsQuoteAndDrift() {
            Ctx c = ctx();
            c.quote = Optional.empty();
            assertOnlyFailure(eval(c), RiskRule.QUOTE, RiskRule.DRIFT);
        }

        @Test
        void quoteWithoutAnyPriceFails() {
            Ctx c = ctx();
            c.quote = Optional.of(new Quote("AAPL", null, bd("99.9"), null, bd("99"), false, Instant.now()));
            assertOnlyFailure(eval(c), RiskRule.QUOTE, RiskRule.DRIFT);
        }
    }

    // ----------------------------------------------------------------------------------------------- entry window

    @Nested
    class EntryWindow {

        RiskCheck window(Ctx c) {
            return eval(c).check(RiskRule.ENTRY_WINDOW);
        }

        @ParameterizedTest(name = "{0} NY -> {1}")
        @CsvSource({
                "09:29:59, false",
                "09:44:59, false",
                "09:45:00, true",
                "12:00:00, true",
                "15:29:59, true",
                "15:30:00, false",
                "16:00:00, false",
                "18:00:00, false",
        })
        void regularDay(String time, boolean open) {
            Ctx c = ctx().at(ZonedDateTime.of(LocalDate.of(2026, 11, 24), LocalTime.parse(time), NY));
            assertEquals(open, window(c).passed(), window(c)::toString);
        }

        @ParameterizedTest(name = "early close, {0} NY -> {1}")
        @CsvSource({
                "09:45:00, true",
                "12:29:59, true",
                "12:30:00, false",
                "13:00:00, false",
                "15:00:00, false",  // would be open on a regular day
        })
        void earlyCloseAt13(String time, boolean open) {
            // Friday after Thanksgiving 2026: closes 13:00 EST = 18:00Z
            Ctx c = ctx().close("2026-11-27T18:00:00Z")
                    .at(ZonedDateTime.of(LocalDate.of(2026, 11, 27), LocalTime.parse(time), NY));
            RiskCheck w = window(c);
            assertEquals(open, w.passed(), w::toString);
            assertEquals("[09:45, 12:30) America/New_York", w.limit());
        }

        @Test
        void marketHolidayIsClosedAllDay() {
            // Thanksgiving, 26 Nov 2026: IBKR reports CLOSED, so there is no session
            Ctx c = ctx().holiday().at(ZonedDateTime.of(2026, 11, 26, 11, 0, 0, 0, NY));
            RiskResult r = eval(c);
            assertOnlyFailure(r, RiskRule.ENTRY_WINDOW);
            assertEquals("market closed today", r.check(RiskRule.ENTRY_WINDOW).limit());
        }

        @Test
        void weekendIsClosed() {
            Ctx c = ctx().holiday().at(ZonedDateTime.of(2026, 11, 28, 11, 0, 0, 0, NY));
            assertFalse(window(c).passed());
        }

        @Test
        void sessionCloseOfAnotherDayIsRejected() {
            // yesterday's close passed by mistake: never trust it for today
            Ctx c = ctx().close("2026-11-23T21:00:00Z");
            RiskCheck w = window(c);
            assertFalse(w.passed());
            assertTrue(w.limit().contains("2026-11-23"), w.limit());
        }

        @Test
        void nowIsEvaluatedInNewYorkWhateverItsZone() {
            // 17:00 Berlin (CET) = 11:00 New York (EST)
            Ctx c = ctx().at(ZonedDateTime.of(2026, 11, 24, 17, 0, 0, 0, ZoneId.of("Europe/Berlin")));
            RiskCheck w = window(c);
            assertTrue(w.passed(), w::toString);
            assertTrue(w.actual().startsWith("2026-11-24 11:00:00"), w.actual());
        }

        // --- DST change weeks. The US and the EU switch on different Sundays; only New York time matters.

        @ParameterizedTest(name = "spring, {0} -> {1}")
        @CsvSource({
                // Fri 6 Mar 2026, still EST (UTC-5): window 14:45Z .. 20:30Z
                "2026-03-06T14:44:59Z, false",
                "2026-03-06T14:45:00Z, true",
                "2026-03-06T20:29:59Z, true",
                "2026-03-06T20:30:00Z, false",
                // Mon 9 Mar 2026, EDT since Sun 8 Mar (UTC-4): window 13:45Z .. 19:30Z
                "2026-03-09T13:44:59Z, false",
                "2026-03-09T13:45:00Z, true",
                "2026-03-09T19:29:59Z, true",
                "2026-03-09T19:30:00Z, false",
                "2026-03-09T20:00:00Z, false",
        })
        void springForward(String now, boolean open) {
            Instant t = Instant.parse(now);
            LocalDate d = t.atZone(NY).toLocalDate();
            Instant close = ZonedDateTime.of(d, LocalTime.of(16, 0), NY).toInstant();
            Ctx c = ctx().at(now).close(close.toString());
            assertEquals(open, window(c).passed(), window(c)::toString);
        }

        @Test
        void springGapWeekBerlinStillOnWinterTime() {
            // 9 Mar 2026: New York on EDT, Berlin still on CET until 29 Mar.
            // 14:45 Berlin = 13:45Z = 09:45 New York: the window has just opened.
            Ctx c = ctx().close("2026-03-09T20:00:00Z")
                    .at(ZonedDateTime.of(2026, 3, 9, 14, 45, 0, 0, ZoneId.of("Europe/Berlin")));
            assertTrue(window(c).passed());
            c.at(ZonedDateTime.of(2026, 3, 9, 14, 44, 59, 0, ZoneId.of("Europe/Berlin")));
            assertFalse(window(c).passed());
        }

        @ParameterizedTest(name = "autumn, {0} -> {1}")
        @CsvSource({
                // Tue 27 Oct 2026: Berlin back on CET since 25 Oct, New York still EDT: window 13:45Z .. 19:30Z
                "2026-10-27T13:45:00Z, true",
                "2026-10-27T19:29:59Z, true",
                "2026-10-27T19:30:00Z, false",
                // Mon 2 Nov 2026, EST since Sun 1 Nov: window 14:45Z .. 20:30Z
                "2026-11-02T13:45:00Z, false",
                "2026-11-02T14:44:59Z, false",
                "2026-11-02T14:45:00Z, true",
                "2026-11-02T20:29:59Z, true",
                "2026-11-02T20:30:00Z, false",
        })
        void fallBack(String now, boolean open) {
            Instant t = Instant.parse(now);
            LocalDate d = t.atZone(NY).toLocalDate();
            Instant close = ZonedDateTime.of(d, LocalTime.of(16, 0), NY).toInstant();
            Ctx c = ctx().at(now).close(close.toString());
            assertEquals(open, window(c).passed(), window(c)::toString);
        }

        @Test
        void lateEveningUtcIsStillTheSameNewYorkDay() {
            // 01:00Z on 25 Nov is 20:00 on 24 Nov in New York: after the close, and the session is still 24 Nov's
            Ctx c = ctx().at("2026-11-25T01:00:00Z");
            RiskCheck w = window(c);
            assertFalse(w.passed());
            assertTrue(w.limit().startsWith("["), "compared with today's session, not rejected as another day");
        }
    }

    // ----------------------------------------------------------------------------------------------- account

    @Nested
    class Account {

        @Test
        void killSwitchBlocksEntries() {
            Ctx c = ctx();
            c.kill = true;
            assertOnlyFailure(eval(c), RiskRule.KILL_SWITCH);
        }

        @Test
        void dailyLossExactlyAtLimitFails() {
            Ctx c = ctx();
            c.pnl = bd("-2000.00"); // 2 % of 100000
            RiskResult r = eval(c);
            assertOnlyFailure(r, RiskRule.DAILY_LOSS);
            assertEquals("2000.00 EUR", r.check(RiskRule.DAILY_LOSS).actual());
            assertEquals("< 2000.00 EUR", r.check(RiskRule.DAILY_LOSS).limit());
        }

        @Test
        void dailyLossJustBelowLimitPasses() {
            Ctx c = ctx();
            c.pnl = bd("-1999.99");
            assertTrue(eval(c).check(RiskRule.DAILY_LOSS).passed());
        }

        @Test
        void dailyLossAboveLimitFails() {
            Ctx c = ctx();
            c.pnl = bd("-2500");
            assertFalse(eval(c).check(RiskRule.DAILY_LOSS).passed());
        }

        @Test
        void dailyLossIsMeasuredAgainstStartOfDayEquity() {
            Ctx c = ctx();
            c.equity = bd("98000.01");
            c.pnl = bd("-1999.99");
            assertTrue(eval(c).check(RiskRule.DAILY_LOSS).passed(), "limit is 2 % of 100000, not of 98000");
        }

        @Test
        void profitIsNoLoss() {
            Ctx c = ctx();
            c.pnl = bd("5000");
            RiskCheck check = eval(c).check(RiskRule.DAILY_LOSS);
            assertTrue(check.passed());
            assertEquals("0 EUR", check.actual());
        }

        @Test
        void unknownPnlOrStartOfDayEquityFails() {
            Ctx c = ctx();
            c.pnl = null;
            assertFalse(eval(c).check(RiskRule.DAILY_LOSS).passed());
            c = ctx();
            c.sod = null;
            assertFalse(eval(c).check(RiskRule.DAILY_LOSS).passed());
            c = ctx();
            c.sod = BigDecimal.ZERO;
            assertFalse(eval(c).check(RiskRule.DAILY_LOSS).passed());
        }

        @Test
        void symbolNotWhitelistedFails() {
            Ctx c = ctx();
            c.whitelist = Set.of("MSFT");
            assertOnlyFailure(eval(c), RiskRule.WHITELIST);
        }

        @Test
        void whitelistIsCaseAndWhitespaceInsensitive() {
            Ctx c = ctx();
            c.whitelist = Set.of(" aapl ");
            EntryProposal p = new EntryProposal(42L, AAPL, "Aapl", "Technology", "USD", bd("100"), bd("94"), bd("115"));
            assertTrue(engine.evaluate(p, c.build()).check(RiskRule.WHITELIST).passed());
        }

        @Test
        void emptyWhitelistAllowsNothing() {
            Ctx c = ctx();
            c.whitelist = Set.of();
            RiskCheck w = eval(c).check(RiskRule.WHITELIST);
            assertFalse(w.passed());
            assertEquals("(empty)", w.limit());
        }

        @Test
        void earningsFlagBlocks() {
            Ctx c = ctx();
            c.earnings = true;
            assertOnlyFailure(eval(c), RiskRule.EARNINGS);
        }

        @Test
        void entriesPerDayLimit() {
            Ctx c = ctx();
            c.entriesToday = 2;
            assertTrue(eval(c).passed());
            c.entriesToday = 3;
            assertOnlyFailure(eval(c), RiskRule.MAX_ENTRIES_PER_DAY);
        }
    }

    // ----------------------------------------------------------------------------------------------- cash

    @Nested
    class Cash {

        @Test
        void entryValueExactlyEqualToCashPasses() {
            Ctx c = ctx();
            c.cash = bd("9000"); // 90 * 100
            assertTrue(eval(c).passed());
        }

        @Test
        void entryValueAboveCashFails() {
            Ctx c = ctx();
            c.cash = bd("8999.99");
            RiskResult r = eval(c);
            assertOnlyFailure(r, RiskRule.CASH);
            assertEquals("9000 USD", r.check(RiskRule.CASH).actual());
        }

        @Test
        void pendingEntriesInTheSameCurrencyReserveCash() {
            Ctx c = ctx().pending(pendingEntry(7L, 1L, "MSFT", "Software", "USD", "120", "100"));
            c.cash = bd("20000"); // 20000 - 12000 = 8000 < 9000
            RiskResult r = eval(c);
            assertOnlyFailure(r, RiskRule.CASH);
            assertTrue(r.check(RiskRule.CASH).limit().startsWith("<= 8000 USD"), r.check(RiskRule.CASH).limit());
        }

        @Test
        void pendingEntriesInAnotherCurrencyDoNotReserveUsd() {
            Ctx c = ctx().pending(pendingEntry(7L, 1L, "SAP", "Software", "EUR", "120", "100"));
            c.cash = bd("9000");
            assertTrue(eval(c).check(RiskRule.CASH).passed());
        }

        @Test
        void thisProposalIsNotCountedAgainstItself() {
            // re-check at approval time: proposal 42 is in the pending list
            Ctx c = ctx().pending(pendingEntry(42L, AAPL, "AAPL", "Technology", "USD", "90", "100"));
            c.cash = bd("9000");
            RiskResult r = eval(c);
            assertTrue(r.passed(), () -> "failures: " + r.failures());
        }

        @Test
        void negativeCashIsAMarginLoanAndFails() {
            Ctx c = ctx();
            c.cash = bd("-1");
            c.equity = bd("1000"); // quantity 0, so the value is 0, still not allowed
            RiskResult r = eval(c);
            assertFalse(r.check(RiskRule.CASH).passed());
        }

        @Test
        void unknownCashFails() {
            Ctx c = ctx();
            c.cash = null;
            assertOnlyFailure(eval(c), RiskRule.CASH);
        }
    }

    // ----------------------------------------------------------------------------------------------- portfolio

    @Nested
    class Portfolio {

        @Test
        void duplicateConidInOpenPositions() {
            RiskResult r = eval(ctx().open(position(AAPL, "AAPL.OLD", "Technology")));
            assertOnlyFailure(r, RiskRule.DUPLICATE_SYMBOL);
            assertEquals("AAPL in open positions", r.check(RiskRule.DUPLICATE_SYMBOL).actual());
        }

        @Test
        void duplicateSymbolInOpenPositions() {
            assertOnlyFailure(eval(ctx().open(position(999L, "aapl", "Other"))), RiskRule.DUPLICATE_SYMBOL);
        }

        @Test
        void duplicateInPendingProposals() {
            RiskResult r = eval(ctx().pending(pendingEntry(41L, AAPL, "AAPL", "Technology", "USD", "1", "100")));
            assertOnlyFailure(r, RiskRule.DUPLICATE_SYMBOL);
            assertEquals("AAPL in pending proposals", r.check(RiskRule.DUPLICATE_SYMBOL).actual());
        }

        @Test
        void pendingWithoutIdIsNeverSelf() {
            EntryProposal p = proposal();
            Ctx c = ctx().pending(pendingEntry(null, AAPL, "AAPL", "Technology", "USD", "1", "100"));
            assertFalse(engine.evaluate(p, c.build()).check(RiskRule.DUPLICATE_SYMBOL).passed());
        }

        @Test
        void maxOpenPositionsCountsOpenAndPending() {
            Ctx c = ctx().open(position(1, "A", "I1"), position(2, "B", "I2"), position(3, "C", "I3"),
                    position(4, "D", "I4"));
            assertTrue(eval(c).passed(), "4 open + this one = 5");

            c.pending(pendingEntry(9L, 5, "E", "I5", "EUR", "1", "1"));
            RiskResult r = eval(c);
            assertOnlyFailure(r, RiskRule.MAX_OPEN_POSITIONS);
            assertEquals("6", r.check(RiskRule.MAX_OPEN_POSITIONS).actual());
        }

        @Test
        void maxPerIndustry() {
            Ctx c = ctx().open(position(1, "MSFT", "Technology"));
            assertTrue(eval(c).passed(), "1 + this one = 2");

            c.open(position(2, "NVDA", "TECHNOLOGY"));
            RiskResult r = eval(c);
            assertOnlyFailure(r, RiskRule.MAX_PER_INDUSTRY);
            assertEquals("3 in TECHNOLOGY", r.check(RiskRule.MAX_PER_INDUSTRY).actual());
        }

        @Test
        void maxPerIndustryCountsPending() {
            Ctx c = ctx().open(position(1, "MSFT", "Technology"))
                    .pending(pendingEntry(9L, 2, "NVDA", "Technology", "EUR", "1", "1"));
            assertOnlyFailure(eval(c), RiskRule.MAX_PER_INDUSTRY);
        }

        @Test
        void otherIndustriesDoNotCount() {
            Ctx c = ctx().open(position(1, "XOM", "Energy"), position(2, "CVX", "Energy"),
                    position(3, "JPM", "Financial"));
            assertTrue(eval(c).passed());
        }

        @Test
        void unknownIndustriesShareOneBucket() {
            EntryProposal p = new EntryProposal(42L, AAPL, "AAPL", null, "USD", bd("100"), bd("94"), bd("115"));
            Ctx c = ctx().open(position(1, "X", " "));
            assertTrue(engine.evaluate(p, c.build()).check(RiskRule.MAX_PER_INDUSTRY).passed());
            c.open(position(2, "Y", null));
            RiskCheck check = engine.evaluate(p, c.build()).check(RiskRule.MAX_PER_INDUSTRY);
            assertFalse(check.passed());
            assertEquals("3 in UNKNOWN", check.actual());
        }
    }
}
