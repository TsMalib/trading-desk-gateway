package dev.belis.trader.desk.support;

import dev.belis.trader.desk.broker.FakeBroker;
import dev.belis.trader.desk.proposal.Decision;
import dev.belis.trader.desk.proposal.OrderExecutor;
import dev.belis.trader.desk.proposal.ProposalOutcome;
import dev.belis.trader.desk.proposal.ProposalRequests;
import dev.belis.trader.desk.proposal.ProposalService;
import dev.belis.trader.desk.store.AuditLog;
import dev.belis.trader.desk.store.AuditLogRepository;
import dev.belis.trader.desk.store.BrokerOrder;
import dev.belis.trader.desk.store.BrokerOrderRepository;
import dev.belis.trader.desk.store.Execution;
import dev.belis.trader.desk.store.ExecutionRepository;
import dev.belis.trader.desk.store.PositionMeta;
import dev.belis.trader.desk.store.PositionMetaRepository;
import dev.belis.trader.desk.store.Proposal;
import dev.belis.trader.desk.store.ProposalRepository;
import dev.belis.trader.desk.store.Setting;
import dev.belis.trader.desk.store.SettingRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static dev.belis.trader.desk.support.Market.bd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trading steps and database lookups shared by the reconciler, kill switch and summary tests. AAPL entry
 * 100 / stop 95 / target 112 sizes to 100 shares; the quote starts at 100.20, so the entry waits until the quote
 * moves.
 */
@ApplicationScoped
public class Desk {

    public static final Map<String, Object> OWNER = Map.of("telegramUser", 2);
    public static final Duration WAIT = Duration.ofSeconds(10);

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
    SettingRepository settings;

    public long proposeAapl() {
        ProposalOutcome o = service.proposeEntry(new ProposalRequests.Entry("AAPL", bd("100"), bd("95"), bd("112"),
                "Breakout.", List.of(), false, "grok-test"));
        assertTrue(o.accepted(), o.failures().toString());
        return o.proposalId();
    }

    /** Approved and sent, not filled. */
    public long approvedAapl() {
        long id = proposeAapl();
        assertEquals(Decision.Outcome.APPROVED, service.approve(id, OWNER).outcome());
        idle();
        assertEquals("SUBMITTED", load(id).status);
        return id;
    }

    /** 100 AAPL bought at 100.00, stop 95 and target 112 working. */
    public long filledAapl() {
        long id = approvedAapl();
        Market.quote(fake, "AAPL", "99.90");
        idle();
        assertEquals("FILLED", load(id).status);
        return id;
    }

    public void idle() {
        executor.awaitIdle(WAIT);
    }

    /** Runs {@code task} on the order executor's worker and waits for it and whatever it queued. */
    public void onWorker(Runnable task) {
        executor.submit("test", task).join();
        idle();
    }

    public Proposal load(long id) {
        return QuarkusTransaction.requiringNew().call(() -> proposals.findById(id));
    }

    public PositionMeta meta(long conid) {
        return QuarkusTransaction.requiringNew().call(() -> positionMetas.findById(conid));
    }

    public BrokerOrder row(String ref) {
        return QuarkusTransaction.requiringNew().call(() -> brokerOrders.find("orderRef", ref).firstResult());
    }

    public Execution execution(String execId) {
        return QuarkusTransaction.requiringNew().call(() -> executions.findById(execId));
    }

    public List<Execution> executions() {
        return QuarkusTransaction.requiringNew().call(() -> executions.list("order by executedAt"));
    }

    public List<AuditLog> audit(String event) {
        return QuarkusTransaction.requiringNew().call(() -> auditLogs.list("event = ?1 order by id", event));
    }

    public long auditCount() {
        return QuarkusTransaction.requiringNew().call(() -> auditLogs.count());
    }

    public String setting(String key) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Setting s = settings.findById(key);
            return s == null ? null : s.value;
        });
    }

    public static BigDecimal n(String s) {
        return new BigDecimal(s);
    }
}
