package dev.belis.trader.desk.proposal;

import dev.belis.trader.desk.broker.BracketOrder;
import dev.belis.trader.desk.broker.BrokerOpenOrder;
import dev.belis.trader.desk.broker.FakeBroker;
import dev.belis.trader.desk.store.AuditActor;
import dev.belis.trader.desk.store.AuditLog;
import dev.belis.trader.desk.store.AuditLogRepository;
import dev.belis.trader.desk.store.BrokerOrder;
import dev.belis.trader.desk.store.BrokerOrderRepository;
import dev.belis.trader.desk.store.Execution;
import dev.belis.trader.desk.store.ExecutionRepository;
import dev.belis.trader.desk.store.OrderRole;
import dev.belis.trader.desk.store.PositionMeta;
import dev.belis.trader.desk.store.PositionMetaRepository;
import dev.belis.trader.desk.store.PositionOrigin;
import dev.belis.trader.desk.store.Proposal;
import dev.belis.trader.desk.store.ProposalKind;
import dev.belis.trader.desk.store.ProposalRepository;
import dev.belis.trader.desk.store.Side;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static dev.belis.trader.desk.support.Market.AAPL;
import static dev.belis.trader.desk.support.Market.bd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link OrderExecutor} and the order reconciler against {@link FakeBroker}: from ProposalApproved to a closed
 * position. AAPL entry 100 / stop 95 / target 112 sizes to 100 shares; the quote starts at 100.20 (ask 100.30),
 * so the entry waits until a test moves the quote or fills it.
 */
@QuarkusTest
class OrderExecutorTest {

    static final Map<String, Object> OWNER = Map.of("telegramUser", 2);
    static final Duration WAIT = Duration.ofSeconds(10);

    @Inject
    ProposalService service;

    @Inject
    OrderExecutor executor;

    @Inject
    FakeBroker fake;

    @Inject
    ProposalRepository proposals;

    @Inject
    BrokerOrderRepository brokerOrders;

    @Inject
    ExecutionRepository executions;

    @Inject
    PositionMetaRepository positionMetas;

    @Inject
    AuditLogRepository auditLogs;

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
    // ENTRY: idempotency
    // =====================================================================================================

    @Test
    void doubleApprovalSendsOneBracket() {
        long id = proposeAapl();
        assertEquals(Decision.Outcome.APPROVED, service.approve(id, OWNER).outcome());
        assertEquals(Decision.Outcome.ALREADY_DECIDED, service.approve(id, OWNER).outcome());
        idle();
        // the event itself delivered twice (e.g. a retry): still one bracket
        approvedEvents.fire(new ProposalApproved(id, ProposalKind.ENTRY, AAPL, "AAPL"));
        idle();

        assertEquals("SUBMITTED", load(id).status);
        Map<String, BrokerOpenOrder> open = fake.openOrders().stream()
                .collect(Collectors.toMap(BrokerOpenOrder::orderRef, o -> o));
        assertEquals(java.util.Set.of("P1-ENTRY", "P1-TARGET", "P1-STOP"), open.keySet());

        BrokerOpenOrder entry = open.get("P1-ENTRY");
        BrokerOpenOrder target = open.get("P1-TARGET");
        BrokerOpenOrder stop = open.get("P1-STOP");
        assertBracketLeg(entry, Side.BUY, "LMT", "DAY", 0);
        assertEquals(0, bd("100").compareTo(entry.limitPrice()));
        assertBracketLeg(target, Side.SELL, "LMT", "GTC", entry.orderId());
        assertEquals(0, bd("112").compareTo(target.limitPrice()));
        assertBracketLeg(stop, Side.SELL, "STP", "GTC", entry.orderId());
        assertEquals(0, bd("95").compareTo(stop.auxPrice()));

        List<BrokerOrder> rows = rows(id);
        assertEquals(3, rows.size());
        assertEquals(List.of(entry.orderId(), target.orderId(), stop.orderId()),
                rows.stream().map(r -> r.ibOrderId).toList(), "rows carry the ids the orders were sent with");
        assertEquals(1, audit(id, OrderExecutor.EVENT_DUPLICATE).size());
        assertEquals(1, audit(id, OrderExecutor.EVENT_ORDERS_STORED).size());
    }

    @Test
    void refAlreadyWorkingAtIbkrIsNotSentAgain() {
        long id = proposeAapl();
        fake.placeBracket(new BracketOrder(id, AAPL, bd("100"), bd("100"), bd("112"), bd("95"))); // e.g. by hand

        service.approve(id, OWNER);
        idle();

        assertEquals(3, fake.openOrders().size());
        assertTrue(rows(id).isEmpty());
        AuditLog skip = audit(id, OrderExecutor.EVENT_DUPLICATE).get(0);
        assertEquals("orderRef already working at IBKR", skip.details.path("reason").asText());
    }

    @Test
    void refAlreadyStoredIsNotSent() {
        long id = proposeAapl();
        QuarkusTransaction.requiringNew().run(() -> {
            // stored by a run that died before it could send
            BrokerOrder o = new BrokerOrder();
            o.proposalId = id;
            o.ibOrderId = 77;
            o.orderRef = "P" + id + "-ENTRY";
            o.role = OrderRole.ENTRY;
            o.action = Side.BUY;
            o.orderType = "LMT";
            o.quantity = bd("100");
            o.status = OrderExecutor.PENDING_SUBMIT;
            brokerOrders.persist(o);
        });

        service.approve(id, OWNER);
        idle();

        assertTrue(fake.openOrders().isEmpty());
        assertEquals("orderRef already in broker_order",
                audit(id, OrderExecutor.EVENT_DUPLICATE).get(0).details.path("reason").asText());
    }

    // =====================================================================================================
    // ENTRY: fills
    // =====================================================================================================

    @Test
    void partialFillOfTheParentThenEndOfDay() {
        long id = approvedAapl();

        fake.fill("P1-ENTRY", bd("100.00"), bd("40"));
        idle();

        assertEquals("SUBMITTED", load(id).status, "a partial fill is not FILLED");
        PositionMeta m = meta();
        assertNotNull(m, "the shares bought already need their stop tracked");
        assertEquals(PositionOrigin.GATEWAY, m.origin);
        assertEquals(id, m.proposalId);
        assertEquals(0, bd("100").compareTo(m.entryPrice));
        assertEquals(0, bd("95").compareTo(m.currentStop));
        assertEquals(1, audit(id, OrderExecutor.EVENT_PARTIAL_FILL).size());
        assertEquals("Submitted", row("P1-ENTRY").status);
        telegram.awaitSent(s -> s.text().contains("partial fill, 40 of 100"), WAIT);

        // 15:55 New York: 5 minutes before the close
        clock.set(Instant.parse("2026-11-24T20:55:00Z"));
        executor.submit("eod", executor::endOfDay).join();
        idle();

        Proposal p = load(id);
        assertEquals("FILLED", p.status, "the 40 shares are the position");
        assertEquals("Cancelled", row("P1-ENTRY").status);
        // target and stop sell no more than was bought
        assertEquals(0, bd("40").compareTo(fake.openOrder("P1-STOP").orElseThrow().quantity()));
        assertEquals(0, bd("40").compareTo(fake.openOrder("P1-TARGET").orElseThrow().quantity()));
        assertEquals(0, bd("40").compareTo(row("P1-STOP").quantity));
        assertEquals(2, audit(id, OrderExecutor.EVENT_ORDER_RESIZED).size());
        telegram.awaitSent(s -> s.text().contains("Resized target and stop to 40"), WAIT);

        // and the stop then closes exactly the 40
        Market.quote(fake, "AAPL", "94.90");
        idle();
        assertEquals("CLOSED", load(id).status);
        assertTrue(fake.positions().isEmpty());
    }

    @Test
    void stopFillClosesWithRealizedPnl() {
        long id = approvedAapl();
        Market.quote(fake, "AAPL", "99.90"); // ask 100.00 fills the entry
        idle();

        assertEquals("FILLED", load(id).status);
        PositionMeta m = meta();
        assertEquals(0, bd("100").compareTo(m.entryPrice));
        assertEquals(0, bd("95").compareTo(m.currentStop));
        assertEquals(0, bd("112").compareTo(m.targetPrice));
        assertEquals("Filled", row("P1-ENTRY").status);
        telegram.awaitSent(s -> s.text().contains("#1 AAPL: bought 100 at 100"), WAIT);

        Market.quote(fake, "AAPL", "94.90"); // last <= 95: stop fills at the bid 94.80
        idle();

        assertEquals("CLOSED", load(id).status);
        assertNull(meta(), "position_meta goes with the position");
        assertEquals("Filled", row("P1-STOP").status);
        assertEquals("Cancelled", row("P1-TARGET").status, "IBKR cancelled the sibling, not us");
        assertTrue(fake.positions().isEmpty());
        assertEquals(2, executionsOf(id).size());
        // (94.80 - 100.01 avg cost incl. commission) * 100 - 1.00 commission
        telegram.awaitSent(s -> s.text().contains("closed by STOP") && s.text().contains("-522.00 USD"), WAIT);
        assertNoCriticalAlert();
    }

    @Test
    void targetFillClosesWithRealizedPnl() {
        long id = approvedAapl();
        Market.quote(fake, "AAPL", "99.90");
        idle();

        Market.quote(fake, "AAPL", "112.10"); // bid 112.00 reaches the target
        idle();

        assertEquals("CLOSED", load(id).status);
        assertNull(meta());
        assertEquals("Filled", row("P1-TARGET").status);
        assertEquals("Cancelled", row("P1-STOP").status);
        assertTrue(fake.openOrders().isEmpty());
        AuditLog closed = audit(id, ProposalService.EVENT_STATUS_CHANGED).stream()
                .filter(a -> a.details.path("to").asText().equals("CLOSED")).findFirst().orElseThrow();
        assertEquals("TARGET", closed.details.path("by").asText());
        assertEquals(0, bd("1198").compareTo(closed.details.path("realizedPnl").decimalValue()));
        telegram.awaitSent(s -> s.text().contains("closed by TARGET") && s.text().contains("1198.00 USD"), WAIT);
        assertNoCriticalAlert();
    }

    // =====================================================================================================
    // EXIT
    // =====================================================================================================

    @Test
    void exitWaitsForThePendingCancelOfTheStopThenSellsThePosition() {
        long entry = filledAapl();
        fake.holdCancels(true);

        long exit = approveExit();
        await("stop pending cancel", () -> "PendingCancel".equals(fake.statusOf("P1-STOP")));
        sleep(300);
        assertNull(fake.statusOf("P2-EXIT"), "nothing is sold while the stop could still fire");
        assertEquals("APPROVED", load(exit).status);

        fake.confirmCancels();
        idle();

        assertEquals("CLOSED", load(exit).status);
        assertEquals("CLOSED", load(entry).status);
        assertNull(meta());
        assertTrue(fake.positions().isEmpty());
        BrokerOrder sell = row("P2-EXIT");
        assertEquals("MKT", sell.orderType);
        assertEquals(0, bd("100").compareTo(sell.quantity), "exactly the position IBKR reports");
        assertEquals("Filled", sell.status);
        telegram.awaitSent(s -> s.text().contains("#2 AAPL: sold 100 at market"), WAIT);
        assertNoCriticalAlert();
    }

    @Test
    void stopFillingDuringThePendingCancelMeansNothingIsSold() {
        long entry = filledAapl();
        fake.holdCancels(true);

        long exit = approveExit();
        await("stop pending cancel", () -> "PendingCancel".equals(fake.statusOf("P1-STOP")));
        fake.fill("P1-STOP", bd("95.00")); // it fired before IBKR could cancel it
        idle();

        assertEquals("CANCELLED", load(exit).status);
        assertNull(fake.statusOf("P2-EXIT"), "never sell what the stop already sold");
        assertEquals("CLOSED", load(entry).status);
        assertTrue(fake.positions().isEmpty());
    }

    @Test
    void exitGivesUpWhenTheCancelIsNeverConfirmed() {
        filledAapl();
        fake.holdCancels(true);

        long exit = approveExit();
        idle(); // desk.orders.cancelTimeout is 1s in tests

        assertEquals("FAILED", load(exit).status);
        assertNull(fake.statusOf("P2-EXIT"));
        assertEquals(0, bd("100").compareTo(fake.positions().get(0).quantity()));
        telegram.awaitSent(s -> s.text().contains("did not confirm the cancel of") && s.text().contains("🚨"), WAIT);
    }

    // =====================================================================================================
    // Reconnect
    // =====================================================================================================

    @Test
    void connectionLostRightAfterSendingIsRepairedOnReconnect() {
        long id = proposeAapl();
        fake.dropConnectionAfterNextSend();
        service.approve(id, OWNER);
        idle();

        assertEquals("APPROVED", load(id).status, "sent, but IBKR never confirmed it");
        assertEquals(1, audit(id, OrderExecutor.EVENT_OUTCOME_UNKNOWN).size());
        assertTrue(rows(id).stream().allMatch(r -> r.status.equals(OrderExecutor.PENDING_SUBMIT)));

        Market.quote(fake, "AAPL", "99.90"); // the entry fills while we are disconnected
        idle();
        assertEquals("APPROVED", load(id).status);
        assertTrue(executionsOf(id).isEmpty(), "no events reach a disconnected client");

        fake.simulateReconnect();
        idle();

        assertEquals("FILLED", load(id).status);
        PositionMeta m = meta();
        assertNotNull(m);
        assertEquals(0, bd("100").compareTo(m.entryPrice));
        assertEquals(1, executionsOf(id).size());
        assertEquals("Filled", row("P1-ENTRY").status);
        assertEquals("Submitted", row("P1-TARGET").status);
        assertEquals("Submitted", row("P1-STOP").status);
        assertTrue(audit(id, ProposalService.EVENT_STATUS_CHANGED).stream()
                .anyMatch(a -> a.actor == AuditActor.RECONCILER && a.details.path("to").asText().equals("FILLED")));

        // a second pass changes nothing
        int rowsBefore = auditCount();
        fake.simulateReconnect();
        idle();
        assertEquals(rowsBefore, auditCount());
    }

    @Test
    void fillsMissedWhileDisconnectedCloseThePositionOnReconnect() {
        long id = filledAapl();
        fake.setConnectionState(dev.belis.trader.desk.broker.ConnectionState.DISCONNECTED);
        Market.quote(fake, "AAPL", "94.90"); // the stop fires while we are not connected
        idle();
        assertEquals("FILLED", load(id).status);

        fake.simulateReconnect();
        idle();

        assertEquals("CLOSED", load(id).status);
        assertNull(meta());
        assertEquals("Filled", row("P1-STOP").status);
        assertEquals("Cancelled", row("P1-TARGET").status);
    }

    // =====================================================================================================
    // Rejections
    // =====================================================================================================

    @Test
    void rejectedBracketFails() {
        long id = proposeAapl();
        fake.rejectNext(201, "Order rejected - reason: YOUR ORDER IS NOT ACCEPTED");
        service.approve(id, OWNER);
        idle();

        assertEquals("FAILED", load(id).status);
        assertTrue(fake.openOrders().isEmpty());
        assertTrue(rows(id).stream().allMatch(r -> r.status.equals(OrderExecutor.REJECTED)));
        AuditLog failed = audit(id, ProposalService.EVENT_STATUS_CHANGED).stream()
                .filter(a -> a.details.path("to").asText().equals("FAILED")).findFirst().orElseThrow();
        assertEquals("IBKR 201: Order rejected - reason: YOUR ORDER IS NOT ACCEPTED",
                failed.details.path("error").asText());
        telegram.awaitSent(s -> s.text().contains("FAILED: IBKR 201: Order rejected"), WAIT);
    }

    @Test
    void lateRejectionOfTheStopTakesTheEntryDown() {
        long id = approvedAapl();
        fake.reject("P1-STOP", 201, "Order rejected - reason: stop price too close");
        idle();

        assertEquals("FAILED", load(id).status);
        assertEquals("Cancelled", fake.statusOf("P1-ENTRY"), "no entry without a stop");
        assertTrue(fake.openOrders().isEmpty());
        assertEquals(1, audit(id, OrderExecutor.EVENT_ORDER_ERROR).size());
        telegram.awaitSent(s -> s.text().contains("stop price too close"), WAIT);
    }

    // =====================================================================================================
    // Stop moves
    // =====================================================================================================

    @Test
    void stopUpdateModifiesTheStopInPlace() {
        long entry = filledAapl();
        int stopId = fake.openOrder("P1-STOP").orElseThrow().orderId();
        ProposalOutcome o = service.proposeStopUpdate(new ProposalRequests.StopUpdate("AAPL", bd("98"),
                "Higher low.", "grok-test"));
        assertTrue(o.accepted(), o.failures().toString());

        service.approve(o.proposalId(), OWNER);
        idle();

        BrokerOpenOrder stop = fake.openOrder("P1-STOP").orElseThrow();
        assertEquals(stopId, stop.orderId(), "same order, modified");
        assertEquals(0, bd("98").compareTo(stop.auxPrice()));
        assertEquals("CLOSED", load(o.proposalId()).status);
        assertEquals(0, bd("98").compareTo(meta().currentStop));
        assertEquals(0, bd("98").compareTo(row("P1-STOP").auxPrice));
        assertEquals("FILLED", load(entry).status);
    }

    @Test
    void breakEvenMovesTheStopToEntryOnceAt1R() {
        long id = filledAapl(); // entry 100, stop 95: 1R is 105

        Market.quote(fake, "AAPL", "104.99");
        executor.submit("be", executor::breakEven).join();
        assertEquals(0, bd("95").compareTo(fake.openOrder("P1-STOP").orElseThrow().auxPrice()));
        assertTrue(!meta().breakevenDone);

        Market.quote(fake, "AAPL", "105.00");
        executor.submit("be", executor::breakEven).join();

        assertEquals(0, bd("100").compareTo(fake.openOrder("P1-STOP").orElseThrow().auxPrice()));
        PositionMeta m = meta();
        assertTrue(m.breakevenDone);
        assertEquals(0, bd("100").compareTo(m.currentStop));
        assertEquals(1, audit(id, OrderExecutor.EVENT_BREAKEVEN).size());
        telegram.awaitSent(s -> s.text().contains("Stop moved 95 -&gt; 100 (break-even)"), WAIT);

        executor.submit("be", executor::breakEven).join();
        assertEquals(1, audit(id, OrderExecutor.EVENT_BREAKEVEN).size(), "only once");
    }

    @Test
    void breakEvenWaitsOutsideTheSession() {
        filledAapl();
        Market.quote(fake, "AAPL", "106.00");
        clock.set(Instant.parse("2026-11-24T21:30:00Z")); // 16:30 New York
        executor.submit("be", executor::breakEven).join();
        assertEquals(0, bd("95").compareTo(fake.openOrder("P1-STOP").orElseThrow().auxPrice()));
    }

    // =====================================================================================================
    // End of day
    // =====================================================================================================

    @Test
    void unfilledEntryIsCancelledFiveMinutesBeforeTheClose() {
        long id = approvedAapl();

        clock.set(Instant.parse("2026-11-24T20:54:59Z")); // 15:54:59 New York
        executor.submit("eod", executor::endOfDay).join();
        idle();
        assertEquals("SUBMITTED", load(id).status);

        clock.set(Instant.parse("2026-11-24T20:55:00Z"));
        executor.submit("eod", executor::endOfDay).join();
        idle();

        assertEquals("CANCELLED", load(id).status);
        assertTrue(fake.openOrders().isEmpty(), "the children go with the parent");
        assertEquals(1, audit(id, OrderExecutor.EVENT_EOD_CANCEL).size());
        assertNull(meta());
        assertNoCriticalAlert();
    }

    // =====================================================================================================
    // Helpers
    // =====================================================================================================

    long proposeAapl() {
        ProposalOutcome o = service.proposeEntry(new ProposalRequests.Entry("AAPL", bd("100"), bd("95"), bd("112"),
                "Breakout.", List.of(), false, "grok-test"));
        assertTrue(o.accepted(), o.failures().toString());
        assertEquals(0, bd("100").compareTo(o.quantity()));
        return o.proposalId();
    }

    /** Approved and sent, not filled. */
    long approvedAapl() {
        long id = proposeAapl();
        assertEquals(Decision.Outcome.APPROVED, service.approve(id, OWNER).outcome());
        idle();
        assertEquals("SUBMITTED", load(id).status);
        return id;
    }

    /** 100 AAPL bought at 100.00, stop 95 and target 112 working. */
    long filledAapl() {
        long id = approvedAapl();
        Market.quote(fake, "AAPL", "99.90");
        idle();
        assertEquals("FILLED", load(id).status);
        return id;
    }

    long approveExit() {
        ProposalOutcome o = service.proposeExit(new ProposalRequests.Exit("AAPL", null, "Thesis broken.",
                "grok-test"));
        assertTrue(o.accepted(), o.failures().toString());
        assertEquals(Decision.Outcome.APPROVED, service.approve(o.proposalId(), OWNER).outcome());
        return o.proposalId();
    }

    void idle() {
        executor.awaitIdle(WAIT);
    }

    Proposal load(long id) {
        return QuarkusTransaction.requiringNew().call(() -> proposals.findById(id));
    }

    PositionMeta meta() {
        return QuarkusTransaction.requiringNew().call(() -> positionMetas.findById(AAPL));
    }

    List<BrokerOrder> rows(long id) {
        return QuarkusTransaction.requiringNew().call(() -> brokerOrders.list("proposalId = ?1 order by id", id));
    }

    BrokerOrder row(String ref) {
        return QuarkusTransaction.requiringNew().call(() -> brokerOrders.find("orderRef", ref).firstResult());
    }

    List<Execution> executionsOf(long proposalId) {
        return QuarkusTransaction.requiringNew().call(() -> executions.list(
                "brokerOrderId in (select o.id from BrokerOrder o where o.proposalId = ?1)", proposalId));
    }

    List<AuditLog> audit(long id, String event) {
        return QuarkusTransaction.requiringNew().call(() ->
                auditLogs.list("proposalId = ?1 and event = ?2 order by id", id, event));
    }

    int auditCount() {
        return (int) (long) QuarkusTransaction.requiringNew().call(() -> auditLogs.count());
    }

    void assertNoCriticalAlert() {
        sleep(200); // alerts are sent asynchronously
        assertTrue(telegram.sent().stream().noneMatch(s -> s.text().contains("🚨")),
                () -> "unexpected critical alert: " + telegram.sent());
    }

    static void assertBracketLeg(BrokerOpenOrder o, Side action, String type, String tif, int parentId) {
        assertEquals(action, o.action(), o.orderRef());
        assertEquals(type, o.orderType(), o.orderRef());
        assertEquals(tif, o.tif(), o.orderRef());
        assertEquals(parentId, o.parentId(), o.orderRef());
        assertEquals(0, bd("100").compareTo(o.quantity()), o.orderRef());
    }

    static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for " + what);
            }
            sleep(10);
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
