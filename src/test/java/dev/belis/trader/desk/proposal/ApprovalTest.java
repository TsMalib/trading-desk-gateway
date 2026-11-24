package dev.belis.trader.desk.proposal;

import dev.belis.trader.desk.broker.BracketOrder;
import dev.belis.trader.desk.broker.ConnectionState;
import dev.belis.trader.desk.broker.FakeBroker;
import dev.belis.trader.desk.store.AuditActor;
import dev.belis.trader.desk.store.AuditLog;
import dev.belis.trader.desk.store.AuditLogRepository;
import dev.belis.trader.desk.store.Proposal;
import dev.belis.trader.desk.store.ProposalRepository;
import dev.belis.trader.desk.support.DecisionEvents;
import dev.belis.trader.desk.support.FixedClock;
import dev.belis.trader.desk.support.Market;
import dev.belis.trader.desk.support.TestDb;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static dev.belis.trader.desk.proposal.Decision.Outcome.*;
import static dev.belis.trader.desk.support.Market.AAPL;
import static dev.belis.trader.desk.support.Market.bd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ProposalService#approve} and {@link ProposalService#reject}: the owner's decision and the re-check. */
@QuarkusTest
class ApprovalTest {

    static final Map<String, Object> OWNER = Map.of("telegramUser", 2);

    @Inject
    ProposalService service;

    @Inject
    KillSwitch killSwitch;

    @Inject
    FakeBroker fake;

    @Inject
    ProposalRepository proposals;

    @Inject
    AuditLogRepository auditLogs;

    @Inject
    DecisionEvents events;

    @Inject
    TestDb db;

    FixedClock clock;

    @BeforeEach
    void setUp() {
        db.clean();
        events.clear();
        clock = FixedClock.install();
        Market.open(fake, FixedClock.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        db.clean();
        fake.reset();
    }

    long proposeAapl() {
        ProposalOutcome o = service.proposeEntry(new ProposalRequests.Entry("AAPL", bd("100"), bd("95"), bd("112"),
                "Breakout.", List.of(), false, "grok-test"));
        assertTrue(o.accepted(), o.failures().toString());
        return o.proposalId();
    }

    Proposal load(long id) {
        return QuarkusTransaction.requiringNew().call(() -> proposals.findById(id));
    }

    List<AuditLog> audit(long id) {
        return QuarkusTransaction.requiringNew().call(() -> auditLogs.list("proposalId = ?1 order by id", id));
    }

    /** AAPL 10 shares filled at 100.50 with a working stop at 90 and target at 120. */
    void aaplPosition() {
        fake.placeBracket(new BracketOrder(900, AAPL, bd("10"), bd("100.50"), bd("120"), bd("90")));
    }

    // ---- ENTRY ------------------------------------------------------------------------------------------

    @Test
    void entryApprovedWhenEverythingStillPasses() {
        long id = proposeAapl();
        Decision d = service.approve(id, OWNER);

        assertEquals(APPROVED, d.outcome());
        assertEquals("APPROVED", d.status());
        assertTrue(d.failures().isEmpty());
        assertEquals(0, bd("100").compareTo(d.quantity()));
        assertEquals(List.of(new ProposalApproved(id, dev.belis.trader.desk.store.ProposalKind.ENTRY, AAPL, "AAPL")),
                events.approved());

        Proposal p = load(id);
        assertEquals(FixedClock.DEFAULT, p.decidedAt);
        assertTrue(p.riskResult.path("approval").path("passed").asBoolean());
        assertEquals(18, p.riskResult.path("approval").path("checks").size(), "every entry rule ran again");
        assertEquals("100", p.riskResult.path("quantity").asText(), "proposal-time values are kept");
    }

    @Test
    void entryQuantityOnlyGoesDown() {
        long id = proposeAapl();
        fake.setCash("USD", new BigDecimal("99000.00")); // risk budget 495 -> 99 shares

        Decision d = service.approve(id, OWNER);

        assertEquals(APPROVED, d.outcome());
        assertEquals(0, bd("99").compareTo(d.quantity()));
        Proposal p = load(id);
        assertEquals(0, bd("99").compareTo(p.quantity));
        assertEquals(0, bd("495.00").compareTo(p.riskEur));
        assertTrue(audit(id).stream().anyMatch(a -> a.event.equals(ProposalService.EVENT_QUANTITY_REDUCED)));

        long id2;
        db.clean();
        Market.open(fake, FixedClock.DEFAULT);
        id2 = proposeAapl();
        fake.setCash("USD", new BigDecimal("101000.00")); // would allow 101: still 100
        assertEquals(0, bd("100").compareTo(service.approve(id2, OWNER).quantity()));
    }

    @Test
    void killSwitchAtApprovalRejects() {
        long id = proposeAapl();
        killSwitch.set(true, AuditActor.USER, "test");

        Decision d = service.approve(id, OWNER);

        assertEquals(REJECTED_RISK, d.outcome());
        assertEquals(List.of("KILL_SWITCH"), d.failures().stream().map(ProposalCheck::name).toList());
        assertEquals("REJECTED_RISK", load(id).status);
        assertTrue(events.approved().isEmpty());
        List<AuditLog> rows = audit(id).stream().filter(a -> a.event.equals("STATUS_CHANGED")).toList();
        assertEquals(List.of(AuditActor.USER, AuditActor.SYSTEM), rows.stream().map(a -> a.actor).toList());
        assertEquals("re-check at approval failed", rows.get(1).details.get("reason").asText());
    }

    @Test
    void brokerDownChangesNothing() {
        long id = proposeAapl();
        fake.setConnectionState(ConnectionState.DISCONNECTED);

        Decision d = service.approve(id, OWNER);

        assertEquals(UNAVAILABLE, d.outcome());
        assertEquals("IBKR is not connected right now. Try again later.", d.message());
        Proposal p = load(id);
        assertEquals("PROPOSED", p.status);
        assertEquals(null, p.decidedAt);
        assertTrue(audit(id).stream().anyMatch(a -> a.event.equals(ProposalService.EVENT_APPROVAL_UNAVAILABLE)
                && a.actor == AuditActor.USER));
    }

    @Test
    void decidedOnlyOnce() {
        long id = proposeAapl();
        assertEquals(REJECTED_USER, service.reject(id, OWNER).outcome());
        Decision again = service.approve(id, OWNER);
        assertEquals(ALREADY_DECIDED, again.outcome());
        assertEquals("REJECTED_USER", again.status());
        assertEquals(ALREADY_DECIDED, service.reject(id, OWNER).outcome());
        assertEquals(NOT_FOUND, service.approve(999_999, OWNER).outcome());
        assertEquals(NOT_FOUND, service.reject(999_999, OWNER).outcome());
    }

    @Test
    void pastExpiryExpires() {
        ProposalOutcome o = service.proposeEntry(new ProposalRequests.Entry("AAPL", bd("100"), bd("95"),
                bd("112"), "Breakout.", List.of(), false, "grok-test"));
        clock.set(o.expiresAt());

        assertEquals(EXPIRED, service.approve(o.proposalId(), OWNER).outcome());
        assertEquals("EXPIRED", load(o.proposalId()).status);
        assertEquals(List.of(new ProposalExpired(o.proposalId())), events.expired());
    }

    @Test
    void expireDueFiresExpired() {
        ProposalOutcome o = service.proposeEntry(new ProposalRequests.Entry("AAPL", bd("100"), bd("95"),
                bd("112"), "Breakout.", List.of(), false, "grok-test"));
        clock.set(o.expiresAt());
        assertEquals(1, service.expireDue());
        assertEquals(List.of(new ProposalExpired(o.proposalId())), events.expired());
    }

    // ---- EXIT -------------------------------------------------------------------------------------------

    @Test
    void exitApprovedWhileThePositionIsThere() {
        aaplPosition();
        long id = service.proposeExit(new ProposalRequests.Exit("AAPL", null, "Done.", "grok-test")).proposalId();
        Decision d = service.approve(id, OWNER);
        assertEquals(APPROVED, d.outcome(), d.failures().toString());
        assertEquals(List.of("POSITION", "SELL_QUANTITY", "NO_OPEN_EXIT"),
                d.checks().stream().map(ProposalCheck::name).toList());
    }

    @Test
    void exitRejectedWhenThePositionShrank() {
        aaplPosition();
        long id = service.proposeExit(new ProposalRequests.Exit("AAPL", null, "Done.", "grok-test")).proposalId();
        fake.setPosition(AAPL, bd("5"), bd("100.50"));

        Decision d = service.approve(id, OWNER);

        assertEquals(REJECTED_RISK, d.outcome());
        assertEquals(List.of("SELL_QUANTITY"), d.failures().stream().map(ProposalCheck::name).toList());
        assertFalse(load(id).riskResult.path("approval").path("passed").asBoolean(true));
    }

    // ---- STOP_UPDATE ------------------------------------------------------------------------------------

    @Test
    void stopUpdateRecheckedAgainstAFreshQuote() {
        aaplPosition();
        long ok = service.proposeStopUpdate(new ProposalRequests.StopUpdate("AAPL", bd("95"), "Trail.",
                "grok-test")).proposalId();
        assertEquals(APPROVED, service.approve(ok, OWNER).outcome());

        db.clean();
        Market.open(fake, FixedClock.DEFAULT);
        aaplPosition();
        long id = service.proposeStopUpdate(new ProposalRequests.StopUpdate("AAPL", bd("95"), "Trail.",
                "grok-test")).proposalId();
        Market.quote(fake, "AAPL", "94.00"); // the new stop would now be above the price

        Decision d = service.approve(id, OWNER);

        assertEquals(REJECTED_RISK, d.outcome());
        assertEquals(List.of("STOP_BELOW_LAST"), d.failures().stream().map(ProposalCheck::name).toList());
    }
}
