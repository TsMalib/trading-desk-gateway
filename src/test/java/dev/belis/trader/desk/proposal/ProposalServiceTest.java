package dev.belis.trader.desk.proposal;

import dev.belis.trader.desk.broker.FakeBroker;
import dev.belis.trader.desk.store.AuditActor;
import dev.belis.trader.desk.store.AuditLog;
import dev.belis.trader.desk.store.AuditLogRepository;
import dev.belis.trader.desk.store.Proposal;
import dev.belis.trader.desk.store.ProposalRepository;
import dev.belis.trader.desk.support.CreatedEvents;
import dev.belis.trader.desk.support.FixedClock;
import dev.belis.trader.desk.support.Market;
import dev.belis.trader.desk.support.TestDb;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static dev.belis.trader.desk.proposal.ProposalStatus.*;
import static dev.belis.trader.desk.support.Market.bd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ProposalServiceTest {

    @Inject
    ProposalService service;

    @Inject
    FakeBroker fake;

    @Inject
    ProposalRepository proposals;

    @Inject
    AuditLogRepository auditLogs;

    @Inject
    TestDb db;

    @Inject
    CreatedEvents events;

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

    ProposalOutcome proposeAapl() {
        return service.proposeEntry(new ProposalRequests.Entry("AAPL", bd("100"), bd("95"), bd("112"),
                "Breakout above the 50-day high on volume.", List.of("https://example.com/aapl"), false, "grok-test"));
    }

    Proposal load(long id) {
        return QuarkusTransaction.requiringNew().call(() -> proposals.findById(id));
    }

    List<AuditLog> audit(long proposalId) {
        return QuarkusTransaction.requiringNew().call(() ->
                auditLogs.list("proposalId = ?1 order by id", proposalId));
    }

    @Test
    void mainPathWritesAuditForEveryTransition() {
        long id = proposeAapl().proposalId();
        assertEquals("PROPOSED", load(id).status);

        service.transition(id, APPROVED, AuditActor.USER, Map.of("telegramUser", 2));
        service.transition(id, SUBMITTED, AuditActor.SYSTEM, null);
        service.transition(id, FILLED, AuditActor.RECONCILER, null);
        service.transition(id, CLOSED, AuditActor.RECONCILER, Map.of("exit", "target"));

        Proposal p = load(id);
        assertEquals("CLOSED", p.status);
        assertEquals(FixedClock.DEFAULT, p.decidedAt);

        List<AuditLog> rows = audit(id);
        // TELEGRAM_SENT: the proposal message went to the (mocked) Telegram chat
        assertEquals(List.of(ProposalService.EVENT_CREATED, ProposalService.EVENT_TELEGRAM_SENT, "STATUS_CHANGED",
                "STATUS_CHANGED", "STATUS_CHANGED", "STATUS_CHANGED"), rows.stream().map(a -> a.event).toList());
        assertEquals(List.of(AuditActor.GROK, AuditActor.SYSTEM, AuditActor.USER, AuditActor.SYSTEM,
                AuditActor.RECONCILER, AuditActor.RECONCILER), rows.stream().map(a -> a.actor).toList());
        AuditLog approved = rows.get(2);
        assertEquals("PROPOSED", approved.details.get("from").asText());
        assertEquals("APPROVED", approved.details.get("to").asText());
        assertEquals("ENTRY", approved.details.get("kind").asText());
        assertEquals(2, approved.details.get("telegramUser").asInt());
        assertEquals("target", rows.get(5).details.get("exit").asText());
    }

    @Test
    void illegalTransitionThrowsAndWritesNothing() {
        long id = proposeAapl().proposalId();
        int before = audit(id).size();

        IllegalTransitionException e = assertThrows(IllegalTransitionException.class,
                () -> service.transition(id, SUBMITTED, AuditActor.SYSTEM, null));
        assertEquals("PROPOSED", e.from());
        assertEquals(SUBMITTED, e.to());
        assertThrows(IllegalTransitionException.class, () -> service.transition(id, FILLED, AuditActor.SYSTEM, null));
        assertThrows(IllegalTransitionException.class, () -> service.transition(id, CLOSED, AuditActor.SYSTEM, null));

        assertEquals("PROPOSED", load(id).status);
        assertNull(load(id).decidedAt);
        assertEquals(before, audit(id).size());
    }

    @Test
    void terminalStatesCannotBeLeft() {
        long id = proposeAapl().proposalId();
        service.transition(id, REJECTED_USER, AuditActor.USER, null);
        for (ProposalStatus to : ProposalStatus.values()) {
            assertThrows(IllegalTransitionException.class, () -> service.transition(id, to, AuditActor.USER, null),
                    "REJECTED_USER -> " + to);
        }
    }

    @Test
    void sideStates() {
        long a = proposeAapl().proposalId();
        service.transition(a, APPROVED, AuditActor.USER, null);
        service.transition(a, REJECTED_RISK, AuditActor.SYSTEM, Map.of("failed", List.of("DRIFT")));
        assertEquals("REJECTED_RISK", load(a).status);

        long b = proposeAapl().proposalId();
        service.transition(b, APPROVED, AuditActor.USER, null);
        service.transition(b, SUBMITTED, AuditActor.SYSTEM, null);
        service.transition(b, FAILED, AuditActor.SYSTEM, Map.of("error", "rejected by IBKR"));
        assertEquals("FAILED", load(b).status);

        long c = proposeAapl().proposalId();
        service.transition(c, CANCELLED, AuditActor.USER, null);
        assertEquals("CANCELLED", load(c).status);
    }

    @Test
    void unknownProposalOrStatus() {
        assertThrows(NoSuchElementException.class, () -> service.transition(999, APPROVED, AuditActor.USER, null));

        long id = proposeAapl().proposalId();
        QuarkusTransaction.requiringNew().run(() -> proposals.findById(id).status = "SOMETHING");
        assertThrows(IllegalTransitionException.class, () -> service.transition(id, APPROVED, AuditActor.USER, null));
    }

    @Test
    void expiry() {
        ProposalOutcome o = proposeAapl();
        assertEquals(FixedClock.DEFAULT.plus(Duration.ofMinutes(30)), o.expiresAt());
        assertEquals(0, service.expireDue());

        clock.set(o.expiresAt());
        // Not yet marked EXPIRED by the job, but too late to approve.
        assertThrows(IllegalTransitionException.class,
                () -> service.transition(o.proposalId(), APPROVED, AuditActor.USER, null));

        assertEquals(1, service.expireDue());
        Proposal p = load(o.proposalId());
        assertEquals("EXPIRED", p.status);
        assertEquals(o.expiresAt(), p.decidedAt);
        AuditLog last = audit(o.proposalId()).getLast();
        assertEquals(AuditActor.SYSTEM, last.actor);
        assertEquals("EXPIRED", last.details.get("to").asText());
        assertEquals(0, service.expireDue());
    }

    @Test
    void rejectedAtProposalTimeIsTerminalAndFiresNoEvent() {
        ProposalOutcome o = service.proposeEntry(new ProposalRequests.Entry("AAPL", bd("100"), bd("99.5"), bd("101"),
                "Too tight.", List.of(), false, "grok-test"));
        assertEquals(REJECTED_RISK, o.status());
        assertFalse(o.accepted());
        assertTrue(events.all().isEmpty());
        Proposal p = load(o.proposalId());
        assertEquals("REJECTED_RISK", p.status);
        assertNull(p.expiresAt);
        assertNotNull(p.decidedAt);
        assertThrows(IllegalTransitionException.class,
                () -> service.transition(o.proposalId(), APPROVED, AuditActor.USER, null));
    }

    @Test
    void pendingEntryBlocksADuplicateUntilDecided() {
        long first = proposeAapl().proposalId();
        ProposalOutcome dup = proposeAapl();
        assertEquals(REJECTED_RISK, dup.status());
        assertEquals(List.of("DUPLICATE_SYMBOL"), dup.failures().stream().map(ProposalCheck::name).toList());

        service.transition(first, REJECTED_USER, AuditActor.USER, null);
        assertEquals(PROPOSED, proposeAapl().status());
    }
}
