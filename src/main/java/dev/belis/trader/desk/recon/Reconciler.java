package dev.belis.trader.desk.recon;

import dev.belis.trader.desk.broker.Broker;
import dev.belis.trader.desk.broker.BrokerException;
import dev.belis.trader.desk.broker.BrokerExecution;
import dev.belis.trader.desk.broker.BrokerOpenOrder;
import dev.belis.trader.desk.broker.BrokerOrderEvent;
import dev.belis.trader.desk.broker.BrokerPosition;
import dev.belis.trader.desk.broker.BrokerReconnected;
import dev.belis.trader.desk.broker.ContractInfo;
import dev.belis.trader.desk.broker.OrderRefs;
import dev.belis.trader.desk.config.DeskClock;
import dev.belis.trader.desk.config.DeskConfig;
import dev.belis.trader.desk.proposal.OrderExecutor;
import dev.belis.trader.desk.proposal.ProposalService;
import dev.belis.trader.desk.proposal.ProposalStatus;
import dev.belis.trader.desk.store.AuditActor;
import dev.belis.trader.desk.store.AuditLog;
import dev.belis.trader.desk.store.AuditLogRepository;
import dev.belis.trader.desk.store.AuditWriter;
import dev.belis.trader.desk.store.BrokerOrder;
import dev.belis.trader.desk.store.BrokerOrderRepository;
import dev.belis.trader.desk.store.Execution;
import dev.belis.trader.desk.store.ExecutionRepository;
import dev.belis.trader.desk.store.InstrumentRepository;
import dev.belis.trader.desk.store.OrderRole;
import dev.belis.trader.desk.store.PositionMeta;
import dev.belis.trader.desk.store.PositionMetaRepository;
import dev.belis.trader.desk.store.PositionOrigin;
import dev.belis.trader.desk.store.Proposal;
import dev.belis.trader.desk.store.ProposalKind;
import dev.belis.trader.desk.store.ProposalRepository;
import dev.belis.trader.desk.store.Setting;
import dev.belis.trader.desk.store.SettingRepository;
import dev.belis.trader.desk.store.Side;
import dev.belis.trader.desk.telegram.AlertService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Compares what IBKR reports (the source of truth) with the database: at startup once IBKR is connected, after
 * every {@link BrokerReconnected} and every 5 minutes. Each pass runs on the order executor's worker, so it never
 * races an order being sent or a broker event being applied.
 *
 * <ol>
 *   <li>Our orders: {@link OrderReconciler} (statuses, fills of open proposals).</li>
 *   <li>Executions since the last run that the database does not have: linked by orderRef to their
 *       broker_order (and applied like a live fill, which moves proposals), otherwise stored as EXTERNAL
 *       (broker_order_id null).</li>
 *   <li>Each position IBKR reports:
 *     <ul>
 *       <li>unknown to the database: position_meta origin EXTERNAL, one alert. Never touched after that.</li>
 *       <li>GATEWAY without a working stop: CRITICAL alert, every pass.</li>
 *       <li>GATEWAY whose quantity differs from what the stored executions add up to (split, corporate action,
 *           an execution IBKR no longer reports): one alert per new quantity.</li>
 *       <li>GATEWAY whose stop or target is for more shares than held: lowered to the position (a SELL never
 *           exceeds the position); for fewer shares: CRITICAL alert.</li>
 *       <li>short: CRITICAL alert (long only).</li>
 *     </ul></li>
 *   <li>position_meta rows IBKR shows no position for: GATEWAY ones are CLOSED from the stored SELL executions
 *       and their leftover orders cancelled; EXTERNAL ones are forgotten. Both alert.</li>
 * </ol>
 * Every difference writes an audit_log row (actor RECONCILER, events RECON_*).
 */
@ApplicationScoped
public class Reconciler {

    private static final Logger LOG = Logger.getLogger(Reconciler.class);

    public static final String EVENT_EXECUTION_LINKED = "RECON_EXECUTION_LINKED";
    public static final String EVENT_EXECUTION_EXTERNAL = "RECON_EXECUTION_EXTERNAL";
    public static final String EVENT_POSITION_EXTERNAL = "RECON_POSITION_EXTERNAL";
    public static final String EVENT_NO_STOP = "RECON_NO_STOP";
    public static final String EVENT_QUANTITY_CHANGED = "RECON_QUANTITY_CHANGED";
    public static final String EVENT_ORDER_QUANTITY = "RECON_ORDER_QUANTITY";
    public static final String EVENT_POSITION_GONE = "RECON_POSITION_GONE";
    public static final String EVENT_SHORT = "RECON_SHORT_POSITION";
    public static final String EVENT_UNRESOLVED = "RECON_UNRESOLVED";
    public static final String EVENT_DONE = "RECON_DONE";

    /** Setting: start of the last completed pass (ISO instant). Executions are fetched from shortly before it. */
    public static final String LAST_RUN = "recon_last_run";

    /** Executions are fetched from this long before the last run, so nothing falls between two passes. */
    static final java.time.Duration OVERLAP = java.time.Duration.ofMinutes(10);

    private static final AuditActor ACTOR = AuditActor.RECONCILER;

    private final Broker broker;
    private final OrderExecutor executor;
    private final OrderReconciler orders;
    private final ProposalService proposalService;
    private final ProposalRepository proposals;
    private final BrokerOrderRepository brokerOrders;
    private final ExecutionRepository executions;
    private final PositionMetaRepository positionMetas;
    private final InstrumentRepository instruments;
    private final SettingRepository settings;
    private final AuditLogRepository auditLogs;
    private final AuditWriter audit;
    private final AlertService alerts;
    private final DeskClock clock;
    private final DeskConfig config;
    private final ManagedExecutor background;

    public Reconciler(Broker broker, OrderExecutor executor, OrderReconciler orders, ProposalService proposalService,
                      ProposalRepository proposals, BrokerOrderRepository brokerOrders, ExecutionRepository executions,
                      PositionMetaRepository positionMetas, InstrumentRepository instruments,
                      SettingRepository settings, AuditLogRepository auditLogs, AuditWriter audit,
                      AlertService alerts, DeskClock clock, DeskConfig config, ManagedExecutor background) {
        this.broker = broker;
        this.executor = executor;
        this.orders = orders;
        this.proposalService = proposalService;
        this.proposals = proposals;
        this.brokerOrders = brokerOrders;
        this.executions = executions;
        this.positionMetas = positionMetas;
        this.instruments = instruments;
        this.settings = settings;
        this.auditLogs = auditLogs;
        this.audit = audit;
        this.alerts = alerts;
        this.clock = clock;
        this.config = config;
        this.background = background;
    }

    // =====================================================================================================
    // Triggers
    // =====================================================================================================

    /** The first connect fires no {@link BrokerReconnected}: wait for it here, then run once. */
    void onStart(@Observes StartupEvent e) {
        if (!config.recon().atStartup()) {
            return;
        }
        background.runAsync(() -> {
            long deadline = System.nanoTime() + config.recon().startupWait().toNanos();
            while (!broker.isConnected()) {
                if (System.nanoTime() > deadline) {
                    LOG.warnf("Startup reconcile skipped: IBKR not connected after %s; the 5-minute job will run it",
                            config.recon().startupWait());
                    return;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            executor.submit("Reconcile at startup", this::reconcile);
        });
    }

    void onReconnected(@Observes BrokerReconnected e) {
        executor.submit("Reconcile after reconnect", this::reconcile);
    }

    @Scheduled(every = "5m", delayed = "5m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void job() {
        executor.submit("Reconcile", this::reconcile).join();
    }

    // =====================================================================================================
    // One pass
    // =====================================================================================================

    /** One full pass. Call it on the executor's worker ({@link OrderExecutor#submit}). */
    public void reconcile() {
        if (!broker.isConnected()) {
            return;
        }
        Instant started = clock.instant();
        orders.reconcile();

        Snapshot snap;
        try {
            snap = snapshot(executionsSince(started));
        } catch (BrokerException e) {
            LOG.warnf("Reconcile skipped: %s", e.getMessage());
            return;
        }
        if (snap == null) {
            LOG.info("Reconcile skipped: positions kept changing while being read; the next pass retries");
            return;
        }
        List<BrokerPosition> positions = snap.positions();
        List<BrokerExecution> execs = snap.executions();

        int differences = executions(execs);
        List<BrokerOpenOrder> open;
        try {
            open = broker.openOrders();
        } catch (BrokerException e) {
            LOG.warnf("Reconcile stopped after the executions: %s", e.getMessage());
            return;
        }
        differences += positions(positions, open);
        differences += gone(positions);

        int d = differences;
        tx(() -> {
            putSetting(LAST_RUN, started.toString());
            if (d > 0) {
                audit.write(ACTOR, EVENT_DONE, null, Map.of("differences", d, "positions", positions.size(),
                        "executionsSeen", execs.size()));
            }
            return null;
        });
        if (d > 0) {
            LOG.infof("Reconcile: %d difference(s)", d);
        }
    }

    private record Snapshot(List<BrokerPosition> positions, List<BrokerExecution> executions) {
    }

    /**
     * Positions and executions that agree: positions read before and after the executions are the same, so no
     * fill landed in between. Null when they never settled.
     */
    private Snapshot snapshot(Instant since) {
        for (int attempt = 0; attempt < 3; attempt++) {
            List<BrokerPosition> before = broker.positions();
            List<BrokerExecution> execs = broker.executionsSince(since);
            List<BrokerPosition> after = broker.positions();
            if (quantities(before).equals(quantities(after))) {
                return new Snapshot(after, execs);
            }
        }
        return null;
    }

    private static Map<Long, BigDecimal> quantities(List<BrokerPosition> positions) {
        return positions.stream().filter(p -> p.quantity() != null).collect(Collectors.toMap(BrokerPosition::conid,
                p -> p.quantity().stripTrailingZeros(), BigDecimal::add));
    }

    private Instant executionsSince(Instant now) {
        Setting s = tx(() -> settings.findById(LAST_RUN));
        if (s != null) {
            try {
                return Instant.parse(s.value.trim()).minus(OVERLAP);
            } catch (DateTimeParseException e) {
                LOG.warnf("Bad %s setting '%s'", LAST_RUN, s.value);
            }
        }
        return now.minus(config.recon().firstLookback());
    }

    // ---- executions ---------------------------------------------------------------------------------------

    private int executions(List<BrokerExecution> execs) {
        if (execs.isEmpty()) {
            return 0;
        }
        Set<String> ids = execs.stream().map(BrokerExecution::execId).collect(Collectors.toSet());
        Set<String> known = new HashSet<>(tx(() -> executions.getEntityManager().createQuery(
                        "select e.ibExecId from Execution e where e.ibExecId in :ids", String.class)
                .setParameter("ids", ids).getResultList()));
        int n = 0;
        for (BrokerExecution x : execs.stream().sorted(Comparator.comparing(BrokerExecution::executedAt)).toList()) {
            if (!known.add(x.execId())) {
                continue;
            }
            BrokerOrder row = OrderRefs.parse(x.orderRef()).isEmpty() ? null
                    : tx(() -> brokerOrders.find("orderRef", x.orderRef()).firstResult());
            if (row != null) {
                tx(() -> audit.write(ACTOR, EVENT_EXECUTION_LINKED, row.proposalId, execDetails(x, row.orderRef)));
                executor.handle(new BrokerOrderEvent.Fill(x), ACTOR); // stores it and moves the proposal
            } else {
                external(x);
            }
            n++;
        }
        return n;
    }

    /** An execution no broker_order row explains: stored with broker_order_id null. */
    private void external(BrokerExecution x) {
        String symbol = x.symbol() == null ? "conid " + x.conid() : x.symbol();
        if (!ensureInstrument(x.conid(), x.symbol())) {
            unresolved("execution " + x.execId() + " of " + symbol + ": the instrument could not be looked up at "
                    + "IBKR, so it is not stored", Map.of("execId", x.execId(), "conid", x.conid()));
            return;
        }
        Long linkedProposal = tx(() -> {
            if (executions.findById(x.execId()) != null) {
                return null;
            }
            Execution n = new Execution();
            n.ibExecId = x.execId();
            n.brokerOrderId = null;
            n.conid = x.conid();
            n.side = x.side();
            n.quantity = x.quantity();
            n.price = x.price();
            n.currency = x.currency();
            n.commission = x.commission();
            n.realizedPnl = x.realizedPnl();
            n.executedAt = x.executedAt();
            executions.persist(n);
            PositionMeta m = positionMetas.findById(x.conid());
            Long pid = m == null ? null : m.proposalId;
            Map<String, Object> d = execDetails(x, blankToNull(x.orderRef()));
            d.put("origin", PositionOrigin.EXTERNAL);
            d.put("position", m == null ? null : m.origin);
            if (OrderRefs.parse(x.orderRef()).isPresent()) {
                d.put("reason", "orderRef " + x.orderRef() + " has no broker_order row");
            }
            audit.write(ACTOR, EVENT_EXECUTION_EXTERNAL, pid, d);
            return pid == null ? -1L : pid;
        });
        if (linkedProposal == null) {
            return;
        }
        String about = linkedProposal > 0 ? " It changes the position of #" + linkedProposal + "." : "";
        alerts.warning("External trade at IBKR, not placed by this service: " + x.side() + " " + plain(x.quantity())
                + " " + symbol + " at " + plain(x.price()) + " " + x.currency() + "." + about);
    }

    // ---- positions ----------------------------------------------------------------------------------------

    private int positions(List<BrokerPosition> positions, List<BrokerOpenOrder> open) {
        int n = 0;
        for (BrokerPosition p : positions) {
            if (p.quantity() == null || p.quantity().signum() == 0) {
                continue;
            }
            String symbol = p.symbol() == null ? "conid " + p.conid() : p.symbol();
            if (p.quantity().signum() < 0) {
                tx(() -> audit.write(ACTOR, EVENT_SHORT, null, Map.of("conid", p.conid(), "symbol", symbol,
                        "quantity", p.quantity())));
                alerts.critical(symbol + ": IBKR reports a SHORT position of " + plain(p.quantity())
                        + " shares. This service is long only; check TWS.");
                n++;
                continue;
            }
            PositionMeta m = tx(() -> positionMetas.findById(p.conid()));
            List<BrokerOpenOrder> sells = open.stream()
                    .filter(o -> o.conid() == p.conid() && o.action() == Side.SELL).toList();
            if (m == null) {
                if (entryInFlight(p.conid())) {
                    continue; // its fill events create position_meta
                }
                n += newExternal(p, symbol, sells);
                continue;
            }
            if (m.origin != PositionOrigin.GATEWAY) {
                continue; // EXTERNAL: alerted once when found, never touched
            }
            if (sells.stream().noneMatch(Reconciler::isStop)) {
                tx(() -> audit.write(ACTOR, EVENT_NO_STOP, m.proposalId, Map.of("conid", p.conid(), "symbol", symbol,
                        "quantity", p.quantity())));
                alerts.critical(ref(m) + symbol + ": IBKR shows " + plain(p.quantity()) + " shares with NO working "
                        + "stop order. Place a stop in TWS or use /flatten.");
                n++;
            }
            n += quantity(m, p, symbol, open);
        }
        return n;
    }

    private int newExternal(BrokerPosition p, String symbol, List<BrokerOpenOrder> sells) {
        if (!ensureInstrument(p.conid(), p.symbol())) {
            unresolved(symbol + ": IBKR reports " + plain(p.quantity()) + " shares that this service did not buy, and "
                    + "the instrument could not be looked up, so it is not recorded", Map.of("conid", p.conid()));
            return 1;
        }
        BigDecimal stop = sells.stream().filter(Reconciler::isStop).map(BrokerOpenOrder::auxPrice)
                .filter(Objects::nonNull).max(BigDecimal::compareTo).orElse(null);
        BigDecimal target = sells.stream().filter(o -> "LMT".equalsIgnoreCase(o.orderType()))
                .map(BrokerOpenOrder::limitPrice).filter(Objects::nonNull).min(BigDecimal::compareTo).orElse(null);
        boolean created = tx(() -> {
            if (positionMetas.findById(p.conid()) != null) {
                return false;
            }
            Instant opened = executions.getEntityManager().createQuery(
                            "select min(e.executedAt) from Execution e where e.conid = :c and e.side = :s",
                            Instant.class)
                    .setParameter("c", p.conid()).setParameter("s", Side.BUY).getSingleResult();
            PositionMeta m = new PositionMeta();
            m.conid = p.conid();
            m.proposalId = null;
            m.origin = PositionOrigin.EXTERNAL;
            m.entryPrice = p.avgCost();
            m.currentStop = stop;
            m.targetPrice = target;
            m.breakevenDone = false;
            m.openedAt = opened;
            positionMetas.persist(m);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("conid", p.conid());
            d.put("symbol", symbol);
            d.put("quantity", p.quantity());
            d.put("avgCost", p.avgCost());
            d.put("stop", stop);
            d.put("target", target);
            audit.write(ACTOR, EVENT_POSITION_EXTERNAL, null, d);
            return true;
        });
        if (!created) {
            return 0;
        }
        alerts.warning(symbol + ": IBKR reports " + plain(p.quantity()) + " shares at " + plain(p.avgCost())
                + " that this service did not buy. Recorded as EXTERNAL: it is never sold, stopped or changed here, "
                + "and /flatten leaves it alone." + (stop == null ? " It has NO stop order." : " Stop " + plain(stop) + "."));
        return 1;
    }

    /**
     * GATEWAY position: does IBKR's quantity match the stored executions, and do our stop and target cover exactly
     * the position?
     */
    private int quantity(PositionMeta m, BrokerPosition p, String symbol, List<BrokerOpenOrder> open) {
        int n = 0;
        BigDecimal held = p.quantity();
        if (m.openedAt != null) {
            BigDecimal stored = tx(() -> executions.getEntityManager().createQuery("""
                            select coalesce(sum(case when e.side = :buy then e.quantity else -e.quantity end), 0)
                            from Execution e where e.conid = :c and e.executedAt >= :from
                            """, BigDecimal.class)
                    .setParameter("buy", Side.BUY).setParameter("c", p.conid()).setParameter("from", m.openedAt)
                    .getSingleResult());
            if (stored.compareTo(held) != 0 && !alreadyReported(p.conid(), held, stored)) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("conid", p.conid());
                d.put("symbol", symbol);
                d.put("brokerQuantity", held);
                d.put("executionQuantity", stored);
                d.put("avgCost", p.avgCost());
                d.put("entryPrice", m.entryPrice);
                tx(() -> audit.write(ACTOR, EVENT_QUANTITY_CHANGED, m.proposalId, d));
                alerts.warning(ref(m) + symbol + ": IBKR reports " + plain(held) + " shares but the executions add up "
                        + "to " + plain(stored) + " (a split or other corporate action?). IBKR's average cost is "
                        + plain(p.avgCost()) + ", the stored entry " + plain(m.entryPrice)
                        + ". Stop and target quantities checked below.");
                n++;
            }
        }
        if (m.proposalId == null) {
            return n;
        }
        for (OrderRole role : List.of(OrderRole.STOP, OrderRole.TARGET)) {
            String orderRef = OrderRefs.of(m.proposalId, role);
            BrokerOpenOrder o = open.stream().filter(x -> orderRef.equals(x.orderRef())).findFirst().orElse(null);
            if (o == null || o.quantity() == null || o.quantity().compareTo(held) == 0) {
                continue;
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("order", orderRef);
            d.put("orderQuantity", o.quantity());
            d.put("position", held);
            if (o.quantity().compareTo(held) > 0) {
                try {
                    executor.resize(orderRef, held, ACTOR, Map.of("reason", "position is " + plain(held)));
                    d.put("action", "lowered to the position");
                    tx(() -> audit.write(ACTOR, EVENT_ORDER_QUANTITY, m.proposalId, d));
                    alerts.warning(ref(m) + symbol + ": " + orderRef + " was for " + plain(o.quantity())
                            + " shares but the position is " + plain(held) + "; lowered to " + plain(held) + ".");
                } catch (BrokerException | IllegalArgumentException e) {
                    d.put("action", "lowering failed: " + e.getMessage());
                    tx(() -> audit.write(ACTOR, EVENT_ORDER_QUANTITY, m.proposalId, d));
                    alerts.critical(ref(m) + symbol + ": " + orderRef + " is for " + plain(o.quantity())
                            + " shares but the position is " + plain(held) + ", and it could not be lowered ("
                            + e.getMessage() + "). It could sell more than held: fix it in TWS.");
                }
            } else {
                d.put("action", "none: covers less than the position");
                tx(() -> audit.write(ACTOR, EVENT_ORDER_QUANTITY, m.proposalId, d));
                String what = role == OrderRole.STOP ? "Part of the position has NO stop" : "Check the target";
                alerts.critical(ref(m) + symbol + ": " + orderRef + " is for " + plain(o.quantity())
                        + " shares but IBKR reports " + plain(held) + ". " + what + ": fix it in TWS.");
            }
            n++;
        }
        return n;
    }

    /** The last quantity alert for this instrument was about the same two quantities. */
    private boolean alreadyReported(long conid, BigDecimal held, BigDecimal stored) {
        AuditLog last = tx(() -> auditLogs.getEntityManager().createQuery(
                        "select a from AuditLog a where a.event = :e order by a.id desc", AuditLog.class)
                .setParameter("e", EVENT_QUANTITY_CHANGED).getResultStream()
                .filter(a -> a.details.path("conid").asLong() == conid).findFirst().orElse(null));
        return last != null && same(last.details.path("brokerQuantity").asText(), held)
                && same(last.details.path("executionQuantity").asText(), stored);
    }

    // ---- positions IBKR no longer reports -----------------------------------------------------------------

    private int gone(List<BrokerPosition> positions) {
        Map<Long, BigDecimal> held = positions.stream().filter(p -> p.quantity() != null)
                .collect(Collectors.toMap(BrokerPosition::conid, BrokerPosition::quantity, BigDecimal::add));
        List<PositionMeta> metas = tx(() -> positionMetas.listAll());
        int n = 0;
        for (PositionMeta m : metas) {
            if (held.getOrDefault(m.conid, BigDecimal.ZERO).signum() != 0) {
                continue;
            }
            String symbol = symbol(m.conid);
            if (m.origin == PositionOrigin.EXTERNAL) {
                tx(() -> {
                    PositionMeta row = positionMetas.findById(m.conid);
                    if (row != null) {
                        positionMetas.delete(row);
                    }
                    return audit.write(ACTOR, EVENT_POSITION_GONE, null, Map.of("conid", m.conid, "symbol", symbol,
                            "origin", PositionOrigin.EXTERNAL));
                });
                alerts.info(symbol + ": the EXTERNAL position is no longer reported by IBKR; forgotten.");
                n++;
                continue;
            }
            OrderExecutor.Gone g = executor.positionGone(m.conid, ACTOR);
            if (g == null) {
                continue;
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("conid", m.conid);
            d.put("symbol", symbol);
            d.put("origin", PositionOrigin.GATEWAY);
            d.put("soldQuantity", g.soldQuantity());
            d.put("averagePrice", g.averagePrice());
            d.put("pnl", g.pnlText());
            d.put("cancelled", g.cancelled());
            tx(() -> audit.write(ACTOR, EVENT_POSITION_GONE, g.proposalId(), d));
            String sold = g.soldQuantity().signum() == 0
                    ? "No SELL execution is known for it, so the P&L is unknown."
                    : "Executions: sold " + plain(g.soldQuantity()) + " at " + plain(g.averagePrice())
                    + " avg; realized P&L " + g.pnlText() + ".";
            alerts.warning(ref(g.proposalId()) + symbol + ": IBKR shows no position any more, and none of this "
                    + "service's orders closed it. Marked CLOSED. " + sold
                    + (g.cancelled().isEmpty() ? "" : " Cancelled " + String.join(", ", g.cancelled()) + "."));
            n++;
        }
        return n;
    }

    // =====================================================================================================
    // Helpers
    // =====================================================================================================

    /** An ENTRY of ours for this instrument is approved or working: IBKR may report its fill before we see it. */
    private boolean entryInFlight(long conid) {
        return tx(() -> proposals.count("kind = ?1 and conid = ?2 and status in ?3", ProposalKind.ENTRY, conid,
                List.of(ProposalStatus.APPROVED.name(), ProposalStatus.SUBMITTED.name())) > 0);
    }

    /** The instrument row exists, or could be stored from IBKR's contract details. */
    private boolean ensureInstrument(long conid, String symbol) {
        if (tx(() -> instruments.findById(conid) != null)) {
            return true;
        }
        if (symbol == null || symbol.isBlank()) {
            return false;
        }
        ContractInfo c;
        try {
            c = broker.contract(symbol);
        } catch (BrokerException e) {
            LOG.warnf("Contract lookup of %s failed: %s", symbol, e.getMessage());
            return false;
        }
        if (c.conid() != conid) {
            LOG.warnf("Contract lookup of %s returned conid %d, not %d", symbol, c.conid(), conid);
            return false;
        }
        tx(() -> {
            proposalService.saveInstrument(c);
            return null;
        });
        return true;
    }

    private void unresolved(String what, Map<String, ?> details) {
        Map<String, Object> d = new LinkedHashMap<>(details);
        d.put("reason", what);
        tx(() -> audit.write(ACTOR, EVENT_UNRESOLVED, null, d));
        alerts.warning("Reconciler: " + what + ".");
    }

    private static boolean isStop(BrokerOpenOrder o) {
        String t = o.orderType() == null ? "" : o.orderType().toUpperCase(Locale.ROOT);
        return t.startsWith("STP") || t.startsWith("TRAIL");
    }

    private String symbol(long conid) {
        return tx(() -> Optional.ofNullable(instruments.findById(conid)).map(i -> i.symbol).orElse("conid " + conid));
    }

    private void putSetting(String key, String value) {
        Setting s = settings.findById(key);
        if (s == null) {
            s = new Setting();
            s.key = key;
            s.value = value;
            settings.persist(s);
        } else {
            s.value = value;
        }
    }

    private static Map<String, Object> execDetails(BrokerExecution x, String orderRef) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("execId", x.execId());
        d.put("order", orderRef);
        d.put("conid", x.conid());
        d.put("symbol", x.symbol());
        d.put("side", x.side());
        d.put("quantity", x.quantity());
        d.put("price", x.price());
        d.put("currency", x.currency());
        d.put("executedAt", x.executedAt());
        return d;
    }

    private static String ref(PositionMeta m) {
        return ref(m.proposalId);
    }

    private static String ref(Long proposalId) {
        return proposalId == null ? "" : "#" + proposalId + " ";
    }

    private static boolean same(String stored, BigDecimal v) {
        try {
            return new BigDecimal(stored).compareTo(v) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "n/a" : v.stripTrailingZeros().toPlainString();
    }

    private static <T> T tx(Supplier<T> body) {
        return QuarkusTransaction.joiningExisting().call(body::get);
    }
}
