package dev.belis.trader.desk.broker;

import dev.belis.trader.desk.config.DeskConfig;
import dev.belis.trader.desk.store.Side;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * In-memory {@link Broker} for tests (desk.broker=fake).
 *
 * <p>Orders fill when a quote set with {@link #setQuote} crosses them, or explicitly with {@link #fill}:
 * LMT BUY fills at the ask when ask &lt;= limit, LMT SELL at the bid when bid &gt;= limit, STP SELL at the
 * bid (or last) when last &lt;= stop, MKT SELL at once at the bid. Every fill costs {@link #COMMISSION}
 * and tracks average cost and realized P&amp;L the way IBKR does (commission included in avg cost).
 * Single currency: the account base currency is assumed to equal the contract currency.
 *
 * <p>Brackets: the children wait (PreSubmitted) until the parent is completely filled, then become active and
 * cancel each other (OCA) when one fills completely. Cancelling a parent that has not filled cancels its
 * children. Cancelling a parent after a partial fill leaves the children active <em>with their original
 * quantity</em>: the worst case, so the service must resize them (IBKR's behaviour is to be verified on paper).
 *
 * <p>Every order change emits {@link BrokerOrderEvent}s (Status, Fill, Error) the way IBKR pushes them, as CDI
 * events and to {@link #setListener}; they are dropped while not CONNECTED, as IBKR's are.
 */
@ApplicationScoped
@IfBuildProperty(name = "desk.broker", stringValue = "fake")
public class FakeBroker implements Broker {

    public static final BigDecimal COMMISSION = new BigDecimal("1.00");
    public static final String ACCOUNT = "DUFAKE01";

    public static final String PRE_SUBMITTED = "PreSubmitted";
    public static final String SUBMITTED = "Submitted";
    public static final String PENDING_CANCEL = "PendingCancel";
    public static final String FILLED = "Filled";
    public static final String CANCELLED = "Cancelled";
    public static final String INACTIVE = "Inactive";

    private static final class FakeOrder {
        int orderId;
        long permId;
        int parentId;
        String ref;
        long conid;
        Side action;
        String type;
        BigDecimal quantity;
        BigDecimal filled = BigDecimal.ZERO;
        BigDecimal filledValue = BigDecimal.ZERO;
        BigDecimal limit;
        BigDecimal aux;
        String tif;
        String status;
        String ocaGroup;

        boolean working() {
            return SUBMITTED.equals(status) || PRE_SUBMITTED.equals(status) || PENDING_CANCEL.equals(status);
        }

        BigDecimal remaining() {
            return quantity.subtract(filled);
        }
    }

    private record Holding(BigDecimal quantity, BigDecimal avgCost) {
    }

    private record Rejection(int code, String message) {
    }

    @Inject
    Event<BrokerReconnected> reconnected;

    @Inject
    Event<BrokerOrderEvent> orderEvents;

    private Clock clock = Clock.systemUTC();
    private ConnectionState state;
    private String baseCurrency;
    private BigDecimal cash;
    private final Map<String, ContractInfo> contracts = new HashMap<>();
    private final Map<String, Quote> quotes = new HashMap<>();
    private final Map<Long, Holding> holdings = new LinkedHashMap<>();
    private final List<FakeOrder> orders = new ArrayList<>();
    private final List<BrokerExecution> executions = new ArrayList<>();
    private int nextOrderId;
    private long nextExecId;
    private boolean holdCancels;
    private Rejection rejectNext;
    private RuntimeException timeoutNext;
    private Consumer<BrokerOrderEvent> listener;

    public FakeBroker() {
        reset();
    }

    // ======================================================================
    // Test controls
    // ======================================================================

    /** Connected, 100,000 USD cash, nothing else. */
    public synchronized void reset() {
        state = ConnectionState.CONNECTED;
        baseCurrency = "USD";
        cash = new BigDecimal("100000.00");
        contracts.clear();
        quotes.clear();
        holdings.clear();
        orders.clear();
        executions.clear();
        nextOrderId = 1;
        nextExecId = 1;
        holdCancels = false;
        rejectNext = null;
        timeoutNext = null;
    }

    public synchronized void setClock(Clock clock) {
        this.clock = clock;
    }

    public synchronized void setConnectionState(ConnectionState state) {
        this.state = state;
    }

    /** Receives every order event, also outside CDI (unit tests). */
    public synchronized void setListener(Consumer<BrokerOrderEvent> listener) {
        this.listener = listener;
    }

    /** Sets the state to CONNECTED and fires {@link BrokerReconnected} synchronously. */
    public void simulateReconnect() {
        synchronized (this) {
            state = ConnectionState.CONNECTED;
        }
        if (reconnected != null) {
            reconnected.fire(new BrokerReconnected(clock.instant(), BrokerReconnected.Reason.SOCKET_RECONNECTED));
        }
    }

    public synchronized void setCash(String baseCurrency, BigDecimal cash) {
        this.baseCurrency = baseCurrency;
        this.cash = cash;
    }

    public synchronized void addContract(ContractInfo contract) {
        contracts.put(contract.symbol().toUpperCase(), contract);
    }

    /** A US stock trading 09:30-16:00 New York today. */
    public ContractInfo addStock(String symbol, long conid, String isin, String industry) {
        ZoneId ny = DeskConfig.MARKET_ZONE;
        LocalDate today = LocalDate.now(clock.withZone(ny));
        TradingSession session = new TradingSession(today,
                today.atTime(9, 30).atZone(ny).toInstant(), today.atTime(16, 0).atZone(ny).toInstant());
        ContractInfo c = new ContractInfo(conid, symbol.toUpperCase(), "NASDAQ", "USD", isin, symbol + " Inc",
                industry, "US/Eastern", Optional.of(session));
        addContract(c);
        return c;
    }

    /** Sets the quote and fills any working order it crosses. */
    public synchronized void setQuote(String symbol, BigDecimal bid, BigDecimal ask, BigDecimal last) {
        String s = symbol.toUpperCase();
        quotes.put(s, new Quote(s, last, bid, ask, null, false, clock.instant()));
        ContractInfo c = contracts.get(s);
        if (c != null) {
            match(c.conid());
        }
    }

    /** An existing (e.g. EXTERNAL) position, without an execution. */
    public synchronized void setPosition(long conid, BigDecimal quantity, BigDecimal avgCost) {
        if (quantity.signum() == 0) {
            holdings.remove(conid);
        } else {
            holdings.put(conid, new Holding(quantity, avgCost));
        }
    }

    /** A working order placed outside this service (blank orderRef), e.g. a manual stop entered in TWS. */
    public synchronized BrokerOpenOrder addExternalOrder(long conid, Side action, String type, BigDecimal quantity,
                                                         BigDecimal limit, BigDecimal aux) {
        return toOpenOrder(newOrder(0, "", conid, action, type, quantity, limit, aux, "GTC", 0, SUBMITTED));
    }

    /**
     * A trade made outside this service (e.g. by hand in TWS): changes the position and is reported by
     * {@link #executionsSince}, with a blank orderRef. No event is fired: IBKR does not tell this API client
     * about other clients' fills.
     */
    public synchronized BrokerExecution externalFill(long conid, Side side, BigDecimal quantity, BigDecimal price) {
        FakeOrder o = newOrder(0, "", conid, side, "MKT", quantity, null, null, "DAY", 0, SUBMITTED);
        Consumer<BrokerOrderEvent> l = listener;
        ConnectionState s = state;
        listener = null;
        state = ConnectionState.DISCONNECTED; // execute() emits nothing while disconnected
        try {
            return execute(o, price, quantity);
        } finally {
            state = s;
            listener = l;
        }
    }

    /** The working order with this ref is cancelled without telling this client (e.g. by hand in TWS while disconnected). */
    public synchronized void cancelSilently(String orderRef) {
        findWorking(orderRef).status = CANCELLED;
    }

    /** IBKR's execution history no longer covers anything that happened so far (e.g. after a few days). */
    public synchronized void forgetExecutions() {
        executions.clear();
    }

    /**
     * A corporate action (e.g. a split): the position becomes {@code quantity} with no execution; working orders
     * of that instrument keep their quantity unless {@code orderQuantity} is given.
     */
    public synchronized void corporateAction(long conid, BigDecimal quantity, BigDecimal avgCost,
                                             BigDecimal orderQuantity) {
        setPosition(conid, quantity, avgCost);
        if (orderQuantity != null) {
            for (FakeOrder o : orders) {
                if (o.conid == conid && o.working()) {
                    o.quantity = orderQuantity;
                }
            }
        }
    }

    /** Fills the rest of the working order with this ref at {@code price}, whatever the quote. */
    public synchronized BrokerExecution fill(String orderRef, BigDecimal price) {
        FakeOrder o = findWorking(orderRef);
        return fill(orderRef, price, o.remaining());
    }

    /** Fills {@code quantity} of the working order with this ref (a partial fill when less than what remains). */
    public synchronized BrokerExecution fill(String orderRef, BigDecimal price, BigDecimal quantity) {
        FakeOrder o = findWorking(orderRef);
        if (!SUBMITTED.equals(o.status) && !PENDING_CANCEL.equals(o.status)) {
            throw new IllegalStateException(orderRef + " is " + o.status + ", not fillable");
        }
        if (quantity.signum() <= 0 || quantity.compareTo(o.remaining()) > 0) {
            throw new IllegalArgumentException("fill " + quantity + " but " + o.remaining() + " remain");
        }
        return execute(o, price, quantity);
    }

    /** From now on cancel() leaves orders PendingCancel (still working, still fillable) until {@link #confirmCancels}. */
    public synchronized void holdCancels(boolean hold) {
        this.holdCancels = hold;
    }

    /** IBKR confirms every PendingCancel order: Cancelled. */
    public synchronized void confirmCancels() {
        for (FakeOrder o : List.copyOf(orders)) {
            if (PENDING_CANCEL.equals(o.status)) {
                cancelNow(o);
            }
        }
    }

    /** The next write call (placeBracket, marketSell, modifyStop, reduceQuantity) is rejected by IBKR. */
    public synchronized void rejectNext(int code, String message) {
        this.rejectNext = new Rejection(code, message);
    }

    /**
     * The connection drops right after the next placeBracket or marketSell is sent: the orders exist at the
     * broker, the state becomes DISCONNECTED (so no events), and the call throws {@link BrokerTimeoutException}.
     */
    public synchronized void dropConnectionAfterNextSend() {
        this.timeoutNext = new BrokerTimeoutException("fake: connection lost after sending; outcome unknown");
    }

    /** IBKR rejects a working order after it was accepted: Error event, then Inactive (with its children). */
    public synchronized void reject(String orderRef, int code, String message) {
        FakeOrder o = findWorking(orderRef);
        emit(new BrokerOrderEvent.Error(o.orderId, o.ref, code, message));
        o.status = INACTIVE;
        emitStatus(o);
        for (FakeOrder child : orders) {
            if (child.parentId == o.orderId && child.working()) {
                child.status = CANCELLED;
                emitStatus(child);
            }
        }
    }

    /** Status of the order with this ref (working or not); null when there is none. */
    public synchronized String statusOf(String orderRef) {
        return orders.stream().filter(o -> o.ref.equals(orderRef)).reduce((a, b) -> b).map(o -> o.status)
                .orElse(null);
    }

    /** The open order with this ref, if working. */
    public synchronized Optional<BrokerOpenOrder> openOrder(String orderRef) {
        return orders.stream().filter(o -> o.ref.equals(orderRef) && o.working()).findFirst().map(this::toOpenOrder);
    }

    // ======================================================================
    // Broker: read
    // ======================================================================

    @Override
    public synchronized ConnectionState connectionState() {
        return state;
    }

    @Override
    public synchronized AccountSummary accountSummary() {
        requireConnected();
        BigDecimal value = cash;
        for (Map.Entry<Long, Holding> e : holdings.entrySet()) {
            BigDecimal mark = Optional.ofNullable(symbolOf(e.getKey())).map(quotes::get).map(Quote::last)
                    .orElse(e.getValue().avgCost());
            value = value.add(e.getValue().quantity().multiply(mark));
        }
        return new AccountSummary(ACCOUNT, baseCurrency, value.setScale(2, RoundingMode.HALF_UP), cash);
    }

    @Override
    public synchronized List<BrokerPosition> positions() {
        requireConnected();
        List<BrokerPosition> out = new ArrayList<>();
        holdings.forEach((conid, h) -> {
            ContractInfo c = contractOf(conid);
            out.add(new BrokerPosition(ACCOUNT, conid, c == null ? null : c.symbol(),
                    c == null ? baseCurrency : c.currency(), h.quantity(), h.avgCost()));
        });
        return out;
    }

    @Override
    public synchronized List<BrokerOpenOrder> openOrders() {
        requireConnected();
        return orders.stream().filter(FakeOrder::working).map(this::toOpenOrder).toList();
    }

    @Override
    public synchronized List<BrokerExecution> executionsSince(Instant since) {
        requireConnected();
        return executions.stream().filter(e -> !e.executedAt().isBefore(since)).toList();
    }

    @Override
    public synchronized Quote quote(String symbol) {
        requireConnected();
        Quote q = quotes.get(symbol.trim().toUpperCase());
        if (q == null) {
            throw new BrokerException(10168, "no market data for " + symbol);
        }
        return q;
    }

    @Override
    public synchronized ContractInfo contract(String symbol) {
        requireConnected();
        ContractInfo c = contracts.get(symbol.trim().toUpperCase());
        if (c == null) {
            throw new BrokerException(200, "No security definition has been found for " + symbol);
        }
        return c;
    }

    // ======================================================================
    // Broker: write (in memory)
    // ======================================================================

    @Override
    public synchronized int nextOrderId() {
        requireConnected();
        return nextOrderId++;
    }

    @Override
    public synchronized void placeBracket(BracketOrder b, PlacedBracket ids) {
        requireConnected();
        if (contractOf(b.conid()) == null) {
            throw new BrokerException(200, "unknown conid " + b.conid());
        }
        if (b.quantity().signum() <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (b.stopPrice().compareTo(b.entryLimit()) >= 0 || b.targetPrice().compareTo(b.entryLimit()) <= 0) {
            throw new IllegalArgumentException("need stop < entry < target: " + b);
        }
        for (String ref : List.of(b.entryRef(), b.targetRef(), b.stopRef())) {
            requireNewRef(ref);
        }
        throwIfRejected();
        dropIfAsked();
        FakeOrder parent = newOrder(ids.entryOrderId(), b.entryRef(), b.conid(), Side.BUY, "LMT", b.quantity(),
                b.entryLimit(), null, "DAY", 0, SUBMITTED);
        String oca = "OCA-" + parent.orderId;
        FakeOrder target = newOrder(ids.targetOrderId(), b.targetRef(), b.conid(), Side.SELL, "LMT", b.quantity(),
                b.targetPrice(), null, "GTC", parent.orderId, PRE_SUBMITTED);
        FakeOrder stop = newOrder(ids.stopOrderId(), b.stopRef(), b.conid(), Side.SELL, "STP", b.quantity(), null,
                b.stopPrice(), "GTC", parent.orderId, PRE_SUBMITTED);
        target.ocaGroup = oca;
        stop.ocaGroup = oca;
        emitStatus(parent);
        emitStatus(target);
        emitStatus(stop);
        match(b.conid());
        throwIfTimedOut();
    }

    @Override
    public synchronized void modifyStop(String stopOrderRef, BigDecimal newStopPrice) {
        requireConnected();
        FakeOrder o = findWorking(stopOrderRef);
        if (!"STP".equals(o.type)) {
            throw new IllegalArgumentException(stopOrderRef + " is not a stop order");
        }
        if (newStopPrice.compareTo(o.aux) < 0) {
            throw new IllegalArgumentException("stops only move up: " + o.aux + " -> " + newStopPrice);
        }
        throwIfRejected();
        o.aux = newStopPrice;
        emitStatus(o);
        match(o.conid);
    }

    @Override
    public synchronized void reduceQuantity(String orderRef, BigDecimal newQuantity) {
        requireConnected();
        FakeOrder o = findWorking(orderRef);
        if (newQuantity.signum() <= 0 || newQuantity.compareTo(o.quantity) >= 0
                || newQuantity.compareTo(o.filled) < 0) {
            throw new IllegalArgumentException("cannot change quantity of " + orderRef + " from " + o.quantity
                    + " to " + newQuantity);
        }
        throwIfRejected();
        o.quantity = newQuantity;
        emitStatus(o);
    }

    @Override
    public synchronized void cancel(String orderRef) {
        requireConnected();
        FakeOrder o = findWorking(orderRef);
        if (PENDING_CANCEL.equals(o.status)) {
            return;
        }
        if (holdCancels) {
            o.status = PENDING_CANCEL;
            emitStatus(o);
            return;
        }
        cancelNow(o);
    }

    @Override
    public synchronized void marketSell(long conid, BigDecimal quantity, String orderRef, int orderId) {
        requireConnected();
        requireNewRef(orderRef);
        Holding h = holdings.get(conid);
        BigDecimal held = h == null ? BigDecimal.ZERO : h.quantity();
        if (quantity.signum() <= 0 || quantity.compareTo(held) > 0) {
            throw new IllegalArgumentException("sell " + quantity + " but position is " + held);
        }
        Quote q = quotes.get(symbolOf(conid));
        BigDecimal price = q == null ? null : Optional.ofNullable(q.bid()).orElse(q.last());
        if (price == null) {
            throw new BrokerException("no quote to fill market sell of conid " + conid);
        }
        throwIfRejected();
        dropIfAsked();
        FakeOrder o = newOrder(orderId, orderRef, conid, Side.SELL, "MKT", quantity, null, null, "DAY", 0, SUBMITTED);
        emitStatus(o);
        execute(o, price, quantity);
        throwIfTimedOut();
    }

    // ======================================================================
    // Matching
    // ======================================================================

    private void match(long conid) {
        Quote q = quotes.get(symbolOf(conid));
        if (q == null) {
            return;
        }
        boolean filledSomething = true;
        while (filledSomething) {
            filledSomething = false;
            for (FakeOrder o : List.copyOf(orders)) {
                if (o.conid != conid || !SUBMITTED.equals(o.status)) {
                    continue;
                }
                BigDecimal price = crossPrice(o, q);
                if (price != null) {
                    execute(o, price, o.remaining());
                    filledSomething = true; // children may now be active
                }
            }
        }
    }

    private static BigDecimal crossPrice(FakeOrder o, Quote q) {
        if ("LMT".equals(o.type) && o.action == Side.BUY && q.ask() != null && q.ask().compareTo(o.limit) <= 0) {
            return q.ask();
        }
        if ("LMT".equals(o.type) && o.action == Side.SELL && q.bid() != null && q.bid().compareTo(o.limit) >= 0) {
            return q.bid();
        }
        if ("STP".equals(o.type) && o.action == Side.SELL && q.last() != null && q.last().compareTo(o.aux) <= 0) {
            return Optional.ofNullable(q.bid()).orElse(q.last());
        }
        return null;
    }

    private BrokerExecution execute(FakeOrder o, BigDecimal price, BigDecimal qty) {
        Holding h = holdings.get(o.conid);
        BigDecimal held = h == null ? BigDecimal.ZERO : h.quantity();
        BigDecimal gross = qty.multiply(price);
        BigDecimal realized = null;
        if (o.action == Side.BUY) {
            BigDecimal cost = (h == null ? BigDecimal.ZERO : held.multiply(h.avgCost())).add(gross).add(COMMISSION);
            BigDecimal newQty = held.add(qty);
            holdings.put(o.conid, new Holding(newQty, cost.divide(newQty, 6, RoundingMode.HALF_UP)));
            cash = cash.subtract(gross).subtract(COMMISSION);
        } else {
            if (qty.compareTo(held) > 0) {
                throw new IllegalStateException("fill would sell " + qty + " but position is " + held);
            }
            realized = price.subtract(h.avgCost()).multiply(qty).subtract(COMMISSION)
                    .setScale(6, RoundingMode.HALF_UP);
            setPosition(o.conid, held.subtract(qty), h.avgCost());
            cash = cash.add(gross).subtract(COMMISSION);
        }
        o.filled = o.filled.add(qty);
        o.filledValue = o.filledValue.add(gross);
        boolean complete = o.remaining().signum() == 0;

        ContractInfo c = contractOf(o.conid);
        BrokerExecution e = new BrokerExecution("fake." + (nextExecId++), o.orderId, o.permId, o.ref, ACCOUNT,
                o.conid, c == null ? null : c.symbol(), o.action, qty, price,
                c == null ? baseCurrency : c.currency(), COMMISSION, baseCurrency, realized, clock.instant());
        executions.add(e);
        emit(new BrokerOrderEvent.Fill(e));

        if (complete) {
            o.status = FILLED;
        }
        emitStatus(o);
        if (complete) {
            for (FakeOrder other : orders) {
                if (other.parentId == o.orderId && PRE_SUBMITTED.equals(other.status)) {
                    other.status = SUBMITTED;
                    emitStatus(other);
                }
                if (o.ocaGroup != null && other != o && o.ocaGroup.equals(other.ocaGroup) && other.working()) {
                    other.status = CANCELLED;
                    emitStatus(other);
                }
            }
        }
        return e;
    }

    private void cancelNow(FakeOrder o) {
        o.status = CANCELLED;
        emitStatus(o);
        for (FakeOrder child : orders) {
            if (child.parentId == o.orderId && child.working()) {
                if (o.filled.signum() > 0) {
                    // the parent's filled part is a position: its children stay, with their original quantity
                    if (PRE_SUBMITTED.equals(child.status)) {
                        child.status = SUBMITTED;
                        emitStatus(child);
                    }
                } else {
                    child.status = CANCELLED;
                    emitStatus(child);
                }
            }
        }
    }

    // ======================================================================
    // Helpers
    // ======================================================================

    private void emitStatus(FakeOrder o) {
        BigDecimal avg = o.filled.signum() == 0 ? null
                : o.filledValue.divide(o.filled, 6, RoundingMode.HALF_UP);
        emit(new BrokerOrderEvent.Status(o.orderId, o.permId, o.ref, o.status, o.filled, o.remaining(), avg, ""));
    }

    private void emit(BrokerOrderEvent e) {
        if (state != ConnectionState.CONNECTED) {
            return; // IBKR cannot tell a client that is not connected
        }
        if (listener != null) {
            listener.accept(e);
        }
        if (orderEvents != null) {
            orderEvents.fire(e);
        }
    }

    private void throwIfRejected() {
        Rejection r = rejectNext;
        if (r != null) {
            rejectNext = null;
            throw new BrokerException(r.code(), r.message());
        }
    }

    private void dropIfAsked() {
        if (timeoutNext != null) {
            state = ConnectionState.DISCONNECTED;
        }
    }

    private void throwIfTimedOut() {
        RuntimeException t = timeoutNext;
        if (t != null) {
            timeoutNext = null;
            throw t;
        }
    }

    private void requireConnected() {
        if (state != ConnectionState.CONNECTED) {
            throw new BrokerUnavailableException("fake broker is " + state);
        }
    }

    private void requireNewRef(String ref) {
        if (orders.stream().anyMatch(o -> o.ref.equals(ref))) {
            throw new BrokerException("orderRef already used: " + ref);
        }
    }

    private FakeOrder findWorking(String ref) {
        return orders.stream().filter(o -> o.ref.equals(ref) && o.working()).findFirst()
                .orElseThrow(() -> new BrokerException("no working order with ref " + ref));
    }

    private FakeOrder newOrder(int orderId, String ref, long conid, Side action, String type, BigDecimal qty,
                               BigDecimal limit, BigDecimal aux, String tif, int parentId, String status) {
        FakeOrder o = new FakeOrder();
        o.orderId = orderId;
        if (orderId >= nextOrderId) {
            nextOrderId = orderId + 1;
        }
        o.permId = 1_000_000L + orders.size() + 1;
        o.ref = ref;
        o.conid = conid;
        o.action = action;
        o.type = type;
        o.quantity = qty;
        o.limit = limit;
        o.aux = aux;
        o.tif = tif;
        o.parentId = parentId;
        o.status = status;
        orders.add(o);
        return o;
    }

    private BrokerOpenOrder toOpenOrder(FakeOrder o) {
        ContractInfo c = contractOf(o.conid);
        return new BrokerOpenOrder(o.orderId, o.permId, 0, o.parentId, o.ref, ACCOUNT, o.conid,
                c == null ? null : c.symbol(), o.action, o.type, o.quantity, o.limit, o.aux, o.tif, o.status);
    }

    private ContractInfo contractOf(long conid) {
        return contracts.values().stream().filter(c -> c.conid() == conid).findFirst().orElse(null);
    }

    private String symbolOf(long conid) {
        ContractInfo c = contractOf(conid);
        return c == null ? null : c.symbol();
    }
}
