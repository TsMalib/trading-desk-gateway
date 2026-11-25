package dev.belis.trader.desk.recon;

import dev.belis.trader.desk.broker.Broker;
import dev.belis.trader.desk.broker.BrokerException;
import dev.belis.trader.desk.broker.BrokerExecution;
import dev.belis.trader.desk.broker.BrokerOpenOrder;
import dev.belis.trader.desk.broker.BrokerOrderEvent;
import dev.belis.trader.desk.broker.OrderRefs;
import dev.belis.trader.desk.config.DeskClock;
import dev.belis.trader.desk.config.DeskConfig;
import dev.belis.trader.desk.proposal.OrderExecutor;
import dev.belis.trader.desk.proposal.ProposalService;
import dev.belis.trader.desk.proposal.ProposalStatus;
import dev.belis.trader.desk.store.AuditActor;
import dev.belis.trader.desk.store.AuditWriter;
import dev.belis.trader.desk.store.BrokerOrder;
import dev.belis.trader.desk.store.BrokerOrderRepository;
import dev.belis.trader.desk.store.ExecutionRepository;
import dev.belis.trader.desk.store.OrderRole;
import dev.belis.trader.desk.store.PositionMeta;
import dev.belis.trader.desk.store.PositionMetaRepository;
import dev.belis.trader.desk.store.Proposal;
import dev.belis.trader.desk.store.ProposalKind;
import dev.belis.trader.desk.store.ProposalRepository;
import dev.belis.trader.desk.telegram.AlertService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Brings our orders in line with IBKR, which is the source of truth; the first step of every {@link Reconciler}
 * pass. Events lost while disconnected are rebuilt from what IBKR reports now:
 * <ol>
 *   <li>every execution of our orders since the oldest open proposal is replayed (already-stored ones are
 *       skipped), which moves proposals to FILLED / CLOSED;</li>
 *   <li>the status of each working order is taken from IBKR's open orders; an order sent with an unknown
 *       outcome that IBKR shows makes its proposal SUBMITTED;</li>
 *   <li>an order IBKR no longer shows, that did not fill completely, has ended (an order stored but never seen
 *       by IBKR, after {@code desk.orders.submitGrace}, is NotFound);</li>
 *   <li>a STOP_UPDATE with an unknown outcome is settled from the stop price IBKR shows.</li>
 * </ol>
 * Runs on the order executor's worker, so it never races an order being sent. Only orders with our refs are
 * looked at; positions bought outside this service are not this class's business.
 */
@ApplicationScoped
public class OrderReconciler {

    private static final Logger LOG = Logger.getLogger(OrderReconciler.class);

    public static final String EVENT_MISMATCH = "RECON_MISMATCH";
    public static final String EVENT_DONE = "RECON_ORDERS";

    private static final List<String> OPEN_STATUSES = List.of(ProposalStatus.APPROVED.name(),
            ProposalStatus.SUBMITTED.name(), ProposalStatus.FILLED.name());

    private final Broker broker;
    private final OrderExecutor executor;
    private final ProposalRepository proposals;
    private final BrokerOrderRepository brokerOrders;
    private final PositionMetaRepository positionMetas;
    private final ExecutionRepository executions;
    private final AuditWriter audit;
    private final AlertService alerts;
    private final DeskClock clock;
    private final DeskConfig config;

    public OrderReconciler(Broker broker, OrderExecutor executor, ProposalRepository proposals,
                           BrokerOrderRepository brokerOrders, PositionMetaRepository positionMetas,
                           ExecutionRepository executions, AuditWriter audit, AlertService alerts, DeskClock clock,
                           DeskConfig config) {
        this.broker = broker;
        this.executor = executor;
        this.proposals = proposals;
        this.brokerOrders = brokerOrders;
        this.positionMetas = positionMetas;
        this.executions = executions;
        this.audit = audit;
        this.alerts = alerts;
        this.clock = clock;
        this.config = config;
    }

    /**
     * One pass. Call it on the executor's worker ({@link OrderExecutor#submit}); {@link Reconciler} runs it first
     * in each of its passes.
     */
    public void reconcile() {
        if (!broker.isConnected()) {
            return;
        }
        AuditActor actor = AuditActor.RECONCILER;
        List<Proposal> open = tx(() -> proposals.list("status in ?1 order by id", OPEN_STATUSES));
        if (open.isEmpty()) {
            return;
        }
        Map<Long, Proposal> byId = open.stream().collect(Collectors.toMap(p -> p.id, Function.identity()));
        List<BrokerOrder> rows = tx(() -> brokerOrders.list("proposalId in ?1 order by id", byId.keySet()));

        List<BrokerExecution> execs;
        List<BrokerOpenOrder> working;
        try {
            Instant since = open.stream().map(p -> p.decidedAt != null ? p.decidedAt : p.createdAt)
                    .filter(Objects::nonNull).min(Instant::compareTo).orElse(clock.marketDayStart())
                    .minus(Duration.ofMinutes(1));
            execs = broker.executionsSince(since);
            working = broker.openOrders();
        } catch (BrokerException e) {
            LOG.warnf("Order reconcile skipped: %s", e.getMessage());
            return;
        }

        // 1. fills
        var refs = rows.stream().map(r -> r.orderRef).collect(Collectors.toSet());
        int replayed = 0;
        for (BrokerExecution x : execs) {
            if (x.orderRef() != null && refs.contains(x.orderRef())
                    && tx(() -> executions.findById(x.execId()) == null)) {
                executor.handle(new BrokerOrderEvent.Fill(x), actor);
                replayed++;
            }
        }

        // 2. and 3. order statuses
        Map<String, BrokerOpenOrder> workingByRef = new LinkedHashMap<>();
        for (BrokerOpenOrder o : working) {
            if (o.orderRef() != null) {
                workingByRef.putIfAbsent(o.orderRef(), o);
            }
        }
        Instant now = clock.instant();
        int ended = 0;
        for (BrokerOrder stale : rows) {
            BrokerOrder row = tx(() -> brokerOrders.findById(stale.id));
            BrokerOpenOrder o = workingByRef.get(row.orderRef);
            if (o != null) {
                if (!Objects.equals(o.status(), row.status)) {
                    executor.handle(new BrokerOrderEvent.Status(o.orderId(), o.permId(), o.orderRef(), o.status(),
                            null, null, null, null), actor);
                }
                tx(() -> {
                    executor.markSubmitted(row.proposalId, actor, Map.of("reason", "IBKR shows " + row.orderRef));
                    return null;
                });
                continue;
            }
            if (OrderExecutor.DONE.contains(row.status)) {
                continue;
            }
            BigDecimal filled = tx(() -> executor.filledQuantity(row.id));
            if (filled.compareTo(row.quantity) >= 0) {
                continue;
            }
            boolean neverSeen = OrderExecutor.PENDING_SUBMIT.equals(row.status);
            Instant decided = byId.get(row.proposalId).decidedAt;
            if (neverSeen && decided != null && now.isBefore(decided.plus(config.orders().submitGrace()))) {
                continue; // may still be on its way
            }
            if (row.role == OrderRole.ENTRY && filled.signum() == 0 && unexplainedPosition(row)) {
                continue;
            }
            executor.handle(new BrokerOrderEvent.Status(row.ibOrderId, row.ibPermId == null ? 0 : row.ibPermId,
                    row.orderRef, neverSeen ? OrderExecutor.NOT_FOUND : "Cancelled", filled, null, null,
                    "not in IBKR's open orders"), actor);
            ended++;
        }

        // 4. stop updates with an unknown outcome
        for (Proposal p : open) {
            if (p.kind != ProposalKind.STOP_UPDATE || ProposalService.parse(p.status) != ProposalStatus.SUBMITTED) {
                continue;
            }
            PositionMeta m = tx(() -> positionMetas.findById(p.conid));
            if (m == null || m.proposalId == null) {
                continue;
            }
            String stopRef = OrderRefs.of(m.proposalId, OrderRole.STOP);
            BrokerOpenOrder stop = workingByRef.get(stopRef);
            if (stop != null) {
                executor.resolveStopUpdate(p, stopRef, stop.auxPrice(), actor);
            }
        }

        if (replayed > 0 || ended > 0) {
            int r = replayed;
            int e = ended;
            tx(() -> audit.write(actor, EVENT_DONE, null, Map.of("executionsReplayed", r, "ordersEnded", e,
                    "openProposals", open.size())));
            LOG.infof("Order reconcile: %d execution(s) replayed, %d order(s) ended", r, e);
        }
    }

    /**
     * An unfilled entry IBKR no longer shows, while IBKR reports a position in that instrument that no
     * position_meta explains: it may have filled while IBKR's execution history no longer covers it. Not
     * concluded automatically.
     */
    private boolean unexplainedPosition(BrokerOrder row) {
        Proposal p = tx(() -> proposals.findById(row.proposalId));
        BigDecimal held = broker.positions().stream().filter(x -> x.conid() == p.conid)
                .map(x -> x.quantity()).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        PositionMeta m = tx(() -> positionMetas.findById(p.conid));
        if (held.signum() <= 0 || m != null) {
            return false;
        }
        tx(() -> audit.write(AuditActor.RECONCILER, EVENT_MISMATCH, p.id, Map.of("order", row.orderRef,
                "reason", "entry not working and no fills seen, but IBKR reports a position", "position", held)));
        alerts.warning("#" + p.id + ": " + row.orderRef + " is not working at IBKR and no fill was seen, but IBKR "
                + "reports " + held.stripTrailingZeros().toPlainString() + " shares of that instrument. "
                + "Check it by hand; the proposal is left as it is.");
        return true;
    }

    private static <T> T tx(java.util.function.Supplier<T> body) {
        return QuarkusTransaction.joiningExisting().call(body::get);
    }
}
