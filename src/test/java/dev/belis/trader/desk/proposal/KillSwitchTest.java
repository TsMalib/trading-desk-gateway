package dev.belis.trader.desk.proposal;

import dev.belis.trader.desk.broker.FakeBroker;
import dev.belis.trader.desk.store.AuditActor;
import dev.belis.trader.desk.store.ProposalKind;
import dev.belis.trader.desk.support.Desk;
import dev.belis.trader.desk.support.FixedClock;
import dev.belis.trader.desk.support.Market;
import dev.belis.trader.desk.support.RecordingTelegram;
import dev.belis.trader.desk.support.TestDb;
import dev.belis.trader.desk.telegram.AlertService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static dev.belis.trader.desk.support.Desk.WAIT;
import static dev.belis.trader.desk.support.Desk.n;
import static dev.belis.trader.desk.support.Market.AAPL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link KillSwitch#engage} (Telegram /kill) and {@link DailyLossGuard} against {@link FakeBroker}. */
@QuarkusTest
class KillSwitchTest {

    @Inject
    KillSwitch killSwitch;

    @Inject
    DailyLossGuard guard;

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
    Event<ProposalApproved> approvedEvents;

    @Inject
    TestDb db;

    FixedClock clock;

    @BeforeEach
    void setUp() {
        db.clean();
        telegram.reset();
        alerts.resetDedupe();
        clock = FixedClock.install();
        Market.open(fake, FixedClock.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        db.clean();
        fake.reset();
    }

    // =====================================================================================================
    // Kill switch
    // =====================================================================================================

    @Test
    void killCancelsTheUnfilledEntryAndBlocksNewOnes() {
        long id = desk.approvedAapl();

        KillSwitch.Engaged k = killSwitch.engage(AuditActor.USER, "telegram /kill");
        desk.idle();

        assertFalse(k.wasOn());
        assertTrue(k.complete());
        assertEquals(List.of("P1-ENTRY"), k.cancelled());
        assertTrue(killSwitch.isOn());
        assertEquals("CANCELLED", desk.load(id).status);
        assertTrue(fake.openOrders().isEmpty(), "the unfilled parent's children go with it");
        assertEquals(1, desk.audit(OrderExecutor.EVENT_KILL_CANCEL).size());

        ProposalOutcome o = service.proposeEntry(new ProposalRequests.Entry("AAPL", n("100"), n("95"), n("112"),
                "Again.", List.of(), false, "grok-test"));
        assertFalse(o.accepted());
        assertEquals("KILL_SWITCH", o.failures().get(0).name());
    }

    @Test
    void killKeepsStopAndTargetOfAnOpenPosition() {
        long id = desk.filledAapl();

        KillSwitch.Engaged k = killSwitch.engage(AuditActor.USER, "telegram /kill");
        desk.idle();

        assertTrue(k.cancelled().isEmpty());
        assertEquals("FILLED", desk.load(id).status);
        assertTrue(fake.openOrder("P1-STOP").isPresent());
        assertTrue(fake.openOrder("P1-TARGET").isPresent());
    }

    @Test
    void killCancelsTheRestOfAPartlyFilledEntryAndResizesItsStop() {
        long id = desk.approvedAapl();
        fake.fill("P1-ENTRY", n("100"), n("40"));
        desk.idle();

        killSwitch.engage(AuditActor.USER, "telegram /kill");
        desk.idle();

        assertEquals("FILLED", desk.load(id).status, "40 bought: a position, protected");
        assertEquals("Cancelled", fake.statusOf("P1-ENTRY"));
        assertEquals(0, n("40").compareTo(fake.openOrder("P1-STOP").orElseThrow().quantity()));
        assertEquals(0, n("40").compareTo(fake.openOrder("P1-TARGET").orElseThrow().quantity()));
    }

    @Test
    void anApprovedEntryNotSentYetIsNeverSent() {
        long id = desk.proposeAapl();
        // approved, but the executor has not picked it up yet
        QuarkusTransaction.requiringNew().run(() -> service.transition(id, ProposalStatus.APPROVED, AuditActor.USER,
                Desk.OWNER));

        killSwitch.engage(AuditActor.USER, "telegram /kill");
        approvedEvents.fire(new ProposalApproved(id, ProposalKind.ENTRY, AAPL, "AAPL"));
        desk.idle();

        assertEquals("CANCELLED", desk.load(id).status);
        assertTrue(fake.openOrders().isEmpty());
    }

    // =====================================================================================================
    // Daily loss
    // =====================================================================================================

    @Test
    void startOfDayEquityIsRecordedAtTheOpen() {
        clock.set(Instant.parse("2026-11-24T14:30:00Z")); // 09:30 New York

        guard.recordStartOfDay("09:30 open");

        assertEquals("2026-11-24", desk.setting(RiskContextBuilder.START_OF_DAY_DATE));
        assertEquals(0, n("100000.00").compareTo(new BigDecimal(desk.setting(RiskContextBuilder.START_OF_DAY_EQUITY))));
        assertEquals("09:30 open", desk.audit(DailyLossGuard.EVENT_START_OF_DAY).get(0).details.path("source").asText());
    }

    @Test
    void lossAtTheLimitTurnsTheKillSwitchOnOnce() {
        clock.set(Instant.parse("2026-11-24T14:30:00Z"));
        guard.recordStartOfDay("09:30 open");
        clock.set(FixedClock.DEFAULT);
        long id = desk.approvedAapl();

        fake.setCash("USD", n("98000.01")); // -1,999.99: not yet
        assertFalse(guard.check().orElseThrow().tripped());
        assertFalse(killSwitch.isOn());

        fake.setCash("USD", n("98000.00")); // -2,000.00 = -2.0 % of 100,000
        DailyLossGuard.Check c = guard.check().orElseThrow();
        desk.idle();

        assertTrue(c.tripped());
        assertEquals(0, n("-2000.00").compareTo(c.pnl()));
        assertTrue(killSwitch.isOn());
        assertEquals("CANCELLED", desk.load(id).status, "the working entry was cancelled");
        assertEquals(1, desk.audit(DailyLossGuard.EVENT_TRIPPED).size());
        assertEquals(AuditActor.SYSTEM, desk.audit(KillSwitch.EVENT_ON).get(0).actor);
        telegram.awaitSent(s -> s.text().contains("🚨 Daily loss limit reached: today -2000.00 USD (-2.00%"), WAIT);

        // the owner resumes: it does not trip again the same day
        killSwitch.set(false, AuditActor.USER, "telegram /resume");
        fake.setCash("USD", n("97000.00"));
        assertFalse(guard.check().orElseThrow().tripped());
        assertFalse(killSwitch.isOn());
        assertEquals(1, desk.audit(DailyLossGuard.EVENT_TRIPPED).size());
    }

    @Test
    void openPositionsCountAtTheirMarketValue() {
        clock.set(Instant.parse("2026-11-24T14:30:00Z"));
        guard.recordStartOfDay("09:30 open");
        clock.set(FixedClock.DEFAULT);
        desk.filledAapl(); // 100 AAPL at 100.00, marked at last

        Market.quote(fake, "AAPL", "95.50"); // above the stop: unrealized -450 and -1 commission
        DailyLossGuard.Check c = guard.check().orElseThrow();

        assertEquals(0, n("-451.00").compareTo(c.pnl()), c.toString());
        assertFalse(c.tripped());
    }

    @Test
    void serviceStartedAfterTheOpenRecordsTheStartOfDayOnFirstCheck() {
        DailyLossGuard.Check c = guard.check().orElseThrow(); // 11:00 New York, nothing recorded

        assertEquals(0, n("100000.00").compareTo(c.startOfDay()));
        assertTrue(desk.audit(DailyLossGuard.EVENT_START_OF_DAY).get(0).details.path("source").asText()
                .startsWith("first check after the open"));
    }

    @Test
    void beforeTheOpenThereIsNothingToCheck() {
        clock.set(Instant.parse("2026-11-24T13:00:00Z")); // 08:00 New York
        assertTrue(guard.check().isEmpty());
        assertEquals(null, desk.setting(RiskContextBuilder.START_OF_DAY_EQUITY));
    }

}
