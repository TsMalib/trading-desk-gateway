package dev.belis.trader.desk.risk;

import dev.belis.trader.desk.risk.RiskContext.PastExecution;
import dev.belis.trader.desk.risk.ThirtyDayWarning.Type;
import dev.belis.trader.desk.store.Side;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThirtyDayRuleTest {

    static final ZoneId NY = ZoneId.of("America/New_York");
    static final String ISIN = "US0378331005";
    static final String OTHER_ISIN = "US5949181045";

    /** Tuesday 24 Nov 2026, 11:00 New York. */
    static final Instant NOW = Instant.parse("2026-11-24T16:00:00Z");

    final ThirtyDayRule rule = new ThirtyDayRule();

    static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    static PastExecution sell(String at, String pnl) {
        return new PastExecution(ISIN, Side.SELL, bd("50"), bd("180"), "USD", pnl == null ? null : bd(pnl), "USD",
                Instant.parse(at));
    }

    static PastExecution buy(String at, String qty, String price) {
        return new PastExecution(ISIN, Side.BUY, bd(qty), bd(price), "USD", null, "USD", Instant.parse(at));
    }

    static void assertBd(String expected, BigDecimal actual) {
        assertTrue(actual != null && bd(expected).compareTo(actual) == 0, "expected " + expected + " got " + actual);
    }

    @Test
    void defaultZoneIsNewYork() {
        assertEquals(NY, rule.zone());
    }

    // ----------------------------------------------------------------------------------------------- buy

    @Nested
    class Buy {

        @Test
        void lossSaleWithinWindowWarnsWithAmountAndCloseDate() {
            List<ThirtyDayWarning> w = rule.forBuy(ISIN, NOW, List.of(sell("2026-11-10T15:00:00Z", "-350.25")));

            assertEquals(1, w.size());
            ThirtyDayWarning x = w.getFirst();
            assertEquals(Type.BUY_AFTER_LOSS_SALE, x.type());
            assertEquals(ISIN, x.isin());
            assertBd("-350.25", x.amount());
            assertEquals("USD", x.currency());
            assertEquals(LocalDate.of(2026, 11, 10), x.tradeDate());
            assertEquals(LocalDate.of(2026, 12, 10), x.windowClosesOn());
            assertBd("50", x.quantity());
            assertTrue(x.message().contains("350.25 USD"), x.message());
            assertTrue(x.message().contains("2026-12-11"), x.message());
        }

        @ParameterizedTest(name = "pnl {0}")
        @CsvSource(value = {"350.25", "0", "NULL"}, nullValues = "NULL")
        void profitableFlatOrUnknownSalesDoNotWarn(String pnl) {
            assertEquals(List.of(), rule.forBuy(ISIN, NOW, List.of(sell("2026-11-10T15:00:00Z", pnl))));
        }

        @Test
        void tinyLossStillWarns() {
            assertEquals(1, rule.forBuy(ISIN, NOW, List.of(sell("2026-11-10T15:00:00Z", "-0.01"))).size());
        }

        @Test
        void buysAreIgnoredForABuy() {
            assertEquals(List.of(), rule.forBuy(ISIN, NOW, List.of(buy("2026-11-10T15:00:00Z", "10", "100"))));
        }

        @Test
        void otherIsinIsIgnored() {
            PastExecution other = new PastExecution(OTHER_ISIN, Side.SELL, bd("5"), bd("400"), "USD", bd("-100"), "USD",
                    Instant.parse("2026-11-10T15:00:00Z"));
            assertEquals(List.of(), rule.forBuy(ISIN, NOW, List.of(other)));
        }

        @Test
        void isinMatchIgnoresCaseAndWhitespace() {
            PastExecution e = new PastExecution(" us0378331005 ", Side.SELL, bd("5"), bd("400"), "USD", bd("-1"), "USD",
                    Instant.parse("2026-11-10T15:00:00Z"));
            assertEquals(1, rule.forBuy(ISIN, NOW, List.of(e)).size());
        }

        @Test
        void pnlCurrencyIsReportedAsIbkrGaveIt() {
            PastExecution e = new PastExecution(ISIN, Side.SELL, bd("5"), bd("400"), "USD", bd("-91.50"), "EUR",
                    Instant.parse("2026-11-10T15:00:00Z"));
            assertEquals("EUR", rule.forBuy(ISIN, NOW, List.of(e)).getFirst().currency());
        }

        @ParameterizedTest(name = "sale at {0} -> warns {1}")
        @CsvSource({
                // today (NY) is 24 Nov: a sale on 25 Oct closes on 24 Nov (still inside), 24 Oct closed on 23 Nov
                "2026-10-25T15:00:00Z, true",
                "2026-10-24T15:00:00Z, false",
                // 25 Oct 00:30 NY (EDT) = 04:30Z: still the 25th in New York
                "2026-10-25T04:30:00Z, true",
                // 02:30Z on 25 Oct is 22:30 on 24 Oct in New York: outside although the UTC date is the 25th
                "2026-10-25T02:30:00Z, false",
                // the same day, earlier this morning
                "2026-11-24T14:35:00Z, true",
        })
        void windowBoundaries(String at, boolean warns) {
            assertEquals(warns, !rule.forBuy(ISIN, NOW, List.of(sell(at, "-10"))).isEmpty());
        }

        @Test
        void boundaryDependsOnTheRuleZone() {
            PastExecution lateNySale = sell("2026-10-25T02:30:00Z", "-10"); // 24 Oct NY, 25 Oct UTC
            assertEquals(List.of(), rule.forBuy(ISIN, NOW, List.of(lateNySale)));
            List<ThirtyDayWarning> utc = new ThirtyDayRule(ZoneOffset.UTC).forBuy(ISIN, NOW, List.of(lateNySale));
            assertEquals(1, utc.size());
            assertEquals(LocalDate.of(2026, 11, 24), utc.getFirst().windowClosesOn());
        }

        @Test
        void lastDayLastMinuteAcrossTheAutumnDstChange() {
            // sold 25 Oct (EDT); the window spans the 1 Nov switch to EST and closes at the end of 24 Nov NY time
            List<PastExecution> sale = List.of(sell("2026-10-25T15:00:00Z", "-10"));
            assertEquals(1, rule.forBuy(ISIN, Instant.parse("2026-11-25T04:59:59Z"), sale).size()); // 23:59:59 EST
            assertEquals(0, rule.forBuy(ISIN, Instant.parse("2026-11-25T05:00:00Z"), sale).size()); // 00:00 EST 25th
        }

        @Test
        void lastDayLastMinuteAcrossTheSpringDstChange() {
            // sold 1 Mar 2026 (EST), window closes 31 Mar, after the 8 Mar switch to EDT
            List<PastExecution> sale = List.of(sell("2026-03-01T17:00:00Z", "-10"));
            assertEquals(LocalDate.of(2026, 3, 31),
                    rule.forBuy(ISIN, Instant.parse("2026-03-20T12:00:00Z"), sale).getFirst().windowClosesOn());
            assertEquals(1, rule.forBuy(ISIN, Instant.parse("2026-04-01T03:59:59Z"), sale).size()); // 23:59:59 EDT
            assertEquals(0, rule.forBuy(ISIN, Instant.parse("2026-04-01T04:00:00Z"), sale).size()); // 00:00 EDT 1 Apr
        }

        @Test
        void windowCrossesMonthEnd() {
            List<ThirtyDayWarning> w = rule.forBuy(ISIN, Instant.parse("2026-02-10T17:00:00Z"),
                    List.of(sell("2026-01-31T17:00:00Z", "-10")));
            assertEquals(LocalDate.of(2026, 3, 2), w.getFirst().windowClosesOn());
        }

        @Test
        void futureExecutionsAreIgnored() {
            assertEquals(List.of(), rule.forBuy(ISIN, NOW, List.of(sell("2026-11-24T16:00:01Z", "-10"))));
        }

        @Test
        void severalWarningsInTimeOrder() {
            List<ThirtyDayWarning> w = rule.forBuy(ISIN, NOW, List.of(
                    sell("2026-11-20T15:00:00Z", "-20"),
                    sell("2026-11-05T15:00:00Z", "-10"),
                    sell("2026-11-12T15:00:00Z", "30")));
            assertEquals(2, w.size());
            assertBd("-10", w.get(0).amount());
            assertBd("-20", w.get(1).amount());
        }
    }

    // ----------------------------------------------------------------------------------------------- exit

    @Nested
    class Exit {

        @Test
        void recentBuyWarnsWithValueAndCloseDate() {
            List<ThirtyDayWarning> w = rule.forExit(ISIN, NOW, List.of(buy("2026-11-03T15:00:00Z", "50", "182.40")));

            assertEquals(1, w.size());
            ThirtyDayWarning x = w.getFirst();
            assertEquals(Type.EXIT_AFTER_RECENT_BUY, x.type());
            assertBd("9120.00", x.amount());
            assertEquals("USD", x.currency());
            assertEquals(LocalDate.of(2026, 11, 3), x.tradeDate());
            assertEquals(LocalDate.of(2026, 12, 3), x.windowClosesOn());
            assertTrue(x.message().contains("2026-12-04"), x.message());
        }

        @Test
        void sellsAreIgnoredForAnExitEvenAtALoss() {
            assertEquals(List.of(), rule.forExit(ISIN, NOW, List.of(sell("2026-11-10T15:00:00Z", "-100"))));
        }

        @ParameterizedTest(name = "buy at {0} -> warns {1}")
        @CsvSource({
                "2026-10-25T13:30:00Z, true",   // 30 days ago: closes today
                "2026-10-24T19:59:00Z, false",  // 31 days ago
                "2026-11-24T14:31:00Z, true",   // bought this morning
        })
        void windowBoundaries(String at, boolean warns) {
            assertEquals(warns, !rule.forExit(ISIN, NOW, List.of(buy(at, "1", "100"))).isEmpty());
        }

        @Test
        void everyBuyInTheWindowWarns() {
            List<ThirtyDayWarning> w = rule.forExit(ISIN, NOW, List.of(
                    buy("2026-11-02T15:00:00Z", "10", "100"),
                    buy("2026-11-16T15:00:00Z", "5", "110"),
                    buy("2026-09-01T15:00:00Z", "5", "90")));
            assertEquals(2, w.size());
            assertBd("1000", w.get(0).amount());
            assertBd("550", w.get(1).amount());
        }
    }

    // ----------------------------------------------------------------------------------------------- inputs

    @Test
    void missingInputsGiveNoWarnings() {
        List<PastExecution> one = List.of(sell("2026-11-10T15:00:00Z", "-10"));
        assertEquals(List.of(), rule.forBuy(null, NOW, one));
        assertEquals(List.of(), rule.forBuy(" ", NOW, one));
        assertEquals(List.of(), rule.forBuy(ISIN, NOW, null));
        assertEquals(List.of(), rule.forExit(ISIN, NOW, Arrays.asList((PastExecution) null)));
        PastExecution noTime = new PastExecution(ISIN, Side.SELL, bd("1"), bd("1"), "USD", bd("-1"), "USD", null);
        assertEquals(List.of(), rule.forBuy(ISIN, NOW, List.of(noTime)));
    }

    @Test
    void contextOverloadsUseItsTimeAndExecutions() {
        RiskContext ctx = new RiskContext("EUR", bd("1"), bd("1"), bd("0"), bd("1"), bd("1"), List.of(), List.of(), 0,
                Optional.empty(), ZonedDateTime.ofInstant(NOW, NY), Optional.empty(), false, Set.of(), false,
                List.of(sell("2026-11-10T15:00:00Z", "-10"), buy("2026-11-11T15:00:00Z", "1", "100")));
        assertEquals(Type.BUY_AFTER_LOSS_SALE, rule.forBuy(ISIN, ctx).getFirst().type());
        assertEquals(Type.EXIT_AFTER_RECENT_BUY, rule.forExit(ISIN, ctx).getFirst().type());
        assertEquals(1, rule.forBuy(ISIN, ctx).size());
        assertEquals(1, rule.forExit(ISIN, ctx).size());
    }
}
