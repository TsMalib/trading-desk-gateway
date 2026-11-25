package dev.belis.trader.desk.proposal;

import dev.belis.trader.desk.broker.BracketOrder;
import dev.belis.trader.desk.broker.Broker;
import dev.belis.trader.desk.broker.BrokerException;
import dev.belis.trader.desk.broker.BrokerExecution;
import dev.belis.trader.desk.broker.BrokerOpenOrder;
import dev.belis.trader.desk.broker.BrokerOrderEvent;
import dev.belis.trader.desk.broker.BrokerPosition;
import dev.belis.trader.desk.broker.BrokerTimeoutException;
import dev.belis.trader.desk.broker.OrderRefs;
import dev.belis.trader.desk.broker.PlacedBracket;
import dev.belis.trader.desk.config.DeskClock;
import dev.belis.trader.desk.config.DeskConfig;
import dev.belis.trader.desk.store.AuditActor;
import dev.belis.trader.desk.store.AuditWriter;
import dev.belis.trader.desk.store.BrokerOrder;
import dev.belis.trader.desk.store.BrokerOrderRepository;
import dev.belis.trader.desk.store.Execution;
import dev.belis.trader.desk.store.ExecutionRepository;
import dev.belis.trader.desk.store.Instrument;
import dev.belis.trader.desk.store.InstrumentRepository;
import dev.belis.trader.desk.store.OrderRole;
import dev.belis.trader.desk.store.PositionMeta;
import dev.belis.trader.desk.store.PositionMetaRepository;
import dev.belis.trader.desk.store.PositionOrigin;
import dev.belis.trader.desk.store.Proposal;
import dev.belis.trader.desk.store.ProposalKind;
import dev.belis.trader.desk.store.ProposalRepository;
import dev.belis.trader.desk.store.Side;
import dev.belis.trader.desk.telegram.AlertService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Sends the orders of approved proposals and follows them until the position is closed. The only code, besides
 * the owner's /flatten, that writes to the broker.
 *
 * <p>Everything runs on one worker thread ({@code order-executor}): {@link ProposalApproved}, every
 * {@link BrokerOrderEvent}, the break-even and end-of-day jobs and the reconciler. So two of them never race, and
 * a slow IBKR call never holds up Telegram or the IBKR reader.
 *
 * <ul>
 *   <li>ENTRY: bracket P&lt;id&gt;-ENTRY/-TARGET/-STOP. Skipped (and audited) when that ref is already in
 *       broker_order or working at IBKR. The three broker_order rows are stored before anything is sent.
 *       Complete parent fill: FILLED and position_meta (GATEWAY). Target or stop fill: CLOSED, alert with the
 *       realized P&amp;L; IBKR cancels the sibling.</li>
 *   <li>EXIT: cancels our target and stop for the instrument, waits until IBKR confirms, then sells exactly the
 *       position IBKR reports at market (P&lt;id&gt;-EXIT).</li>
 *   <li>STOP_UPDATE: moves the trigger of the working P&lt;entry&gt;-STOP in place, up only.</li>
 * </ul>
 *
 * <p>Fills move proposals; order statuses only end them (cancelled, inactive). Every change writes audit_log.
 * IBKR rejections: FAILED with the error text in audit_log and an alert.
 */
@ApplicationScoped
public class OrderExecutor {

    private static final Logger LOG = Logger.getLogger(OrderExecutor.class);

    public static final String EVENT_DUPLICATE = "ORDER_DUPLICATE_SKIPPED";
    public static final String EVENT_ORDERS_STORED = "ORDERS_STORED";
    public static final String EVENT_OUTCOME_UNKNOWN = "ORDER_OUTCOME_UNKNOWN";
    public static final String EVENT_REJECTED = "ORDER_REJECTED";
    public static final String EVENT_ORDER_ERROR = "ORDER_ERROR";
    public static final String EVENT_ORDER_STATUS = "ORDER_STATUS";
    public static final String EVENT_ORDER_RESIZED = "ORDER_RESIZED";
    public static final String EVENT_EXECUTION = "EXECUTION";
    public static final String EVENT_POSITION_OPENED = "POSITION_OPENED";
    public static final String EVENT_POSITION_CLOSED = "POSITION_CLOSED";
    public static final String EVENT_PARTIAL_FILL = "PARTIAL_FILL";
    public static final String EVENT_EXIT_CANCELLING = "EXIT_CANCELLING";
    public static final String EVENT_STOP_MOVED = "STOP_MOVED";
    public static final String EVENT_BREAKEVEN = "BREAKEVEN_STOP";
    public static final String EVENT_EOD_CANCEL = "EOD_CANCEL_REQUESTED";
    public static final String EVENT_UNPROTECTED = "POSITION_UNPROTECTED";
    public static final String EVENT_STATE_MISMATCH = "STATE_MISMATCH";
    public static final String EVENT_KILL_CANCEL = "KILL_SWITCH_CANCEL_ENTRY";

    /** broker_order.status before IBKR has seen the order (also an IBKR status name). */
    public static final String PENDING_SUBMIT = "PendingSubmit";
    /** broker_order.status: IBKR refused the write call. */
    public static final String REJECTED = "Rejected";
    /** broker_order.status: the reconciler found no trace of the order at IBKR. */
    public static final String NOT_FOUND = "NotFound";
    public static final String FILLED = "Filled";
    public static final String PENDING_CANCEL = "PendingCancel";

    /** IBKR statuses (and ours) of an order that ended without filling completely. */
    public static final Set<String> ENDED = Set.of("Cancelled", "ApiCancelled", "Inactive", NOT_FOUND, REJECTED);
    /** Statuses a broker_order row never leaves. */
    public static final Set<String> DONE = Set.of(FILLED, "Cancelled", "ApiCancelled", "Inactive", NOT_FOUND, REJECTED);

    /** IBKR error codes about a cancel request, not about the order: logged, the order stays as it is. */
    private static final Set<Integer> CANCEL_ERRORS = Set.of(161, 10147, 10148);

    private static final long POLL_MS = 100;

    private final Broker broker;
    private final ProposalService proposalService;
    private final ProposalRepository proposals;
    private final BrokerOrderRepository brokerOrders;
    private final ExecutionRepository executions;
    private final PositionMetaRepository positionMetas;
    private final InstrumentRepository instruments;
    private final AuditWriter audit;
    private final AlertService alerts;
    private final DeskClock clock;
    private final DeskConfig config;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            r -> Thread.ofPlatform().daemon().name("order-executor").unstarted(r));
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile Thread workerThread;
    /** Refs we asked IBKR to cancel: their cancellation is expected, not an alarm. */
    private final Set<String> expectedCancels = ConcurrentHashMap.newKeySet();

    public OrderExecutor(Broker broker, ProposalService proposalService, ProposalRepository proposals,
                         BrokerOrderRepository brokerOrders, ExecutionRepository executions,
                         PositionMetaRepository positionMetas, InstrumentRepository instruments, AuditWriter audit,
                         AlertService alerts, DeskClock clock, DeskConfig config) {
        this.broker = broker;
        this.proposalService = proposalService;
        this.proposals = proposals;
        this.brokerOrders = brokerOrders;
        this.executions = executions;
        this.positionMetas = positionMetas;
        this.instruments = instruments;
        this.audit = audit;
        this.alerts = alerts;
        this.clock = clock;
        this.config = config;
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    // =====================================================================================================
    // Worker
    // =====================================================================================================

    /** Runs {@code task} on the worker. A failure is logged and alerted; the future never fails. */
    public CompletableFuture<Void> submit(String what, Runnable task) {
        inFlight.incrementAndGet();
        try {
            return CompletableFuture.runAsync(() -> {
                workerThread = Thread.currentThread();
                try {
                    task.run();
                } catch (RuntimeException e) {
                    LOG.errorf(e, "%s failed", what);
                    alerts.critical(what + " failed: " + e.getMessage());
                } finally {
                    inFlight.decrementAndGet();
                }
            }, worker);
        } catch (RuntimeException e) {
            inFlight.decrementAndGet();
            throw e;
        }
    }

    /** Waits until the worker has nothing left to do, including work queued by the work itself (tests). */
    public void awaitIdle(Duration timeout) {
        if (Thread.currentThread() == workerThread) {
            return;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (inFlight.get() > 0) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("order executor still busy after " + timeout);
            }
            sleep(5);
        }
    }

    void onApproved(@Observes ProposalApproved e) {
        submit("Order for proposal #" + e.proposalId(), () -> execute(e.proposalId()));
    }

    void onOrderEvent(@Observes BrokerOrderEvent e) {
        submit("Order event " + e, () -> handle(e, AuditActor.SYSTEM));
    }

    /** Remembers that we are about to cancel these refs (EXIT, flatten), so their cancellation is no alarm. */
    public void expectCancel(Collection<String> refs) {
        expectedCancels.addAll(refs);
    }

    // =====================================================================================================
    // Approved proposals
    // =====================================================================================================

    /** Sends the order(s) of an APPROVED proposal. Runs on the worker. */
    void execute(long id) {
        Proposal p = proposalService.find(id).orElse(null);
        if (p == null) {
            LOG.warnf("ProposalApproved for unknown proposal %d", id);
            return;
        }
        if (ProposalService.parse(p.status) != ProposalStatus.APPROVED) {
            skip(id, null, "proposal is " + p.status + ", not APPROVED");
            return;
        }
        switch (p.kind) {
            case ENTRY -> executeEntry(p);
            case EXIT -> executeExit(p);
            case STOP_UPDATE -> executeStopUpdate(p);
        }
    }

    // ---- ENTRY --------------------------------------------------------------------------------------------

    private void executeEntry(Proposal p) {
        long id = p.id;
        BracketOrder b = new BracketOrder(id, p.conid, p.quantity, p.entryLimit, p.targetPrice, p.stopPrice);
        String symbol = symbol(p.conid);
        if (alreadyExists(id, b.entryRef())) {
            return;
        }
        PlacedBracket ids;
        try {
            ids = new PlacedBracket(broker.nextOrderId(), broker.nextOrderId(), broker.nextOrderId());
        } catch (BrokerException e) {
            fail(id, null, "IBKR not available to send the order: " + e.getMessage(), e, symbol, false);
            return;
        }

        List<BrokerOrder> rows = new ArrayList<>();
        boolean stored = tx(() -> {
            if (!stillApproved(id) || hasRef(b.entryRef())) {
                return false;
            }
            rows.add(newRow(id, ids.entryOrderId(), b.entryRef(), OrderRole.ENTRY, Side.BUY, "LMT", b.quantity(),
                    b.entryLimit(), null));
            rows.add(newRow(id, ids.targetOrderId(), b.targetRef(), OrderRole.TARGET, Side.SELL, "LMT", b.quantity(),
                    b.targetPrice(), null));
            rows.add(newRow(id, ids.stopOrderId(), b.stopRef(), OrderRole.STOP, Side.SELL, "STP", b.quantity(),
                    null, b.stopPrice()));
            Map<String, Object> d = details("symbol", symbol, "quantity", b.quantity(), "entryLimit", b.entryLimit(),
                    "target", b.targetPrice(), "stop", b.stopPrice());
            d.put("orders", rows.stream().map(r -> r.orderRef + "=" + r.ibOrderId).toList());
            audit.write(AuditActor.SYSTEM, EVENT_ORDERS_STORED, id, d);
            return true;
        });
        if (!stored) {
            skip(id, b.entryRef(), "changed while the orders were being stored");
            return;
        }

        try {
            broker.placeBracket(b, ids);
        } catch (BrokerTimeoutException e) {
            outcomeUnknown(id, b.entryRef(), symbol, e);
            return;
        } catch (BrokerException | IllegalArgumentException e) {
            rejected(id, List.of(b.entryRef(), b.targetRef(), b.stopRef()), symbol, e);
            cancelWorking(List.of(b.entryRef(), b.targetRef(), b.stopRef()));
            return;
        }
        tx(() -> {
            markSubmitted(id, AuditActor.SYSTEM, details("orders", List.of(b.entryRef(), b.targetRef(), b.stopRef()),
                    "ibOrderIds", List.of(ids.entryOrderId(), ids.targetOrderId(), ids.stopOrderId())));
            return null;
        });
        LOG.infof("Proposal %d: bracket sent (%s BUY %s LMT %s, target %s, stop %s)", id, symbol,
                plain(b.quantity()), plain(b.entryLimit()), plain(b.targetPrice()), plain(b.stopPrice()));
    }

    // ---- EXIT ---------------------------------------------------------------------------------------------

    private void executeExit(Proposal p) {
        long id = p.id;
        long conid = p.conid;
        String symbol = symbol(conid);
        String exitRef = OrderRefs.of(id, OrderRole.EXIT);
        if (alreadyExists(id, exitRef)) {
            return;
        }
        List<BrokerOpenOrder> open;
        BigDecimal held;
        try {
            open = broker.openOrders();
            held = held(conid);
        } catch (BrokerException e) {
            fail(id, null, "IBKR not available for the exit: " + e.getMessage(), e, symbol, false);
            return;
        }
        if (held.signum() <= 0) {
            cancelProposal(id, "IBKR reports no " + symbol + " position any more", symbol);
            return;
        }
        if (p.quantity != null && p.quantity.compareTo(held) < 0) {
            fail(id, null, "partial exits are not supported: selling " + plain(p.quantity) + " of " + plain(held)
                    + " would leave the rest without a stop. Nothing was cancelled or sold.", null, symbol, false);
            return;
        }
        List<BrokerOpenOrder> external = open.stream()
                .filter(o -> o.conid() == conid && o.action() == Side.SELL && OrderRefs.parse(o.orderRef()).isEmpty())
                .toList();
        if (!external.isEmpty()) {
            fail(id, null, "a SELL order not placed by this service is working for " + symbol + " ("
                    + external.stream().map(o -> o.orderType() + " " + plain(o.quantity())).collect(Collectors.joining(", "))
                    + "); a market sell on top could sell more than the position. Nothing was sold.", null, symbol,
                    false);
            return;
        }
        List<String> toCancel = open.stream()
                .filter(o -> o.conid() == conid && o.action() == Side.SELL)
                .filter(o -> OrderRefs.parse(o.orderRef()).map(r -> r.role() == OrderRole.TARGET
                        || r.role() == OrderRole.STOP).orElse(false))
                .map(BrokerOpenOrder::orderRef).distinct().toList();

        if (!toCancel.isEmpty()) {
            tx(() -> audit.write(AuditActor.SYSTEM, EVENT_EXIT_CANCELLING, id,
                    details("symbol", symbol, "cancel", toCancel, "position", held)));
            expectCancel(toCancel);
            for (String ref : toCancel) {
                try {
                    broker.cancel(ref);
                } catch (BrokerException e) {
                    LOG.infof("Exit %d: cancel %s: %s", id, ref, e.getMessage()); // already gone is fine
                }
            }
            List<String> still = awaitNotWorking(toCancel, config.orders().cancelTimeout());
            if (!still.isEmpty()) {
                fail(id, null, "IBKR did not confirm the cancel of " + String.join(", ", still) + " within "
                        + config.orders().cancelTimeout().toSeconds() + " s. Nothing was sold. Check the "
                        + symbol + " orders in TWS: the stop may or may not still be working.", null, symbol, true);
                return;
            }
        }

        BigDecimal qty;
        int orderId;
        try {
            qty = held(conid); // what IBKR reports now: a stop may have filled while cancelling
            if (qty.signum() <= 0) {
                cancelProposal(id, "the " + symbol + " position was closed while its orders were being cancelled",
                        symbol);
                return;
            }
            orderId = broker.nextOrderId();
        } catch (BrokerException e) {
            fail(id, null, "IBKR not available after cancelling the target and stop: " + e.getMessage()
                    + ". Nothing was sold and " + symbol + " has NO stop now.", e, symbol, true);
            return;
        }
        boolean stored = tx(() -> {
            if (!stillApproved(id) || hasRef(exitRef)) {
                return false;
            }
            BrokerOrder row = newRow(id, orderId, exitRef, OrderRole.EXIT, Side.SELL, "MKT", qty, null, null);
            audit.write(AuditActor.SYSTEM, EVENT_ORDERS_STORED, id,
                    details("symbol", symbol, "quantity", qty, "orders", List.of(row.orderRef + "=" + orderId)));
            return true;
        });
        if (!stored) {
            skip(id, exitRef, "changed while the order was being stored");
            return;
        }
        try {
            broker.marketSell(conid, qty, exitRef, orderId);
        } catch (BrokerTimeoutException e) {
            outcomeUnknown(id, exitRef, symbol, e);
            return;
        } catch (BrokerException | IllegalArgumentException e) {
            rejected(id, List.of(exitRef), symbol, e);
            alerts.critical("#" + id + " " + symbol + ": the exit was rejected and the target and stop are already "
                    + "cancelled. " + symbol + " (" + plain(qty) + ") has NO stop. Sell it in TWS or try /flatten.");
            return;
        }
        tx(() -> {
            markSubmitted(id, AuditActor.SYSTEM, details("orders", List.of(exitRef), "ibOrderId", orderId,
                    "quantity", qty));
            return null;
        });
    }

    // ---- STOP_UPDATE --------------------------------------------------------------------------------------

    private void executeStopUpdate(Proposal p) {
        long id = p.id;
        String symbol = symbol(p.conid);
        BigDecimal newStop = p.stopPrice;
        Optional<String> stopRef = gatewayStopRef(p.conid);
        BrokerOpenOrder stop;
        try {
            List<BrokerOpenOrder> open = broker.openOrders();
            stop = stopRef.flatMap(ref -> open.stream().filter(o -> ref.equals(o.orderRef())).findFirst())
                    .orElse(null);
        } catch (BrokerException e) {
            fail(id, null, "IBKR not available to move the stop: " + e.getMessage(), e, symbol, false);
            return;
        }
        if (stop == null) {
            fail(id, null, "no working stop placed by this service for " + symbol
                    + " (stops placed by hand are never changed)", null, symbol, false);
            return;
        }
        if (stop.auxPrice() != null && newStop.compareTo(stop.auxPrice()) <= 0) {
            fail(id, null, "stops only move up: IBKR has " + plain(stop.auxPrice()) + ", proposed "
                    + plain(newStop), null, symbol, false);
            return;
        }
        BigDecimal from = stop.auxPrice();
        tx(() -> {
            markSubmitted(id, AuditActor.SYSTEM, details("order", stop.orderRef(), "from", from, "to", newStop));
            return null;
        });
        try {
            broker.modifyStop(stop.orderRef(), newStop);
        } catch (BrokerTimeoutException e) {
            outcomeUnknown(id, stop.orderRef(), symbol, e);
            return;
        } catch (BrokerException | IllegalArgumentException e) {
            fail(id, stop.orderRef(), errorText(e), e, symbol, false);
            return;
        }
        stopMoved(p.conid, stop.orderRef(), from, newStop, id, AuditActor.SYSTEM, false);
        tx(() -> proposalService.transition(id, ProposalStatus.CLOSED, AuditActor.SYSTEM,
                details("order", stop.orderRef(), "stop", newStop)));
        alerts.info("#" + id + " " + symbol + ": stop moved " + plain(from) + " -> " + plain(newStop) + ".");
    }

    /** The stop moved at IBKR: broker_order.aux_price, position_meta.current_stop and an audit row. */
    void stopMoved(long conid, String stopRef, BigDecimal from, BigDecimal to, Long proposalId, AuditActor actor,
                   boolean breakEven) {
        tx(() -> {
            brokerOrders.find("orderRef", stopRef).firstResultOptional().ifPresent(r -> r.auxPrice = to);
            PositionMeta m = positionMetas.findById(conid);
            if (m != null) {
                m.currentStop = to;
                if (breakEven) {
                    m.breakevenDone = true;
                }
            }
            audit.write(actor, breakEven ? EVENT_BREAKEVEN : EVENT_STOP_MOVED, proposalId,
                    details("order", stopRef, "from", from, "to", to));
            return null;
        });
    }

    // =====================================================================================================
    // Broker events
    // =====================================================================================================

    /** Applies one broker event. Idempotent: the reconciler replays events. Runs on the worker. */
    public void handle(BrokerOrderEvent e, AuditActor actor) {
        switch (e) {
            case BrokerOrderEvent.Fill f -> onFill(f.execution(), actor);
            case BrokerOrderEvent.Status s -> onStatus(s, actor);
            case BrokerOrderEvent.Error er -> onError(er, actor);
        }
    }

    // ---- fills --------------------------------------------------------------------------------------------

    private record FillResult(BrokerOrder row, String alert, boolean critical, Runnable after) {
    }

    private void onFill(BrokerExecution x, AuditActor actor) {
        FillResult r = tx(() -> {
            BrokerOrder row = findRow(x.orderRef(), x.orderId());
            if (row == null) {
                return null; // not ours
            }
            Execution known = executions.findById(x.execId());
            if (known != null) {
                if (known.commission == null && x.commission() != null) {
                    known.commission = x.commission();
                }
                if (known.realizedPnl == null && x.realizedPnl() != null) {
                    known.realizedPnl = x.realizedPnl();
                }
                return null; // already applied
            }
            Execution n = new Execution();
            n.ibExecId = x.execId();
            n.brokerOrderId = row.id;
            n.conid = x.conid();
            n.side = x.side();
            n.quantity = x.quantity();
            n.price = x.price();
            n.currency = x.currency();
            n.commission = x.commission();
            n.realizedPnl = x.realizedPnl();
            n.executedAt = x.executedAt();
            executions.persist(n);
            executions.flush();
            if (row.ibPermId == null && x.permId() != 0) {
                row.ibPermId = x.permId();
            }
            audit.write(actor, EVENT_EXECUTION, row.proposalId, details("order", row.orderRef, "execId", x.execId(),
                    "side", x.side(), "quantity", x.quantity(), "price", x.price(), "currency", x.currency(),
                    "commission", x.commission(), "commissionCurrency", x.commissionCurrency(),
                    "realizedPnl", x.realizedPnl()));
            BigDecimal filled = filledQuantity(row.id);
            boolean complete = filled.compareTo(row.quantity) >= 0;
            if (complete) {
                setStatus(row, FILLED, actor, details("filled", filled));
            }
            return switch (row.role) {
                case ENTRY -> entryFilled(row, filled, complete, actor);
                case TARGET, STOP -> complete
                        ? closed(row.proposalId, row, x.commissionCurrency(), actor)
                        : partialExitFill(row, filled, actor);
                case EXIT -> complete
                        ? exitFilled(row, x.commissionCurrency(), actor)
                        : partialExitFill(row, filled, actor);
            };
        });
        if (r != null) {
            if (r.alert() != null) {
                if (r.critical()) {
                    alerts.critical(r.alert());
                } else {
                    alerts.info(r.alert());
                }
            }
            if (r.after() != null) {
                r.after().run();
            }
        }
    }

    /** In the fill's transaction: position_meta, and FILLED once the parent is complete. */
    private FillResult entryFilled(BrokerOrder row, BigDecimal filled, boolean complete, AuditActor actor) {
        Proposal p = proposals.findById(row.proposalId);
        String symbol = symbol(p.conid);
        ProposalStatus st = ProposalService.parse(p.status);
        if (st == ProposalStatus.APPROVED) {
            markSubmitted(p.id, actor, details("reason", "IBKR reports a fill", "order", row.orderRef));
            st = ProposalStatus.SUBMITTED;
        }
        List<Execution> fills = fillsOf(row.id);
        BigDecimal avg = averagePrice(fills);
        Instant first = fills.stream().map(f -> f.executedAt).min(Instant::compareTo).orElse(clock.instant());

        PositionMeta m = positionMetas.findById(p.conid);
        if (m == null) {
            m = new PositionMeta();
            m.conid = p.conid;
            m.proposalId = p.id;
            m.origin = PositionOrigin.GATEWAY;
            m.currentStop = p.stopPrice;
            m.targetPrice = p.targetPrice;
            m.breakevenDone = false;
            m.openedAt = first;
            m.entryPrice = avg;
            positionMetas.persist(m);
            audit.write(actor, EVENT_POSITION_OPENED, p.id, details("conid", p.conid, "symbol", symbol,
                    "origin", m.origin, "entryPrice", avg, "currentStop", m.currentStop, "target", m.targetPrice,
                    "quantity", filled));
        } else if (Objects.equals(m.proposalId, p.id)) {
            BigDecimal was = m.entryPrice;
            m.entryPrice = avg;
            if (was == null || was.compareTo(avg) != 0) {
                audit.write(actor, "POSITION_UPDATED", p.id, details("conid", p.conid, "entryPrice", avg,
                        "was", was, "quantity", filled));
            }
        } else {
            audit.write(actor, EVENT_STATE_MISMATCH, p.id, details("reason",
                    "position_meta for this instrument belongs to " + m.origin + " proposal " + m.proposalId,
                    "conid", p.conid));
            return new FillResult(row, "#" + p.id + " " + symbol + ": bought, but position_meta already belongs to "
                    + m.origin + (m.proposalId == null ? "" : " #" + m.proposalId) + ". Check /positions.", true, null);
        }

        if (!complete) {
            audit.write(actor, EVENT_PARTIAL_FILL, p.id, details("order", row.orderRef, "filled", filled,
                    "of", row.quantity, "avgPrice", avg));
            return new FillResult(row, "#" + p.id + " " + symbol + ": partial fill, " + plain(filled) + " of "
                    + plain(row.quantity) + " bought at " + plain(avg) + " avg.", false, null);
        }
        if (st != ProposalStatus.SUBMITTED) {
            audit.write(actor, EVENT_STATE_MISMATCH, p.id, details("reason", "entry filled while proposal is "
                    + p.status, "order", row.orderRef));
            return new FillResult(row, "#" + p.id + " " + symbol + ": IBKR filled the entry (" + plain(filled)
                    + " at " + plain(avg) + ") but the proposal is " + p.status + ". Check /positions.", true, null);
        }
        proposalService.transition(p.id, ProposalStatus.FILLED, actor, details("order", row.orderRef,
                "quantity", filled, "avgPrice", avg));
        return new FillResult(row, "#" + p.id + " " + symbol + ": bought " + plain(filled) + " at " + plain(avg)
                + ". Stop " + plain(p.stopPrice) + ", target " + plain(p.targetPrice) + ".", false, null);
    }

    private FillResult partialExitFill(BrokerOrder row, BigDecimal filled, AuditActor actor) {
        audit.write(actor, EVENT_PARTIAL_FILL, row.proposalId, details("order", row.orderRef, "filled", filled,
                "of", row.quantity));
        return new FillResult(row, null, false, null);
    }

    /** In the fill's transaction: the target or stop of entry {@code entryId} filled completely. */
    private FillResult closed(long entryId, BrokerOrder row, String pnlCurrency, AuditActor actor) {
        Proposal p = proposals.findById(entryId);
        String symbol = symbol(p.conid);
        PositionMeta m = positionMetas.findById(p.conid);
        BigDecimal entryPrice = m == null ? null : m.entryPrice;
        Pnl pnl = pnl(fillsOf(row.id), entryPrice, pnlCurrency, p.conid);
        closeEntry(p, row.role.name(), pnl, actor);
        List<Execution> fills = fillsOf(row.id);
        return new FillResult(row, "#" + p.id + " " + symbol + " closed by " + row.role + ": sold "
                + plain(row.quantity) + " at " + plain(averagePrice(fills)) + ". Realized P&L: " + pnl.text() + ".",
                false, null);
    }

    /** In the fill's transaction: the EXIT order filled completely. */
    private FillResult exitFilled(BrokerOrder row, String pnlCurrency, AuditActor actor) {
        Proposal exit = proposals.findById(row.proposalId);
        String symbol = symbol(exit.conid);
        PositionMeta m = positionMetas.findById(exit.conid);
        Pnl pnl = pnl(fillsOf(row.id), m == null ? null : m.entryPrice, pnlCurrency, exit.conid);
        ProposalStatus st = ProposalService.parse(exit.status);
        if (st == ProposalStatus.APPROVED) {
            markSubmitted(exit.id, actor, details("reason", "IBKR reports a fill", "order", row.orderRef));
            st = ProposalStatus.SUBMITTED;
        }
        if (st == ProposalStatus.SUBMITTED) {
            proposalService.transition(exit.id, ProposalStatus.FILLED, actor, details("order", row.orderRef,
                    "quantity", row.quantity, "realizedPnl", pnl.amount(), "currency", pnl.currency()));
            proposalService.transition(exit.id, ProposalStatus.CLOSED, actor, details("reason", "position sold"));
        }
        if (m != null && m.proposalId != null) {
            Proposal entry = proposals.findById(m.proposalId);
            if (entry != null) {
                closeEntry(entry, "EXIT #" + exit.id, pnl, actor);
            }
        } else if (m != null) {
            deleteMeta(m, exit.id, "EXIT #" + exit.id, actor);
        }
        return new FillResult(row, "#" + exit.id + " " + symbol + ": sold " + plain(row.quantity) + " at market, "
                + plain(averagePrice(fillsOf(row.id))) + ". Realized P&L: " + pnl.text() + ".", false, null);
    }

    /** ENTRY proposal -> CLOSED (through FILLED if needed) and its position_meta deleted. */
    private void closeEntry(Proposal p, String by, Pnl pnl, AuditActor actor) {
        ProposalStatus st = ProposalService.parse(p.status);
        if (st == ProposalStatus.APPROVED) {
            markSubmitted(p.id, actor, details("reason", "IBKR reports fills"));
            st = ProposalStatus.SUBMITTED;
        }
        if (st == ProposalStatus.SUBMITTED) {
            proposalService.transition(p.id, ProposalStatus.FILLED, actor, details("reason",
                    "position closed before the entry was complete"));
            st = ProposalStatus.FILLED;
        }
        if (st == ProposalStatus.FILLED) {
            proposalService.transition(p.id, ProposalStatus.CLOSED, actor, details("by", by,
                    "realizedPnl", pnl.amount(), "currency", pnl.currency(), "estimated", pnl.estimated()));
        }
        PositionMeta m = positionMetas.findById(p.conid);
        if (m != null && Objects.equals(m.proposalId, p.id)) {
            deleteMeta(m, p.id, by, actor);
        }
    }

    private void deleteMeta(PositionMeta m, Long proposalId, String by, AuditActor actor) {
        audit.write(actor, EVENT_POSITION_CLOSED, proposalId, details("conid", m.conid, "by", by,
                "origin", m.origin, "entryPrice", m.entryPrice, "currentStop", m.currentStop,
                "breakevenDone", m.breakevenDone, "openedAt", m.openedAt));
        positionMetas.delete(m);
    }

    /** Realized P&amp;L of these SELL fills: IBKR's when every fill has it, else an estimate. */
    record Pnl(BigDecimal amount, String currency, boolean estimated) {
        String text() {
            if (amount == null) {
                return "not reported by IBKR yet";
            }
            String s = amount.setScale(2, RoundingMode.HALF_UP).toPlainString() + " " + currency;
            return estimated ? "about " + s + " (estimate, IBKR has not reported it yet)" : s;
        }
    }

    private Pnl pnl(List<Execution> sells, BigDecimal entryPrice, String reportedCurrency, long conid) {
        if (!sells.isEmpty() && sells.stream().allMatch(e -> e.realizedPnl != null)) {
            return new Pnl(sells.stream().map(e -> e.realizedPnl).reduce(BigDecimal.ZERO, BigDecimal::add),
                    reportedCurrency != null ? reportedCurrency : sells.get(0).currency, false);
        }
        if (entryPrice == null || sells.isEmpty()) {
            return new Pnl(null, null, true);
        }
        BigDecimal est = BigDecimal.ZERO;
        for (Execution e : sells) {
            est = est.add(e.price.subtract(entryPrice).multiply(e.quantity));
            if (e.commission != null) {
                est = est.subtract(e.commission);
            }
        }
        return new Pnl(est, sells.get(0).currency, true);
    }

    // ---- statuses -----------------------------------------------------------------------------------------

    private void onStatus(BrokerOrderEvent.Status s, AuditActor actor) {
        BrokerOrder row = tx(() -> {
            BrokerOrder r = findRow(s.orderRef(), s.orderId());
            if (r == null) {
                return null;
            }
            if (r.ibPermId == null && s.permId() != 0) {
                r.ibPermId = s.permId();
            }
            if (DONE.contains(r.status) || Objects.equals(r.status, s.status())) {
                return null; // a row never leaves DONE; IBKR repeats statuses
            }
            if (FILLED.equals(s.status())) {
                // fills move things; a Filled status before its executions only shows in the row
                setStatus(r, s.status(), actor, details("filled", s.filled(), "avgFillPrice", s.avgFillPrice()));
                return null;
            }
            setStatus(r, s.status(), actor, details("filled", s.filled(), "remaining", s.remaining(),
                    "whyHeld", blankToNull(s.whyHeld())));
            return ENDED.contains(s.status()) ? r : null;
        });
        if (row != null) {
            orderEnded(row, s.status(), actor);
        }
    }

    /** {@code row} ended at IBKR without filling completely (cancelled, inactive, not found). */
    void orderEnded(BrokerOrder row, String status, AuditActor actor) {
        String ref = row.orderRef;
        boolean expected = expectedCancels.remove(ref);
        BigDecimal filled = tx(() -> filledQuantity(row.id));
        Proposal p = proposalService.find(row.proposalId).orElse(null);
        if (p == null) {
            return;
        }
        String symbol = symbol(p.conid);
        ProposalStatus st = ProposalService.parse(p.status);
        boolean live = st == ProposalStatus.APPROVED || st == ProposalStatus.SUBMITTED;
        String why = switch (status) {
            case "Inactive" -> "IBKR set " + ref + " Inactive";
            case NOT_FOUND -> "IBKR has no trace of " + ref;
            default -> "IBKR cancelled " + ref;
        };

        switch (row.role) {
            case ENTRY -> {
                if (!live) {
                    return; // already FAILED by the error, or ended otherwise
                }
                if (filled.signum() == 0) {
                    boolean failed = "Inactive".equals(status) || (NOT_FOUND.equals(status)
                            && st == ProposalStatus.APPROVED);
                    tx(() -> proposalService.transition(p.id, failed ? ProposalStatus.FAILED : ProposalStatus.CANCELLED,
                            actor, details("reason", why + " before anything filled", "order", ref)));
                    alerts.info("#" + p.id + " " + symbol + ": entry " + (failed ? "failed" : "cancelled")
                            + ", nothing bought (" + why + ").");
                } else {
                    tx(() -> {
                        if (st == ProposalStatus.APPROVED) {
                            markSubmitted(p.id, actor, details("reason", "IBKR reports fills"));
                        }
                        proposalService.transition(p.id, ProposalStatus.FILLED, actor, details("reason",
                                why + " after a partial fill", "quantity", filled, "ordered", row.quantity));
                        return null;
                    });
                    protectPartial(p, row, filled, actor);
                }
            }
            case STOP, TARGET -> {
                if (expected || st != ProposalStatus.FILLED) {
                    return;
                }
                checkProtection(p, symbol, ref + " is " + status, actor);
            }
            case EXIT -> {
                if (!live) {
                    return;
                }
                String text = why + (filled.signum() == 0 ? ", nothing sold" : " after selling " + plain(filled)
                        + " of " + plain(row.quantity));
                tx(() -> {
                    if (st == ProposalStatus.APPROVED) {
                        markSubmitted(p.id, actor, details("order", ref));
                    }
                    return proposalService.transition(p.id, ProposalStatus.FAILED, actor, details("reason", text,
                            "order", ref, "filled", filled));
                });
                alerts.critical("#" + p.id + " " + symbol + ": exit did not complete (" + text + "). The target and "
                        + "stop were cancelled before it: the position may have NO stop. Check TWS or use /flatten.");
            }
        }
    }

    /**
     * The entry stopped after a partial fill. The target and stop must not sell more than was bought: lower
     * their quantity to {@code filled}, and alert loudly when there is no working stop at all.
     */
    private void protectPartial(Proposal p, BrokerOrder entryRow, BigDecimal filled, AuditActor actor) {
        String symbol = symbol(p.conid);
        List<BrokerOpenOrder> open;
        try {
            open = broker.openOrders();
        } catch (BrokerException e) {
            alerts.critical("#" + p.id + " " + symbol + ": entry stopped after " + plain(filled) + " of "
                    + plain(entryRow.quantity) + " filled and IBKR could not be asked about the stop: "
                    + e.getMessage() + ". Check the stop quantity in TWS.");
            return;
        }
        List<String> resized = new ArrayList<>();
        boolean stopWorking = false;
        for (OrderRole role : List.of(OrderRole.TARGET, OrderRole.STOP)) {
            String ref = OrderRefs.of(p.id, role);
            BrokerOpenOrder o = open.stream().filter(x -> ref.equals(x.orderRef())).findFirst().orElse(null);
            if (o == null) {
                continue;
            }
            stopWorking |= role == OrderRole.STOP;
            if (o.quantity() != null && o.quantity().compareTo(filled) > 0) {
                try {
                    broker.reduceQuantity(ref, filled);
                    tx(() -> {
                        BrokerOrder r = brokerOrders.find("orderRef", ref).firstResult();
                        BigDecimal was = r.quantity;
                        r.quantity = filled;
                        return audit.write(actor, EVENT_ORDER_RESIZED, p.id, details("order", ref, "from", was,
                                "to", filled, "reason", "entry filled " + plain(filled) + " of "
                                        + plain(entryRow.quantity)));
                    });
                    resized.add(role.name().toLowerCase());
                } catch (BrokerException | IllegalArgumentException e) {
                    tx(() -> audit.write(actor, EVENT_ORDER_ERROR, p.id, details("order", ref,
                            "error", errorText(e), "action", "reduce quantity to " + plain(filled))));
                    alerts.critical("#" + p.id + " " + symbol + ": could not lower " + ref + " to " + plain(filled)
                            + " shares (" + errorText(e) + "). It could sell more than the position. Fix it in TWS.");
                }
            }
        }
        if (!stopWorking) {
            checkProtection(p, symbol, "the entry stopped after a partial fill", actor);
            return;
        }
        alerts.info("#" + p.id + " " + symbol + ": entry partially filled, " + plain(filled) + " of "
                + plain(entryRow.quantity) + "; the rest is cancelled."
                + (resized.isEmpty() ? "" : " Resized " + String.join(" and ", resized) + " to " + plain(filled) + "."));
    }

    /** Alerts (CRITICAL) when IBKR shows a position for this entry but no working stop of ours. */
    private void checkProtection(Proposal p, String symbol, String trigger, AuditActor actor) {
        String stopRef = OrderRefs.of(p.id, OrderRole.STOP);
        String targetRef = OrderRefs.of(p.id, OrderRole.TARGET);
        try {
            boolean targetFilled = tx(() -> brokerOrders.find("orderRef", targetRef).firstResultOptional()
                    .map(r -> FILLED.equals(r.status)).orElse(false));
            if (targetFilled) {
                return; // OCA: the stop goes when the target fills
            }
            BigDecimal held = held(p.conid);
            boolean stopWorking = broker.openOrders().stream().anyMatch(o -> stopRef.equals(o.orderRef()));
            if (held.signum() <= 0 || stopWorking) {
                return;
            }
            tx(() -> audit.write(actor, EVENT_UNPROTECTED, p.id, details("conid", p.conid, "position", held,
                    "trigger", trigger)));
            alerts.critical("#" + p.id + " " + symbol + ": " + trigger + " and IBKR shows " + plain(held)
                    + " shares with NO working stop. Place a stop in TWS or use /flatten.");
        } catch (BrokerException e) {
            alerts.critical("#" + p.id + " " + symbol + ": " + trigger + "; IBKR could not be asked whether a stop "
                    + "is still working (" + e.getMessage() + "). Check TWS.");
        }
    }

    // ---- errors -------------------------------------------------------------------------------------------

    private void onError(BrokerOrderEvent.Error er, AuditActor actor) {
        BrokerOrder row = tx(() -> findRow(er.orderRef(), er.orderId()));
        if (row == null) {
            LOG.infof("IBKR error %d for order %d (%s), not ours: %s", er.code(), er.orderId(), er.orderRef(),
                    er.message());
            return;
        }
        String text = "IBKR " + er.code() + ": " + er.message();
        tx(() -> audit.write(actor, EVENT_ORDER_ERROR, row.proposalId, details("order", row.orderRef,
                "code", er.code(), "error", er.message())));
        if (CANCEL_ERRORS.contains(er.code())) {
            LOG.warnf("Cancel of %s refused: %s", row.orderRef, text);
            return;
        }
        Proposal p = proposalService.find(row.proposalId).orElse(null);
        if (p == null) {
            return;
        }
        String symbol = symbol(p.conid);
        ProposalStatus st = ProposalService.parse(p.status);
        boolean live = st == ProposalStatus.APPROVED || st == ProposalStatus.SUBMITTED;
        if (row.role == OrderRole.EXIT) {
            if (live) {
                fail(p.id, row.orderRef, text, null, symbol, true);
            }
            return;
        }
        BigDecimal entryFilled = tx(() -> brokerOrders.find("orderRef", OrderRefs.of(p.id, OrderRole.ENTRY))
                .firstResultOptional().map(r -> filledQuantity(r.id)).orElse(BigDecimal.ZERO));
        if (live && entryFilled.signum() == 0) {
            // no entry without a stop: whichever leg was refused, take the whole bracket down
            fail(p.id, row.orderRef, text, null, symbol, false);
            List<String> legs = List.of(OrderRefs.of(p.id, OrderRole.ENTRY), OrderRefs.of(p.id, OrderRole.TARGET),
                    OrderRefs.of(p.id, OrderRole.STOP));
            expectCancel(legs);
            cancelWorking(legs);
            return;
        }
        alerts.critical("#" + p.id + " " + symbol + ": " + row.orderRef + " got an error from IBKR (" + text
                + ") while the position is open. Check its stop in TWS.");
    }

    // =====================================================================================================
    // Jobs (called on the worker)
    // =====================================================================================================

    /**
     * Break-even: for each GATEWAY position whose break-even is not done, when the live last price reaches
     * entry + (entry - original stop) during the regular session, moves its stop up to the entry price.
     */
    public void breakEven() {
        if (!broker.isConnected()) {
            return;
        }
        Instant now = clock.instant();
        List<PositionMeta> metas = tx(() -> positionMetas.list(
                "origin = ?1 and breakevenDone = false and proposalId is not null", PositionOrigin.GATEWAY));
        for (PositionMeta m : metas) {
            try {
                breakEven(m, now);
            } catch (BrokerException e) {
                LOG.warnf("Break-even check for conid %d: %s", m.conid, e.getMessage());
            }
        }
    }

    private void breakEven(PositionMeta m, Instant now) {
        Proposal p = proposalService.find(m.proposalId).orElse(null);
        if (p == null || ProposalService.parse(p.status) != ProposalStatus.FILLED || m.entryPrice == null
                || p.stopPrice == null) {
            return;
        }
        String symbol = symbol(m.conid);
        if (!inSession(symbol, now)) {
            return;
        }
        BigDecimal entry = m.entryPrice;
        BigDecimal trigger = entry.add(entry.subtract(p.stopPrice));
        var q = broker.quote(symbol);
        if (q.delayed() || q.last() == null || q.last().compareTo(trigger) < 0) {
            return;
        }
        String stopRef = OrderRefs.of(p.id, OrderRole.STOP);
        BrokerOpenOrder stop = broker.openOrders().stream().filter(o -> stopRef.equals(o.orderRef())).findFirst()
                .orElse(null);
        if (stop == null) {
            alerts.warning("#" + p.id + " " + symbol + ": break-even reached (last " + plain(q.last())
                    + ") but IBKR shows no working " + stopRef + ".");
            return;
        }
        BigDecimal newStop = tick(entry);
        if (stop.auxPrice() != null && stop.auxPrice().compareTo(newStop) >= 0) {
            stopMoved(m.conid, stopRef, stop.auxPrice(), stop.auxPrice(), p.id, AuditActor.SYSTEM, true);
            return; // already at or above break-even
        }
        broker.modifyStop(stopRef, newStop);
        stopMoved(m.conid, stopRef, stop.auxPrice(), newStop, p.id, AuditActor.SYSTEM, true);
        alerts.info("#" + p.id + " " + symbol + ": last " + plain(q.last()) + " reached 1R (" + plain(trigger)
                + "). Stop moved " + plain(stop.auxPrice()) + " -> " + plain(newStop) + " (break-even).");
    }

    /**
     * End of day: from {@code eodCancelMinutes} before the day's close (as IBKR reports it), cancels ENTRY parents
     * that are still working. Unfilled: CANCELLED when IBKR confirms. Partly filled: FILLED with that quantity,
     * and the target and stop are resized to it.
     */
    public void endOfDay() {
        if (!broker.isConnected()) {
            return;
        }
        Instant now = clock.instant();
        List<BrokerOrder> parents = tx(() -> brokerOrders.getEntityManager().createQuery("""
                        select o from BrokerOrder o, Proposal p
                        where o.proposalId = p.id and o.role = :role and p.kind = :kind and p.status in :statuses
                        order by o.id
                        """, BrokerOrder.class)
                .setParameter("role", OrderRole.ENTRY)
                .setParameter("kind", ProposalKind.ENTRY)
                .setParameter("statuses", List.of(ProposalStatus.APPROVED.name(), ProposalStatus.SUBMITTED.name()))
                .getResultList());
        for (BrokerOrder o : parents) {
            if (DONE.contains(o.status) || PENDING_CANCEL.equals(o.status)) {
                continue;
            }
            try {
                Proposal p = proposalService.find(o.proposalId).orElseThrow();
                String symbol = symbol(p.conid);
                Optional<Instant> close = broker.contract(symbol).todaySession()
                        .filter(s -> s.date().equals(now.atZone(DeskConfig.MARKET_ZONE).toLocalDate()))
                        .map(s -> s.close());
                if (close.isEmpty()) {
                    continue;
                }
                Instant cutoff = close.get().minus(Duration.ofMinutes(config.orders().eodCancelMinutes()));
                if (now.isBefore(cutoff)) {
                    continue;
                }
                tx(() -> audit.write(AuditActor.SYSTEM, EVENT_EOD_CANCEL, o.proposalId, details("order", o.orderRef,
                        "close", close.get(), "cutoff", cutoff)));
                broker.cancel(o.orderRef);
                LOG.infof("End of day: cancel %s (close %s)", o.orderRef, close.get());
            } catch (BrokerException e) {
                LOG.warnf("End of day cancel of %s: %s", o.orderRef, e.getMessage());
            }
        }
    }

    // =====================================================================================================
    // Kill switch
    // =====================================================================================================

    /**
     * The kill switch went on: cancels every ENTRY parent that is still working (its unfilled part), and
     * cancels APPROVED entries whose orders were not sent yet. Targets and stops of positions stay. Returns the
     * refs it asked IBKR to cancel. Runs on the worker, so an approved entry queued before it is either sent
     * (and cancelled here) or finds its proposal CANCELLED.
     *
     * <p>A parent with a partial fill ends as FILLED with that quantity and its target and stop are resized
     * ({@link #orderEnded}); one with no fill ends CANCELLED, its children with it.
     */
    public List<String> cancelWorkingEntries(AuditActor actor, String reason) {
        List<Object[]> rows = tx(() -> brokerOrders.getEntityManager().createQuery("""
                        select p.id, o.orderRef, o.status from Proposal p left join BrokerOrder o
                          on o.proposalId = p.id and o.role = :role
                        where p.kind = :kind and p.status in :statuses
                        order by p.id
                        """, Object[].class)
                .setParameter("role", OrderRole.ENTRY)
                .setParameter("kind", ProposalKind.ENTRY)
                .setParameter("statuses", List.of(ProposalStatus.APPROVED.name(), ProposalStatus.SUBMITTED.name()))
                .getResultList());
        List<String> cancelled = new ArrayList<>();
        Set<String> working;
        try {
            working = broker.openOrders().stream().map(BrokerOpenOrder::orderRef).filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        } catch (BrokerException e) {
            working = null;
        }
        for (Object[] r : rows) {
            long id = (Long) r[0];
            String ref = (String) r[1];
            String status = (String) r[2];
            if (ref == null) {
                // approved, orders not stored yet: it must not be sent any more
                try {
                    tx(() -> {
                        if (stillApproved(id)) {
                            proposalService.transition(id, ProposalStatus.CANCELLED, actor, details("reason", reason));
                        }
                        return null;
                    });
                } catch (IllegalTransitionException e) {
                    LOG.debugf("Proposal %d changed before the kill switch could cancel it", id);
                }
                continue;
            }
            if (DONE.contains(status) || PENDING_CANCEL.equals(status)) {
                continue;
            }
            if (working == null) {
                alerts.critical("Kill switch: IBKR could not be asked for open orders, " + ref
                        + " may still be working. Cancel it in TWS.");
                continue;
            }
            if (!working.contains(ref)) {
                continue; // the reconciler settles a row IBKR does not show
            }
            tx(() -> audit.write(actor, EVENT_KILL_CANCEL, id, details("order", ref, "reason", reason)));
            try {
                broker.cancel(ref);
                cancelled.add(ref);
            } catch (BrokerException e) {
                LOG.infof("Kill switch: cancel %s: %s", ref, e.getMessage());
            }
        }
        return cancelled;
    }

    // =====================================================================================================
    // Used by the reconciler
    // =====================================================================================================

    /**
     * What {@link #positionGone} did.
     *
     * @param soldQuantity SELL executions found since the position opened (0 when none)
     */
    public record Gone(Long proposalId, String symbol, BigDecimal soldQuantity, BigDecimal averagePrice,
                       String pnlText, List<String> cancelled) {
    }

    /**
     * A GATEWAY position that IBKR no longer reports and that none of our order events closed (sold by hand,
     * or fills IBKR no longer reports). The entry becomes CLOSED with the P&amp;L of the SELL executions stored
     * since it opened, position_meta is deleted, and any of its orders still working are cancelled: a stop or
     * target without a position must not sell. Runs on the worker. Null when there is nothing to do.
     */
    public Gone positionGone(long conid, AuditActor actor) {
        record Closed(Long proposalId, BigDecimal sold, BigDecimal avg, Pnl pnl) {
        }
        Closed c = tx(() -> {
            PositionMeta m = positionMetas.findById(conid);
            if (m == null || m.origin != PositionOrigin.GATEWAY) {
                return null;
            }
            List<Execution> sells = m.openedAt == null ? List.of() : executions.list(
                    "conid = ?1 and side = ?2 and executedAt >= ?3 order by executedAt", conid, Side.SELL,
                    m.openedAt);
            Pnl pnl = pnl(sells, m.entryPrice, null, conid);
            BigDecimal sold = sells.stream().map(e -> e.quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            Proposal p = m.proposalId == null ? null : proposals.findById(m.proposalId);
            if (p != null) {
                closeEntry(p, "position no longer reported by IBKR", pnl, actor);
            }
            PositionMeta still = positionMetas.findById(conid);
            if (still != null) {
                deleteMeta(still, m.proposalId, "position no longer reported by IBKR",
                        actor);
            }
            return new Closed(m.proposalId, sold, averagePrice(sells), pnl);
        });
        if (c == null) {
            return null;
        }
        List<String> cancelled = new ArrayList<>();
        if (c.proposalId() != null) {
            List<String> refs = List.of(OrderRefs.of(c.proposalId(), OrderRole.ENTRY),
                    OrderRefs.of(c.proposalId(), OrderRole.TARGET), OrderRefs.of(c.proposalId(), OrderRole.STOP));
            try {
                List<String> working = broker.openOrders().stream().map(BrokerOpenOrder::orderRef)
                        .filter(refs::contains).distinct().toList();
                expectCancel(working);
                for (String ref : working) {
                    try {
                        broker.cancel(ref);
                        cancelled.add(ref);
                    } catch (BrokerException e) {
                        LOG.infof("Cancel %s: %s", ref, e.getMessage());
                    }
                }
            } catch (BrokerException e) {
                alerts.critical("#" + c.proposalId() + " " + symbol(conid) + ": the position is gone but IBKR could "
                        + "not be asked whether its stop or target is still working (" + e.getMessage()
                        + "). Cancel them in TWS.");
            }
        }
        return new Gone(c.proposalId(), symbol(conid), c.sold(), c.avg(), c.pnl().text(), cancelled);
    }

    /**
     * Lowers a working order of ours to {@code quantity} (it must not sell more than IBKR reports held) and
     * stores the new quantity. Throws what the broker throws.
     */
    public void resize(String orderRef, BigDecimal quantity, AuditActor actor, Map<String, ?> why) {
        broker.reduceQuantity(orderRef, quantity);
        tx(() -> {
            BrokerOrder r = brokerOrders.find("orderRef", orderRef).firstResult();
            Map<String, Object> d = details("order", orderRef, "from", r == null ? null : r.quantity, "to", quantity);
            d.putAll(why);
            if (r != null) {
                r.quantity = quantity;
            }
            return audit.write(actor, EVENT_ORDER_RESIZED, r == null ? null : r.proposalId, d);
        });
    }

    /** APPROVED -> SUBMITTED, when IBKR shows that the order did arrive. Joins the caller's transaction. */
    public void markSubmitted(long id, AuditActor actor, Map<String, ?> details) {
        Proposal p = proposals.findById(id);
        if (p != null && ProposalService.parse(p.status) == ProposalStatus.APPROVED) {
            proposalService.transition(id, ProposalStatus.SUBMITTED, actor, details);
        }
    }

    /** A STOP_UPDATE whose outcome was unknown: IBKR shows the stop at {@code seen}. */
    public void resolveStopUpdate(Proposal p, String stopRef, BigDecimal seen, AuditActor actor) {
        String symbol = symbol(p.conid);
        if (seen != null && seen.compareTo(p.stopPrice) == 0) {
            stopMoved(p.conid, stopRef, null, seen, p.id, actor, false);
            tx(() -> proposalService.transition(p.id, ProposalStatus.CLOSED, actor, details("order", stopRef,
                    "stop", seen)));
            alerts.info("#" + p.id + " " + symbol + ": IBKR confirms the stop at " + plain(seen) + ".");
        } else {
            fail(p.id, stopRef, "IBKR shows " + stopRef + " at " + plain(seen) + ", not " + plain(p.stopPrice),
                    null, symbol, false);
        }
    }

    public BigDecimal filledQuantity(long brokerOrderId) {
        return QuarkusTransaction.joiningExisting().call(() -> executions.getEntityManager().createQuery(
                        "select coalesce(sum(e.quantity), 0) from Execution e where e.brokerOrderId = :id",
                        BigDecimal.class)
                .setParameter("id", brokerOrderId)
                .getSingleResult());
    }

    // =====================================================================================================
    // Internals
    // =====================================================================================================

    /** True (after auditing the skip) when this ref is already in broker_order or working at IBKR. */
    private boolean alreadyExists(long id, String ref) {
        if (tx(() -> hasRef(ref))) {
            skip(id, ref, "orderRef already in broker_order");
            return true;
        }
        try {
            if (broker.openOrders().stream().anyMatch(o -> ref.equals(o.orderRef()))) {
                skip(id, ref, "orderRef already working at IBKR");
                return true;
            }
        } catch (BrokerException e) {
            fail(id, null, "IBKR not available to check for an existing " + ref + ": " + e.getMessage(), e,
                    symbol(proposalService.find(id).map(x -> x.conid).orElse(null)), false);
            return true;
        }
        return false;
    }

    private void skip(long id, String ref, String why) {
        LOG.infof("Proposal %d: not sending %s: %s", id, ref == null ? "orders" : ref, why);
        tx(() -> audit.write(AuditActor.SYSTEM, EVENT_DUPLICATE, id, details("order", ref, "reason", why)));
    }

    private boolean stillApproved(long id) {
        Proposal p = proposals.findById(id, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return p != null && ProposalService.parse(p.status) == ProposalStatus.APPROVED;
    }

    private boolean hasRef(String ref) {
        return brokerOrders.count("orderRef", ref) > 0;
    }

    private BrokerOrder newRow(long proposalId, int orderId, String ref, OrderRole role, Side action, String type,
                               BigDecimal qty, BigDecimal limit, BigDecimal aux) {
        BrokerOrder o = new BrokerOrder();
        o.proposalId = proposalId;
        o.ibOrderId = orderId;
        o.orderRef = ref;
        o.role = role;
        o.action = action;
        o.orderType = type;
        o.quantity = qty;
        o.limitPrice = limit;
        o.auxPrice = aux;
        o.status = PENDING_SUBMIT;
        brokerOrders.persist(o);
        return o;
    }

    private void setStatus(BrokerOrder row, String status, AuditActor actor, Map<String, Object> extra) {
        Map<String, Object> d = details("order", row.orderRef, "from", row.status, "to", status);
        extra.forEach(d::putIfAbsent);
        row.status = status;
        audit.write(actor, EVENT_ORDER_STATUS, row.proposalId, d);
    }

    /** By orderRef when the event has one; else by IBKR order id among rows that are still working. */
    private BrokerOrder findRow(String ref, int orderId) {
        if (ref != null && OrderRefs.parse(ref).isPresent()) {
            return brokerOrders.find("orderRef", ref).firstResult();
        }
        if (orderId <= 0) {
            return null;
        }
        return brokerOrders.find("ibOrderId = ?1 and status not in ?2 order by id desc", orderId, DONE)
                .firstResult();
    }

    private List<Execution> fillsOf(long brokerOrderId) {
        return executions.list("brokerOrderId = ?1 order by executedAt", brokerOrderId);
    }

    private static BigDecimal averagePrice(List<Execution> fills) {
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal value = BigDecimal.ZERO;
        for (Execution f : fills) {
            qty = qty.add(f.quantity);
            value = value.add(f.quantity.multiply(f.price));
        }
        return qty.signum() == 0 ? null : value.divide(qty, 6, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private void outcomeUnknown(long id, String ref, String symbol, BrokerException e) {
        tx(() -> audit.write(AuditActor.SYSTEM, EVENT_OUTCOME_UNKNOWN, id, details("order", ref,
                "error", e.getMessage())));
        alerts.warning("#" + id + " " + symbol + ": " + ref + " was sent but IBKR did not confirm it ("
                + e.getMessage() + "). The reconciler will check it against IBKR.");
    }

    /** IBKR refused a write call: the rows become Rejected and the proposal FAILED. */
    private void rejected(long id, List<String> refs, String symbol, RuntimeException e) {
        String text = errorText(e);
        tx(() -> {
            for (String ref : refs) {
                brokerOrders.find("orderRef", ref).firstResultOptional().ifPresent(r -> {
                    if (!DONE.contains(r.status)) {
                        setStatus(r, REJECTED, AuditActor.SYSTEM, details("error", text));
                    }
                });
            }
            audit.write(AuditActor.SYSTEM, EVENT_REJECTED, id, details("orders", refs, "error", text,
                    "code", e instanceof BrokerException be ? be.code() : null));
            return null;
        });
        fail(id, refs.get(0), text, null, symbol, false);
    }

    /** Proposal -> FAILED (from APPROVED or SUBMITTED) with the reason in audit_log, and an alert. */
    private void fail(long id, String ref, String reason, Exception cause, String symbol, boolean critical) {
        try {
            tx(() -> proposalService.transition(id, ProposalStatus.FAILED, AuditActor.SYSTEM,
                    details("error", reason, "order", ref)));
        } catch (IllegalTransitionException e) {
            LOG.warnf("Proposal %d could not become FAILED (%s): %s", id, e.from(), reason);
        }
        if (cause != null) {
            LOG.warnf("Proposal %d failed: %s (%s)", id, reason, cause.toString());
        }
        String text = "#" + id + " " + (symbol == null ? "" : symbol + " ") + "FAILED: " + reason;
        if (critical) {
            alerts.critical(text);
        } else {
            alerts.warning(text);
        }
    }

    private void cancelProposal(long id, String reason, String symbol) {
        try {
            tx(() -> proposalService.transition(id, ProposalStatus.CANCELLED, AuditActor.SYSTEM,
                    details("reason", reason)));
        } catch (IllegalTransitionException e) {
            LOG.warnf("Proposal %d could not be cancelled (%s): %s", id, e.from(), reason);
        }
        alerts.info("#" + id + " " + symbol + ": nothing sold, " + reason + ".");
    }

    /** Best effort: cancels whichever of these refs IBKR still has working. */
    private void cancelWorking(List<String> refs) {
        try {
            List<String> working = broker.openOrders().stream().map(BrokerOpenOrder::orderRef)
                    .filter(refs::contains).toList();
            for (String ref : working) {
                try {
                    broker.cancel(ref);
                } catch (BrokerException e) {
                    LOG.infof("Cancel %s: %s", ref, e.getMessage());
                }
            }
        } catch (BrokerException e) {
            alerts.critical("Could not check whether " + refs + " are still working at IBKR: " + e.getMessage()
                    + ". Check TWS.");
        }
    }

    /** Polls IBKR until none of {@code refs} is working (PendingCancel still counts). Returns those still working. */
    private List<String> awaitNotWorking(List<String> refs, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            List<String> still;
            try {
                still = broker.openOrders().stream().map(BrokerOpenOrder::orderRef).filter(refs::contains)
                        .distinct().toList();
            } catch (BrokerException e) {
                still = refs;
            }
            if (still.isEmpty() || System.nanoTime() > deadline) {
                return still;
            }
            sleep(POLL_MS);
        }
    }

    private BigDecimal held(long conid) {
        return broker.positions().stream().filter(p -> p.conid() == conid).map(BrokerPosition::quantity)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Optional<String> gatewayStopRef(long conid) {
        return tx(() -> Optional.ofNullable(positionMetas.findById(conid))
                .filter(m -> m.origin == PositionOrigin.GATEWAY && m.proposalId != null)
                .map(m -> OrderRefs.of(m.proposalId, OrderRole.STOP)));
    }

    private boolean inSession(String symbol, Instant now) {
        return broker.contract(symbol).todaySession()
                .map(s -> !now.isBefore(s.open()) && now.isBefore(s.close()))
                .orElse(false);
    }

    /** A stop price on IBKR's tick: rounded up (toward safety), cents from 1.00, else 0.0001. */
    static BigDecimal tick(BigDecimal price) {
        return price.compareTo(BigDecimal.ONE) >= 0 ? price.setScale(2, RoundingMode.CEILING)
                : price.setScale(4, RoundingMode.CEILING);
    }

    private String symbol(Long conid) {
        if (conid == null) {
            return "?";
        }
        return tx(() -> Optional.ofNullable(instruments.findById(conid)).map((Instrument i) -> i.symbol)
                .orElse("conid " + conid));
    }

    private static String errorText(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static <T> T tx(Supplier<T> body) {
        return QuarkusTransaction.joiningExisting().call(body::get);
    }

    static Map<String, Object> details(Object... kv) {
        Map<String, Object> d = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            d.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return d;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "n/a" : v.stripTrailingZeros().toPlainString();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
