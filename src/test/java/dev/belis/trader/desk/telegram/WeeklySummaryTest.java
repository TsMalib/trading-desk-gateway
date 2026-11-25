package dev.belis.trader.desk.telegram;

import dev.belis.trader.desk.broker.ContractInfo;
import dev.belis.trader.desk.broker.FakeBroker;
import dev.belis.trader.desk.broker.TradingSession;
import dev.belis.trader.desk.config.DeskConfig;
import dev.belis.trader.desk.proposal.Decision;
import dev.belis.trader.desk.proposal.ProposalOutcome;
import dev.belis.trader.desk.proposal.ProposalRequests;
import dev.belis.trader.desk.proposal.ProposalService;
import dev.belis.trader.desk.support.Desk;
import dev.belis.trader.desk.support.FixedClock;
import dev.belis.trader.desk.support.Market;
import dev.belis.trader.desk.support.RecordingTelegram;
import dev.belis.trader.desk.support.TestDb;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static dev.belis.trader.desk.support.Desk.n;
import static dev.belis.trader.desk.support.Market.AAPL;
import static dev.belis.trader.desk.support.Market.TSLA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link WeeklySummary}: Friday 27 Nov 2026, a stop-out, a target hit, one open position and one external. */
@QuarkusTest
class WeeklySummaryTest {

    /** Friday 27 Nov 2026, 11:00 New York. */
    static final Instant FRIDAY = Instant.parse("2026-11-27T16:00:00Z");

    @Inject
    WeeklySummary summary;

    @Inject
    ProposalService service;

    @Inject
    Desk desk;

    @Inject
    FakeBroker fake;

    @Inject
    RecordingTelegram telegram;

    @Inject
    AlertService alerts;

    @Inject
    TestDb db;

    FixedClock clock;

    @BeforeEach
    void setUp() {
        db.clean();
        telegram.reset();
        alerts.resetDedupe();
        clock = FixedClock.install();
        clock.set(FRIDAY);
        Market.open(fake, FRIDAY);
    }

    @AfterEach
    void tearDown() {
        db.clean();
        fake.reset();
    }

    void week() {
        // #1: stopped out. Bought 100 at 100.00 (avg cost 100.01), stop filled at the 94.80 bid: -522, -1.04R
        desk.filledAapl();
        Market.quote(fake, "AAPL", "94.90");
        desk.idle();
        // #2: target. Equity is lower now, so 99 shares; bought at 100.00, sold at the 112.10 bid: +1195.90, +2.42R
        Market.quote(fake, "AAPL", "100.20");
        long second = desk.proposeAapl();
        service.approve(second, Desk.OWNER);
        desk.idle();
        Market.quote(fake, "AAPL", "99.90");
        Market.quote(fake, "AAPL", "112.20");
        desk.idle();
        assertEquals("CLOSED", desk.load(1).status);
        assertEquals("CLOSED", desk.load(second).status);
        // #3: MSFT still open, stop 380
        Market.quote(fake, "MSFT", "400.00");
        ProposalOutcome o = service.proposeEntry(new ProposalRequests.Entry("MSFT", n("400"), n("380"), n("450"),
                "Base.", List.of(), false, "grok-test"));
        assertTrue(o.accepted(), o.failures().toString());
        assertEquals(Decision.Outcome.APPROVED, service.approve(o.proposalId(), Desk.OWNER).outcome());
        desk.idle();
        Market.quote(fake, "MSFT", "399.90");
        desk.idle();
        assertEquals("FILLED", desk.load(o.proposalId()).status);
        // external, no stop
        fake.setPosition(TSLA, n("3"), n("240"));
    }

    @Test
    void summaryOfTheWeek() {
        week();

        String text = summary.build(clock.instant());

        assertTrue(text.startsWith("[PAPER] 📅 <b>Week of Mon 23 Nov</b>"), text);
        assertTrue(text.contains("<b>Closed trades</b> (2)"), text);
        assertTrue(text.contains("#1 AAPL 100 @ 100  -522.00 USD  -1.04R  STOP"), text);
        assertTrue(text.contains("#2 AAPL 99 @ 100  +1195.90 USD  +2.42R  TARGET"), text);
        assertTrue(text.contains("Win rate: 50% (1 of 2)"), text);
        assertTrue(text.contains("Average R: +0.69R"), text);
        assertTrue(text.contains("Realized: +673.90 USD"), text);
        assertTrue(text.contains("<b>Open risk</b>"), text);
        assertTrue(text.contains("MSFT ") && text.contains("stop 380"), text);
        assertTrue(text.contains("TSLA 3  NO STOP (external)"), text);
        assertTrue(text.contains("⚠️ No stop: TSLA"), text);
        assertTrue(text.contains("<b>30-day rule</b>"), text);
        assertTrue(text.contains("• AAPL: Sold at a loss of 522"), text);
        assertTrue(text.contains("• MSFT: Bought "), text);
    }

    @Test
    void sentOnceTenMinutesAfterTheClose() {
        clock.set(Instant.parse("2026-11-27T21:09:59Z")); // 16:09:59 New York
        assertFalse(summary.sendIfDue());
        assertTrue(telegram.sent().isEmpty());

        clock.set(Instant.parse("2026-11-27T21:10:00Z"));
        assertTrue(summary.sendIfDue());
        assertTrue(telegram.lastSent().text().contains("<b>Closed trades</b> (0)\nnone"),
                telegram.lastSent().text());
        assertEquals(1, desk.audit(WeeklySummary.EVENT_SENT).size());

        clock.set(Instant.parse("2026-11-27T21:30:00Z"));
        assertFalse(summary.sendIfDue(), "once per Friday");
        assertEquals(1, telegram.sent().size());
    }

    @Test
    void halfDayUsesTheCloseIbkrReports() {
        LocalDate friday = LocalDate.of(2026, 11, 27);
        ContractInfo aapl = fake.contract("AAPL");
        fake.addContract(new ContractInfo(aapl.conid(), aapl.symbol(), aapl.primaryExchange(), aapl.currency(),
                aapl.isin(), aapl.name(), aapl.industry(), aapl.timeZoneId(), Optional.of(new TradingSession(friday,
                friday.atTime(9, 30).atZone(DeskConfig.MARKET_ZONE).toInstant(),
                friday.atTime(13, 0).atZone(DeskConfig.MARKET_ZONE).toInstant()))));
        desk.proposeAapl(); // stores the instrument the close is looked up with
        assertEquals(AAPL, aapl.conid());

        clock.set(Instant.parse("2026-11-27T18:09:00Z")); // 13:09 New York
        assertFalse(summary.sendIfDue());
        clock.set(Instant.parse("2026-11-27T18:10:00Z"));
        assertTrue(summary.sendIfDue());
    }

    @Test
    void notOnOtherDays() {
        clock.set(Instant.parse("2026-11-26T21:30:00Z")); // Thursday 16:30
        assertFalse(summary.sendIfDue());
    }
}
