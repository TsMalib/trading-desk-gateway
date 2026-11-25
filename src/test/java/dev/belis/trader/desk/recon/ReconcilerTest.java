package dev.belis.trader.desk.recon;

import dev.belis.trader.desk.broker.BrokerExecution;
import dev.belis.trader.desk.broker.ConnectionState;
import dev.belis.trader.desk.broker.FakeBroker;
import dev.belis.trader.desk.proposal.ProposalService;
import dev.belis.trader.desk.store.AuditActor;
import dev.belis.trader.desk.store.AuditLog;
import dev.belis.trader.desk.store.Execution;
import dev.belis.trader.desk.store.PositionMeta;
import dev.belis.trader.desk.store.PositionOrigin;
import dev.belis.trader.desk.store.Side;
import dev.belis.trader.desk.support.Desk;
import dev.belis.trader.desk.support.FixedClock;
import dev.belis.trader.desk.support.Market;
import dev.belis.trader.desk.support.RecordingTelegram;
import dev.belis.trader.desk.support.TestDb;
import dev.belis.trader.desk.telegram.AlertService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.belis.trader.desk.support.Desk.WAIT;
import static dev.belis.trader.desk.support.Desk.n;
import static dev.belis.trader.desk.support.Market.AAPL;
import static dev.belis.trader.desk.support.Market.MSFT;
import static dev.belis.trader.desk.support.Market.TSLA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Reconciler} against {@link FakeBroker}: one test per kind of mismatch between IBKR and the database. */
@QuarkusTest
class ReconcilerTest {

    @Inject
    Reconciler reconciler;

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
        Market.open(fake, FixedClock.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        db.clean();
        fake.reset();
    }

    void reconcile() {
        desk.onWorker(reconciler::reconcile);
    }

    // =====================================================================================================
    // Executions unknown to the database
    // =====================================================================================================

    @Test
    void missedFillOfOurOrderIsLinkedByOrderRefAndMovesTheProposal() {
        long id = desk.approvedAapl();
        fake.setConnectionState(ConnectionState.DISCONNECTED);
        Market.quote(fake, "AAPL", "99.90"); // fills while no events reach us
        fake.setConnectionState(ConnectionState.CONNECTED);
        desk.idle();
        assertEquals("SUBMITTED", desk.load(id).status);
        assertTrue(desk.executions().isEmpty());

        reconcile();

        assertEquals("FILLED", desk.load(id).status);
        List<Execution> stored = desk.executions();
        assertEquals(1, stored.size());
        assertEquals(desk.row("P1-ENTRY").id, stored.get(0).brokerOrderId, "linked to its broker_order by ref");
        PositionMeta m = desk.meta(AAPL);
        assertEquals(PositionOrigin.GATEWAY, m.origin);
        assertEquals(id, m.proposalId);
        assertTrue(desk.audit("EXECUTION").stream().allMatch(a -> a.actor == AuditActor.RECONCILER));
        assertTrue(desk.audit(ProposalService.EVENT_STATUS_CHANGED).stream()
                .anyMatch(a -> a.actor == AuditActor.RECONCILER && "FILLED".equals(a.details.path("to").asText())));
    }

    @Test
    void executionWithoutOurOrderRefIsStoredAsExternal() {
        BrokerExecution x = fake.externalFill(MSFT, Side.BUY, n("10"), n("400"));

        reconcile();

        Execution e = desk.execution(x.execId());
        assertNotNull(e);
        assertNull(e.brokerOrderId, "EXTERNAL: no broker_order");
        assertEquals(0, n("400").compareTo(e.price));
        AuditLog a = desk.audit(Reconciler.EVENT_EXECUTION_EXTERNAL).get(0);
        assertEquals(AuditActor.RECONCILER, a.actor);
        assertEquals(x.execId(), a.details.path("execId").asText());
        telegram.awaitSent(s -> s.text().contains("External trade at IBKR, not placed by this service: BUY 10 MSFT"),
                WAIT);

        // the position it made is EXTERNAL too, opened at that execution
        PositionMeta m = desk.meta(MSFT);
        assertEquals(PositionOrigin.EXTERNAL, m.origin);
        assertEquals(x.executedAt(), m.openedAt);
    }

    @Test
    void executionsAreStoredOnce() {
        fake.externalFill(MSFT, Side.BUY, n("10"), n("400"));
        reconcile();
        long rows = desk.auditCount();
        int sent = telegram.sent().size();

        reconcile();

        assertEquals(1, desk.executions().size());
        assertEquals(rows, desk.auditCount(), "a pass with no difference writes nothing");
        assertEquals(sent, telegram.sent().size());
        assertNotNull(desk.setting(Reconciler.LAST_RUN));
    }

    // =====================================================================================================
    // Positions
    // =====================================================================================================

    @Test
    void unknownPositionBecomesExternalAlertsOnceAndIsNeverTouched() {
        fake.setPosition(TSLA, n("3"), n("240"));

        reconcile();
        reconcile();

        PositionMeta m = desk.meta(TSLA);
        assertEquals(PositionOrigin.EXTERNAL, m.origin);
        assertNull(m.proposalId);
        assertEquals(0, n("240").compareTo(m.entryPrice));
        assertEquals(1, desk.audit(Reconciler.EVENT_POSITION_EXTERNAL).size());
        telegram.awaitSent(s -> s.text().contains("TSLA: IBKR reports 3 shares at 240 that this service did not buy"),
                WAIT);
        sleep(200);
        assertEquals(1, telegram.sent().stream().filter(s -> s.text().contains("did not buy")).count(), "once");
        assertTrue(fake.openOrders().isEmpty(), "no order was placed for it");
        assertTrue(desk.audit(Reconciler.EVENT_NO_STOP).isEmpty(), "an EXTERNAL position is not policed");
    }

    @Test
    void positionOfAnEntryInFlightIsNotTakenForExternal() {
        desk.approvedAapl();
        fake.setPosition(AAPL, n("100"), n("100")); // IBKR's position update before the fill event reached us

        reconcile();

        assertNull(desk.meta(AAPL));
        assertTrue(desk.audit(Reconciler.EVENT_POSITION_EXTERNAL).isEmpty());
    }

    @Test
    void gatewayPositionWithoutAWorkingStopAlertsImmediately() {
        long id = desk.filledAapl();
        fake.cancelSilently("P1-STOP"); // e.g. cancelled by hand in TWS

        reconcile();

        AuditLog a = desk.audit(Reconciler.EVENT_NO_STOP).get(0);
        assertEquals(id, a.proposalId);
        assertEquals(AuditActor.RECONCILER, a.actor);
        telegram.awaitSent(s -> s.text().contains("🚨") && s.text().contains("#1 AAPL: IBKR shows 100 shares with NO "
                + "working stop order"), WAIT);
    }

    @Test
    void gatewayPositionWithItsStopRaisesNothing() {
        desk.filledAapl();
        long rows = desk.auditCount();

        reconcile();

        assertEquals(rows, desk.auditCount());
        sleep(200);
        assertTrue(telegram.sent().stream().noneMatch(s -> s.text().contains("🚨")), telegram.sent().toString());
    }

    @Test
    void shortPositionAlerts() {
        fake.setPosition(TSLA, n("-5"), n("250"));

        reconcile();

        assertEquals(1, desk.audit(Reconciler.EVENT_SHORT).size());
        assertNull(desk.meta(TSLA));
        telegram.awaitSent(s -> s.text().contains("SHORT position of -5"), WAIT);
    }

    // =====================================================================================================
    // Database thinks a position is open, IBKR shows none
    // =====================================================================================================

    @Test
    void gatewayPositionSoldByHandIsClosedFromTheExternalExecution() {
        long id = desk.filledAapl();
        fake.externalFill(AAPL, Side.SELL, n("100"), n("103")); // sold in TWS; our stop and target still work

        reconcile();

        assertEquals("CLOSED", desk.load(id).status);
        assertNull(desk.meta(AAPL));
        assertEquals("Cancelled", fake.statusOf("P1-STOP"), "a stop without a position must not sell");
        assertEquals("Cancelled", fake.statusOf("P1-TARGET"));
        assertTrue(fake.openOrders().isEmpty());

        AuditLog gone = desk.audit(Reconciler.EVENT_POSITION_GONE).get(0);
        assertEquals(id, gone.proposalId);
        assertEquals(0, n("100").compareTo(gone.details.path("soldQuantity").decimalValue()));
        AuditLog closed = desk.audit(ProposalService.EVENT_STATUS_CHANGED).stream()
                .filter(a -> "CLOSED".equals(a.details.path("to").asText())).findFirst().orElseThrow();
        // IBKR's realized P&L: (103 - 100.01 avg cost) x 100 - 1 commission
        assertEquals(0, n("298").compareTo(closed.details.path("realizedPnl").decimalValue()),
                closed.details.toString());
        telegram.awaitSent(s -> s.text().contains("#1 AAPL: IBKR shows no position any more")
                && s.text().contains("Marked CLOSED") && s.text().contains("sold 100 at 103"), WAIT);
    }

    @Test
    void gatewayPositionGoneWithoutAnyExecutionIsClosedWithUnknownPnl() {
        long id = desk.filledAapl();
        // days later: IBKR's history no longer has the fills, the position and its orders are gone
        fake.forgetExecutions();
        fake.cancelSilently("P1-STOP");
        fake.cancelSilently("P1-TARGET");
        fake.setPosition(AAPL, n("0"), n("0"));
        fake.externalFill(MSFT, Side.BUY, n("1"), n("400")); // unrelated, keeps the pass honest

        reconcile();

        assertEquals("CLOSED", desk.load(id).status);
        assertNull(desk.meta(AAPL));
        assertEquals(1, desk.audit(Reconciler.EVENT_POSITION_GONE).stream()
                .filter(a -> a.proposalId != null && a.proposalId == id).count());
        telegram.awaitSent(s -> s.text().contains("No SELL execution is known for it, so the P&amp;L is unknown"), WAIT);
    }

    @Test
    void externalPositionThatIsGoneIsForgotten() {
        fake.setPosition(TSLA, n("3"), n("240"));
        reconcile();
        assertNotNull(desk.meta(TSLA));

        fake.setPosition(TSLA, n("0"), n("0"));
        reconcile();

        assertNull(desk.meta(TSLA));
        AuditLog a = desk.audit(Reconciler.EVENT_POSITION_GONE).get(0);
        assertEquals("EXTERNAL", a.details.path("origin").asText());
        telegram.awaitSent(s -> s.text().contains("TSLA: the EXTERNAL position is no longer reported"), WAIT);
    }

    // =====================================================================================================
    // Quantity changed without an execution
    // =====================================================================================================

    @Test
    void splitWithoutExecutionAlertsAndFlagsTheUncoveredStop() {
        long id = desk.filledAapl();
        fake.corporateAction(AAPL, n("200"), n("50.005"), null); // 2:1, IBKR left the orders at 100

        reconcile();

        AuditLog q = desk.audit(Reconciler.EVENT_QUANTITY_CHANGED).get(0);
        assertEquals(id, q.proposalId);
        assertEquals(0, n("200").compareTo(q.details.path("brokerQuantity").decimalValue()));
        assertEquals(0, n("100").compareTo(q.details.path("executionQuantity").decimalValue()));
        telegram.awaitSent(s -> s.text().contains("IBKR reports 200 shares but the executions add up to 100"), WAIT);
        telegram.awaitSent(s -> s.text().contains("🚨") && s.text().contains("P1-STOP is for 100 shares but IBKR "
                + "reports 200. Part of the position has NO stop"), WAIT);
        assertEquals(2, desk.audit(Reconciler.EVENT_ORDER_QUANTITY).size(), "stop and target");
        assertEquals(0, n("100").compareTo(fake.openOrder("P1-STOP").orElseThrow().quantity()), "never raised");

        // the same quantities are not reported again
        reconcile();
        assertEquals(1, desk.audit(Reconciler.EVENT_QUANTITY_CHANGED).size());
    }

    @Test
    void reverseSplitLowersStopAndTargetToThePosition() {
        desk.filledAapl();
        fake.corporateAction(AAPL, n("50"), n("200.02"), null); // 1:2, orders still for 100

        reconcile();

        assertEquals(0, n("50").compareTo(fake.openOrder("P1-STOP").orElseThrow().quantity()));
        assertEquals(0, n("50").compareTo(fake.openOrder("P1-TARGET").orElseThrow().quantity()));
        assertEquals(0, n("50").compareTo(desk.row("P1-STOP").quantity));
        assertEquals(1, desk.audit(Reconciler.EVENT_QUANTITY_CHANGED).size());
        assertTrue(desk.audit(Reconciler.EVENT_ORDER_QUANTITY).stream()
                .allMatch(a -> "lowered to the position".equals(a.details.path("action").asText())));
        telegram.awaitSent(s -> s.text().contains("P1-STOP was for 100 shares but the position is 50; lowered to 50"),
                WAIT);
    }

    @Test
    void splitThatIbkrAppliedToTheOrdersOnlyAlertsOnce() {
        desk.filledAapl();
        fake.corporateAction(AAPL, n("200"), n("50.005"), n("200"));

        reconcile();
        reconcile();

        assertEquals(1, desk.audit(Reconciler.EVENT_QUANTITY_CHANGED).size());
        assertTrue(desk.audit(Reconciler.EVENT_ORDER_QUANTITY).isEmpty(), "stop and target match the position");
        sleep(200);
        assertTrue(telegram.sent().stream().noneMatch(s -> s.text().contains("🚨")), telegram.sent().toString());
    }

    // =====================================================================================================
    // Triggers
    // =====================================================================================================

    @Test
    void reconnectRunsAFullPass() {
        fake.setPosition(TSLA, n("3"), n("240"));

        fake.simulateReconnect();
        desk.idle();

        assertEquals(PositionOrigin.EXTERNAL, desk.meta(TSLA).origin);
    }

    @Test
    void nothingHappensWhileDisconnected() {
        fake.setPosition(TSLA, n("3"), n("240"));
        fake.setConnectionState(ConnectionState.DISCONNECTED);

        reconcile();

        assertNull(desk.meta(TSLA));
        assertNull(desk.setting(Reconciler.LAST_RUN));
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
