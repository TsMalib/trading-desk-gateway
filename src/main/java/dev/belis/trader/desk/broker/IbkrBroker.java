package dev.belis.trader.desk.broker;

import com.ib.client.CommissionAndFeesReport;
import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.DefaultEWrapper;
import com.ib.client.EClientSocket;
import com.ib.client.EJavaSignal;
import com.ib.client.EReader;
import com.ib.client.EReaderSignal;
import com.ib.client.Execution;
import com.ib.client.ExecutionFilter;
import com.ib.client.Order;
import com.ib.client.OrderState;
import com.ib.client.TickAttrib;
import com.ib.client.TickType;
import com.ib.client.Decimal;
import dev.belis.trader.desk.config.DeskConfig;
import dev.belis.trader.desk.store.Side;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * {@link Broker} on the IBKR TWS API (IB Gateway).
 *
 * <p>Threads:
 * <ul>
 *   <li>{@code ibkr-reader}: the TWS {@link EReader}, reads the socket.</li>
 *   <li>{@code ibkr-msgs}: waits on the {@link EReaderSignal} and dispatches to the single {@link Wrapper};
 *       callbacks complete {@link CompletableFuture}s keyed by reqId.</li>
 *   <li>{@code ibkr-connector}: connect, reconnect with backoff, connection watchdog.</li>
 *   <li>{@code ibkr-events}: fires {@link BrokerReconnected} so observers may call back into the broker.</li>
 * </ul>
 * Callers block on the future with {@code desk.ib.requestTimeout}.
 *
 * <p>Write methods are not implemented yet (the Gateway runs with READ_ONLY_API=yes in this stage).
 */
@ApplicationScoped
@IfBuildProperty(name = "desk.broker", stringValue = "ibkr", enableIfMissing = true)
public class IbkrBroker implements Broker {

    private static final Logger LOG = Logger.getLogger(IbkrBroker.class);

    /** reqIds start far above order ids, because IBKR reports errors for both under the same id. */
    private static final int REQ_ID_BASE = 1_000_000_000;
    /** Keys for requests IBKR does not key by reqId; at most one of each in flight. */
    private static final int POSITIONS_KEY = -100;
    private static final int OPEN_ORDERS_KEY = -101;

    private static final String SUMMARY_TAGS = "NetLiquidation,TotalCashValue";
    private static final Duration COMMISSION_WAIT = Duration.ofSeconds(3);
    private static final Duration WATCHDOG_PERIOD = Duration.ofSeconds(10);

    /** Informational error codes: logged, but they do not fail the request they refer to. */
    private static boolean informational(int code) {
        return (code >= 2100 && code < 2200)
                || code == 10167   // requested market data not subscribed, displaying delayed data
                || code == 10090   // part of requested market data not subscribed
                || code == 300     // can't find EId (cancel of a finished snapshot)
                || code == 399;    // order message (warning)
    }

    record Settings(String host, int port, int clientId, int marketDataType, Duration requestTimeout,
                    Duration connectTimeout, Duration reconnectMaxBackoff, ZoneId executionFallbackZone) {

        static Settings from(DeskConfig.Ib ib) {
            return new Settings(ib.host(), ib.port(), ib.clientId(), ib.marketDataType(), ib.requestTimeout(),
                    ib.connectTimeout(), ib.reconnectMaxBackoff(), ZoneId.systemDefault());
        }
    }

    private final Settings settings;
    private final Consumer<BrokerReconnected> reconnectListener;
    private final Clock clock;
    private final Wrapper wrapper = new Wrapper();

    private ScheduledExecutorService connector;
    private ExecutorService events;

    private volatile EClientSocket client;
    private volatile ConnectionState state = ConnectionState.DISCONNECTED;
    private volatile boolean stopped;
    private volatile CompletableFuture<Integer> handshake = new CompletableFuture<>();
    private volatile String managedAccounts = "";

    // Confined to the connector thread.
    private boolean everConnected;
    private boolean reconnectScheduled;
    private int failedAttempts;

    private final AtomicInteger nextOrderId = new AtomicInteger(-1);
    private final AtomicInteger reqIds = new AtomicInteger(REQ_ID_BASE);
    private final ConcurrentMap<Integer, Pending<?>> pending = new ConcurrentHashMap<>();
    private final ReentrantLock positionsLock = new ReentrantLock();
    private final ReentrantLock openOrdersLock = new ReentrantLock();

    /** Commission reports by execId. They arrive separately from execDetails, sometimes after execDetailsEnd. */
    private final Map<String, CommissionAndFeesReport> commissions = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CommissionAndFeesReport> eldest) {
                    return size() > 10_000;
                }
            });

    @Inject
    public IbkrBroker(DeskConfig config, Event<BrokerReconnected> reconnected) {
        this(Settings.from(config.ib()), reconnected::fire, Clock.systemUTC());
    }

    IbkrBroker(Settings settings, Consumer<BrokerReconnected> reconnectListener, Clock clock) {
        this.settings = settings;
        this.reconnectListener = reconnectListener;
        this.clock = clock;
    }

    // ======================================================================
    // Lifecycle and connection
    // ======================================================================

    void onStart(@Observes StartupEvent ev) {
        start();
    }

    /** Starts connecting in the background; does not wait for the connection. */
    synchronized void start() {
        if (connector != null) {
            return;
        }
        stopped = false;
        connector = Executors.newSingleThreadScheduledExecutor(daemon("ibkr-connector"));
        events = Executors.newSingleThreadExecutor(daemon("ibkr-events"));
        connector.execute(this::connectAttempt);
        connector.scheduleWithFixedDelay(this::watchdog, WATCHDOG_PERIOD.toMillis(), WATCHDOG_PERIOD.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    synchronized void stop() {
        stopped = true;
        if (connector != null) {
            connector.shutdownNow();
            events.shutdownNow();
            connector = null;
        }
        EClientSocket c = client;
        if (c != null && c.isConnected()) {
            c.eDisconnect();
        }
        state = ConnectionState.DISCONNECTED;
        failAllPending("broker stopped");
    }

    @Override
    public ConnectionState connectionState() {
        return state;
    }

    /** Next order id from nextValidId. Used by the write methods of a later stage. */
    int allocateOrderId() {
        int id = nextOrderId.getAndUpdate(v -> v < 0 ? v : v + 1);
        if (id < 0) {
            throw new BrokerUnavailableException("no valid order id received from IBKR yet");
        }
        return id;
    }

    /** Runs on the connector thread. */
    private void connectAttempt() {
        reconnectScheduled = false;
        if (stopped) {
            return;
        }
        state = ConnectionState.CONNECTING;
        EReaderSignal signal = new EJavaSignal();
        EClientSocket c = new EClientSocket(wrapper, signal);
        CompletableFuture<Integer> hs = new CompletableFuture<>();
        handshake = hs;

        LOG.infof("Connecting to IB Gateway %s:%d as clientId %d", settings.host(), settings.port(),
                settings.clientId());
        c.eConnect(settings.host(), settings.port(), settings.clientId());
        if (!c.isConnected()) {
            attemptFailed("connect failed");
            return;
        }
        client = c;
        EReader reader = new EReader(c, signal);
        reader.setName("ibkr-reader");
        reader.setDaemon(true);
        reader.start();
        Thread.ofPlatform().daemon().name("ibkr-msgs").start(() -> processMessages(c, reader, signal));

        try {
            hs.get(settings.connectTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            c.eDisconnect();
            return;
        } catch (ExecutionException | TimeoutException e) {
            c.eDisconnect();
            attemptFailed(e instanceof TimeoutException ? "no nextValidId within " + settings.connectTimeout()
                    : e.getCause().getMessage());
            return;
        }

        c.reqMarketDataType(settings.marketDataType());
        failedAttempts = 0;
        state = ConnectionState.CONNECTED;
        LOG.infof("Connected to IB Gateway %s:%d (server version %d, accounts %s, next order id %d)",
                settings.host(), settings.port(), c.serverVersion(), managedAccounts, nextOrderId.get());
        if (everConnected) {
            fireReconnected(BrokerReconnected.Reason.SOCKET_RECONNECTED);
        }
        everConnected = true;
    }

    private void processMessages(EClientSocket c, EReader reader, EReaderSignal signal) {
        while (c.isConnected() && !stopped) {
            signal.waitForSignal();
            try {
                reader.processMsgs();
            } catch (Exception e) {
                LOG.error("Error while processing IBKR message", e);
            }
        }
    }

    private void attemptFailed(String why) {
        state = ConnectionState.DISCONNECTED;
        failAllPending("not connected to IB Gateway");
        scheduleReconnect(why);
    }

    /** Runs on the connector thread. */
    private void onConnectionLost() {
        if (stopped || reconnectScheduled || state == ConnectionState.CONNECTING) {
            return;
        }
        EClientSocket c = client;
        if (c != null && c.isConnected()) {
            return; // stale notification from an earlier socket
        }
        LOG.warnf("Lost connection to IB Gateway %s:%d", settings.host(), settings.port());
        attemptFailed("connection lost");
    }

    private void scheduleReconnect(String why) {
        if (stopped || reconnectScheduled) {
            return;
        }
        reconnectScheduled = true;
        long delayMs = backoff(failedAttempts++).toMillis();
        LOG.warnf("IB Gateway %s:%d: %s; reconnecting in %d s", settings.host(), settings.port(), why,
                delayMs / 1000);
        connector.schedule(this::connectAttempt, delayMs, TimeUnit.MILLISECONDS);
    }

    Duration backoff(int failures) {
        Duration d = Duration.ofSeconds(1L << Math.min(failures, 16));
        return d.compareTo(settings.reconnectMaxBackoff()) > 0 ? settings.reconnectMaxBackoff() : d;
    }

    private void watchdog() {
        ConnectionState s = state;
        if (s == ConnectionState.CONNECTED || s == ConnectionState.IB_SERVER_LOST) {
            EClientSocket c = client;
            if (c == null || !c.isConnected()) {
                onConnectionLost();
            }
        }
    }

    private void connectionEvent(Runnable r) {
        ScheduledExecutorService ex = connector;
        if (ex != null && !ex.isShutdown()) {
            ex.execute(r);
        }
    }

    private void fireReconnected(BrokerReconnected.Reason reason) {
        BrokerReconnected ev = new BrokerReconnected(clock.instant(), reason);
        LOG.infof("Broker reconnected (%s)", reason);
        ExecutorService ex = events;
        if (ex == null || ex.isShutdown()) {
            return;
        }
        ex.execute(() -> {
            try {
                reconnectListener.accept(ev);
            } catch (RuntimeException e) {
                LOG.error("BrokerReconnected observer failed", e);
            }
        });
    }

    private void failAllPending(String why) {
        for (Pending<?> p : pending.values()) {
            p.future.completeExceptionally(new BrokerUnavailableException(why + " (" + p.what + ")"));
        }
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> Thread.ofPlatform().daemon().name(name).unstarted(r);
    }

    // ======================================================================
    // Request plumbing
    // ======================================================================

    private static class Pending<T> {
        final String what;
        final CompletableFuture<T> future = new CompletableFuture<>();

        Pending(String what) {
            this.what = what;
        }
    }

    private EClientSocket requireConnected() {
        ConnectionState s = state;
        EClientSocket c = client;
        if ((s != ConnectionState.CONNECTED && s != ConnectionState.IB_SERVER_LOST) || c == null || !c.isConnected()) {
            throw new BrokerUnavailableException("not connected to IB Gateway (" + s + ")");
        }
        return c;
    }

    /**
     * Registers {@code p} under {@code key}, sends the request and waits for the wrapper to complete it.
     * {@code cleanup} runs afterwards in every case (e.g. to cancel a subscription).
     */
    private <T> T call(int key, Pending<T> p, Consumer<EClientSocket> send, Consumer<EClientSocket> cleanup) {
        EClientSocket c = requireConnected();
        pending.put(key, p);
        try {
            send.accept(c);
            return p.future.get(settings.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new BrokerTimeoutException(p.what + ": no answer from IBKR within " + settings.requestTimeout());
        } catch (ExecutionException e) {
            if (e.getCause() instanceof BrokerException be) {
                throw be;
            }
            throw new BrokerException(p.what + " failed: " + e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerException(p.what + " interrupted");
        } finally {
            pending.remove(key);
            if (cleanup != null && c.isConnected()) {
                try {
                    cleanup.accept(c);
                } catch (RuntimeException e) {
                    LOG.debugf(e, "cleanup after %s failed", p.what);
                }
            }
        }
    }

    private <P extends Pending<?>> P pendingAs(int key, Class<P> type) {
        Pending<?> p = pending.get(key);
        return type.isInstance(p) ? type.cast(p) : null;
    }

    private static Contract usStock(String symbol) {
        Contract c = new Contract();
        c.symbol(symbol.trim().toUpperCase());
        c.secType("STK");
        c.exchange("SMART");
        c.currency("USD");
        return c;
    }

    // ======================================================================
    // Read methods
    // ======================================================================

    private static final class SummaryReq extends Pending<AccountSummary> {
        /** account -> tag -> {value, currency} */
        final Map<String, Map<String, String[]>> values = new TreeMap<>();

        SummaryReq() {
            super("accountSummary");
        }
    }

    @Override
    public AccountSummary accountSummary() {
        int id = reqIds.incrementAndGet();
        return call(id, new SummaryReq(),
                c -> c.reqAccountSummary(id, "All", SUMMARY_TAGS),
                c -> c.cancelAccountSummary(id));
    }

    private static AccountSummary toSummary(SummaryReq r) {
        if (r.values.isEmpty()) {
            throw new BrokerException("IBKR returned no account summary");
        }
        if (r.values.size() > 1) {
            LOG.warnf("Several accounts in summary %s; using %s", r.values.keySet(), r.values.keySet().iterator().next());
        }
        Map.Entry<String, Map<String, String[]>> first = r.values.entrySet().iterator().next();
        String[] netLiq = first.getValue().get("NetLiquidation");
        String[] cash = first.getValue().get("TotalCashValue");
        if (netLiq == null || cash == null) {
            throw new BrokerException("account summary is missing NetLiquidation or TotalCashValue: "
                    + first.getValue().keySet());
        }
        return new AccountSummary(first.getKey(), netLiq[1], new BigDecimal(netLiq[0]), new BigDecimal(cash[0]));
    }

    private static final class PositionsReq extends Pending<List<BrokerPosition>> {
        final List<BrokerPosition> items = new ArrayList<>();

        PositionsReq() {
            super("positions");
        }
    }

    @Override
    public List<BrokerPosition> positions() {
        return locked(positionsLock, "positions", () -> call(POSITIONS_KEY, new PositionsReq(),
                EClientSocket::reqPositions, EClientSocket::cancelPositions));
    }

    private static final class OpenOrdersReq extends Pending<List<BrokerOpenOrder>> {
        /** Keyed by permId (or orderId); IBKR may send the same order more than once. */
        final Map<Long, BrokerOpenOrder> items = new LinkedHashMap<>();

        OpenOrdersReq() {
            super("openOrders");
        }
    }

    @Override
    public List<BrokerOpenOrder> openOrders() {
        return locked(openOrdersLock, "openOrders", () -> call(OPEN_ORDERS_KEY, new OpenOrdersReq(),
                EClientSocket::reqAllOpenOrders, null));
    }

    private <T> T locked(ReentrantLock lock, String what, java.util.function.Supplier<T> body) {
        try {
            if (!lock.tryLock(settings.requestTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new BrokerTimeoutException(what + ": another request is still in flight");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerException(what + " interrupted");
        }
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }

    private record RawExecution(Contract contract, Execution execution) {
    }

    private static final class ExecutionsReq extends Pending<List<RawExecution>> {
        final List<RawExecution> items = new ArrayList<>();

        ExecutionsReq() {
            super("executions");
        }
    }

    @Override
    public List<BrokerExecution> executionsSince(Instant since) {
        int id = reqIds.incrementAndGet();
        ExecutionFilter filter = new ExecutionFilter();
        filter.time(IbFormats.executionFilterTime(since));
        List<RawExecution> raw = call(id, new ExecutionsReq(), c -> c.reqExecutions(id, filter), null);

        awaitCommissions(raw.stream().map(r -> r.execution().execId()).toList());
        return raw.stream()
                .map(this::toExecution)
                .filter(e -> !e.executedAt().isBefore(since))
                .sorted(Comparator.comparing(BrokerExecution::executedAt))
                .toList();
    }

    private void awaitCommissions(Collection<String> execIds) {
        long deadline = System.nanoTime() + COMMISSION_WAIT.toNanos();
        synchronized (commissions) {
            while (!commissions.keySet().containsAll(execIds)) {
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMs <= 0) {
                    LOG.warnf("Commission reports missing for %s",
                            execIds.stream().filter(id -> !commissions.containsKey(id)).toList());
                    return;
                }
                try {
                    commissions.wait(remainingMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private BrokerExecution toExecution(RawExecution r) {
        Execution e = r.execution();
        Contract c = r.contract();
        CommissionAndFeesReport report = commissions.get(e.execId());
        return new BrokerExecution(
                e.execId(),
                e.orderId(),
                e.permId(),
                IbFormats.blankToNull(e.orderRef()),
                e.acctNumber(),
                c.conid(),
                c.symbol(),
                "BOT".equals(e.side()) ? Side.BUY : Side.SELL,
                IbFormats.quantity(e.shares()),
                IbFormats.price(e.price()),
                c.currency(),
                report == null ? null : IbFormats.amount(report.commissionAndFees()),
                report == null ? null : report.currency(),
                report == null ? null : IbFormats.amount(report.realizedPNL()),
                IbFormats.executionTime(e.time(), settings.executionFallbackZone()));
    }

    private static final class SnapshotReq extends Pending<Quote> {
        final String symbol;
        BigDecimal bid;
        BigDecimal ask;
        BigDecimal last;
        BigDecimal close;
        boolean delayed;

        SnapshotReq(String symbol) {
            super("quote " + symbol);
            this.symbol = symbol;
        }
    }

    @Override
    public Quote quote(String symbol) {
        int id = reqIds.incrementAndGet();
        SnapshotReq req = new SnapshotReq(symbol.trim().toUpperCase());
        Contract contract = usStock(symbol);
        return call(id, req,
                c -> c.reqMktData(id, contract, "", true, false, null),
                c -> {
                    if (!req.future.isDone() || req.future.isCompletedExceptionally()) {
                        c.cancelMktData(id);
                    }
                });
    }

    private static final class ContractReq extends Pending<List<ContractDetails>> {
        final List<ContractDetails> items = new ArrayList<>();

        ContractReq(String symbol) {
            super("contract " + symbol);
        }
    }

    @Override
    public ContractInfo contract(String symbol) {
        int id = reqIds.incrementAndGet();
        Contract query = usStock(symbol);
        List<ContractDetails> found = call(id, new ContractReq(query.symbol()),
                c -> c.reqContractDetails(id, query), null);
        if (found.isEmpty()) {
            throw new BrokerException("IBKR knows no US stock " + query.symbol());
        }
        if (found.size() > 1) {
            throw new BrokerException("ambiguous symbol " + query.symbol() + ": " + found.stream()
                    .map(d -> d.contract().conid() + "@" + d.contract().primaryExch())
                    .collect(Collectors.joining(", ")));
        }
        ContractDetails d = found.get(0);
        Contract c = d.contract();
        String isin = Optional.ofNullable(d.secIdList()).orElse(List.of()).stream()
                .filter(tv -> "ISIN".equalsIgnoreCase(tv.m_tag))
                .map(tv -> tv.m_value)
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElseThrow(() -> new BrokerException("IBKR returned no ISIN for " + c.symbol()
                        + " (conid " + c.conid() + ")"));
        ZoneId zone = IbFormats.zone(d.timeZoneId());
        LocalDate today = LocalDate.now(clock.withZone(zone));
        return new ContractInfo(c.conid(), c.symbol(), c.primaryExch(), c.currency(), isin,
                IbFormats.blankToNull(d.longName()), IbFormats.blankToNull(d.industry()), d.timeZoneId(),
                IbFormats.sessionOn(d.liquidHours(), zone, today));
    }

    // ======================================================================
    // Write methods: not in this stage
    // ======================================================================

    @Override
    public PlacedBracket placeBracket(BracketOrder order) {
        throw new UnsupportedOperationException("placeBracket is not implemented yet (read-only stage)");
    }

    @Override
    public void modifyStop(String stopOrderRef, BigDecimal newStopPrice) {
        throw new UnsupportedOperationException("modifyStop is not implemented yet (read-only stage)");
    }

    @Override
    public void cancel(String orderRef) {
        throw new UnsupportedOperationException("cancel is not implemented yet (read-only stage)");
    }

    @Override
    public int marketSell(long conid, BigDecimal quantity, String orderRef) {
        throw new UnsupportedOperationException("marketSell is not implemented yet (read-only stage)");
    }

    // ======================================================================
    // EWrapper: runs on the ibkr-msgs thread (connectionClosed and error(Exception) on ibkr-reader)
    // ======================================================================

    private final class Wrapper extends DefaultEWrapper {

        // ---- connection -----------------------------------------------------

        @Override
        public void nextValidId(int orderId) {
            nextOrderId.accumulateAndGet(orderId, Math::max);
            handshake.complete(orderId);
        }

        @Override
        public void managedAccounts(String accountsList) {
            managedAccounts = accountsList;
        }

        @Override
        public void connectAck() {
            LOG.debug("IBKR connectAck");
        }

        @Override
        public void connectionClosed() {
            if (!stopped) {
                LOG.info("IBKR connection closed");
            }
            connectionEvent(IbkrBroker.this::onConnectionLost);
        }

        @Override
        public void error(Exception e) {
            if (stopped) {
                LOG.debugf("IBKR exception after stop: %s", e.toString());
            } else {
                LOG.warnf("IBKR API exception: %s", e.toString());
                LOG.debug("IBKR API exception", e);
            }
            connectionEvent(IbkrBroker.this::watchdog);
        }

        @Override
        public void error(String str) {
            LOG.warnf("IBKR API error: %s", str);
        }

        @Override
        public void error(int id, long errorTime, int errorCode, String errorMsg, String advancedOrderRejectJson) {
            String extra = advancedOrderRejectJson == null || advancedOrderRejectJson.isBlank()
                    ? "" : " " + advancedOrderRejectJson;
            if (informational(errorCode)) {
                LOG.infof("IBKR %d (id %d): %s%s", errorCode, id, errorMsg, extra);
            } else {
                LOG.warnf("IBKR %d (id %d): %s%s", errorCode, id, errorMsg, extra);
            }

            switch (errorCode) {
                case 1100 -> { // connectivity between IB Gateway and IBKR lost
                    if (state == ConnectionState.CONNECTED) {
                        state = ConnectionState.IB_SERVER_LOST;
                    }
                }
                case 1101, 1102 -> { // restored (1101: data lost, 1102: data maintained)
                    if (state == ConnectionState.IB_SERVER_LOST) {
                        state = ConnectionState.CONNECTED;
                        fireReconnected(BrokerReconnected.Reason.IB_SERVER_RESTORED);
                    }
                }
                case 326, 502, 504, 507 -> // clientId in use, couldn't connect, not connected, bad message
                        handshake.completeExceptionally(new BrokerUnavailableException(errorCode + ": " + errorMsg));
                default -> {
                    if (id >= 0 && !informational(errorCode)) {
                        Pending<?> p = pending.get(id);
                        if (p != null) {
                            p.future.completeExceptionally(new BrokerException(errorCode, errorMsg));
                        }
                    }
                }
            }
        }

        // ---- account summary --------------------------------------------------

        @Override
        public void accountSummary(int reqId, String account, String tag, String value, String currency) {
            SummaryReq r = pendingAs(reqId, SummaryReq.class);
            if (r != null) {
                r.values.computeIfAbsent(account, a -> new TreeMap<>()).put(tag, new String[]{value, currency});
            }
        }

        @Override
        public void accountSummaryEnd(int reqId) {
            SummaryReq r = pendingAs(reqId, SummaryReq.class);
            if (r != null) {
                try {
                    r.future.complete(toSummary(r));
                } catch (RuntimeException e) {
                    r.future.completeExceptionally(e);
                }
            }
        }

        // ---- positions --------------------------------------------------------

        @Override
        public void position(String account, Contract contract, Decimal pos, double avgCost) {
            PositionsReq r = pendingAs(POSITIONS_KEY, PositionsReq.class);
            BigDecimal qty = IbFormats.quantity(pos);
            if (r != null && qty != null && qty.signum() != 0) {
                r.items.add(new BrokerPosition(account, contract.conid(), contract.symbol(), contract.currency(),
                        qty, IbFormats.amount(avgCost)));
            }
        }

        @Override
        public void positionEnd() {
            PositionsReq r = pendingAs(POSITIONS_KEY, PositionsReq.class);
            if (r != null) {
                r.future.complete(List.copyOf(r.items));
            }
        }

        // ---- open orders ------------------------------------------------------

        @Override
        public void openOrder(int orderId, Contract contract, Order order, OrderState orderState) {
            OpenOrdersReq r = pendingAs(OPEN_ORDERS_KEY, OpenOrdersReq.class);
            if (r == null) {
                LOG.debugf("Unsolicited openOrder %d %s %s", orderId, order.orderRef(), orderState.getStatus());
                return;
            }
            long key = order.permId() != 0 ? order.permId() : -orderId;
            r.items.put(key, new BrokerOpenOrder(
                    orderId,
                    order.permId(),
                    order.clientId(),
                    order.parentId(),
                    IbFormats.blankToNull(order.orderRef()),
                    order.account(),
                    contract.conid(),
                    contract.symbol(),
                    "BUY".equals(order.getAction()) ? Side.BUY : Side.SELL,
                    order.getOrderType(),
                    IbFormats.quantity(order.totalQuantity()),
                    IbFormats.price(order.lmtPrice()),
                    IbFormats.price(order.auxPrice()),
                    order.getTif(),
                    orderState.getStatus()));
        }

        @Override
        public void openOrderEnd() {
            OpenOrdersReq r = pendingAs(OPEN_ORDERS_KEY, OpenOrdersReq.class);
            if (r != null) {
                r.future.complete(List.copyOf(r.items.values()));
            }
        }

        @Override
        public void orderStatus(int orderId, String status, Decimal filled, Decimal remaining, double avgFillPrice,
                                long permId, int parentId, double lastFillPrice, int clientId, String whyHeld,
                                double mktCapPrice) {
            LOG.debugf("orderStatus %d perm %d %s filled %s remaining %s", orderId, permId, status, filled, remaining);
        }

        // ---- executions -------------------------------------------------------

        @Override
        public void execDetails(int reqId, Contract contract, Execution execution) {
            ExecutionsReq r = pendingAs(reqId, ExecutionsReq.class);
            if (r != null) {
                r.items.add(new RawExecution(contract, execution));
            } else {
                LOG.infof("Execution %s %s %s %s @ %s ref %s", execution.execId(), execution.side(),
                        execution.shares(), contract.symbol(), execution.price(), execution.orderRef());
            }
        }

        @Override
        public void execDetailsEnd(int reqId) {
            ExecutionsReq r = pendingAs(reqId, ExecutionsReq.class);
            if (r != null) {
                r.future.complete(List.copyOf(r.items));
            }
        }

        @Override
        public void commissionAndFeesReport(CommissionAndFeesReport report) {
            synchronized (commissions) {
                commissions.put(report.execId(), report);
                commissions.notifyAll();
            }
        }

        // ---- market data ------------------------------------------------------

        @Override
        public void tickPrice(int tickerId, int field, double price, TickAttrib attribs) {
            SnapshotReq r = pendingAs(tickerId, SnapshotReq.class);
            if (r == null) {
                return;
            }
            BigDecimal p = IbFormats.price(price);
            switch (TickType.get(field)) {
                case BID -> r.bid = p;
                case ASK -> r.ask = p;
                case LAST -> r.last = p;
                case CLOSE -> r.close = p;
                case DELAYED_BID -> { r.bid = p; r.delayed = true; }
                case DELAYED_ASK -> { r.ask = p; r.delayed = true; }
                case DELAYED_LAST -> { r.last = p; r.delayed = true; }
                case DELAYED_CLOSE -> { r.close = p; r.delayed = true; }
                default -> { }
            }
        }

        @Override
        public void marketDataType(int reqId, int marketDataType) {
            SnapshotReq r = pendingAs(reqId, SnapshotReq.class);
            if (r != null && (marketDataType == 3 || marketDataType == 4)) {
                r.delayed = true;
            }
        }

        @Override
        public void tickSnapshotEnd(int reqId) {
            SnapshotReq r = pendingAs(reqId, SnapshotReq.class);
            if (r != null) {
                r.future.complete(new Quote(r.symbol, r.last, r.bid, r.ask, r.close, r.delayed, clock.instant()));
            }
        }

        // ---- contract details -------------------------------------------------

        @Override
        public void contractDetails(int reqId, ContractDetails contractDetails) {
            ContractReq r = pendingAs(reqId, ContractReq.class);
            if (r != null) {
                r.items.add(contractDetails);
            }
        }

        @Override
        public void contractDetailsEnd(int reqId) {
            ContractReq r = pendingAs(reqId, ContractReq.class);
            if (r != null) {
                r.future.complete(List.copyOf(r.items));
            }
        }
    }
}
