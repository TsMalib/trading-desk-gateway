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

/**
 * In-memory {@link Broker} for tests (desk.broker=fake).
 *
 * <p>Orders fill when a quote set with {@link #setQuote} crosses them, or explicitly with {@link #fill}:
 * LMT BUY fills at the ask when ask &lt;= limit, LMT SELL at the bid when bid &gt;= limit, STP SELL at the
 * bid (or last) when last &lt;= stop, MKT SELL at once at the bid. Bracket children wait (PreSubmitted)
 * until the parent fills and cancel each other (OCA) when one fills. Every fill costs {@link #COMMISSION}
 * and tracks average cost and realized P&amp;L the way IBKR does (commission included in avg cost).
 * Single currency: the account base currency is assumed to equal the contract currency.
 */
@ApplicationScoped
@IfBuildProperty(name = "desk.broker", stringValue = "fake")
public class FakeBroker implements Broker {

    public static final BigDecimal COMMISSION = new BigDecimal("1.00");
    public static final String ACCOUNT = "DUFAKE01";

    private static final String PRE_SUBMITTED = "PreSubmitted";
    private static final String SUBMITTED = "Submitted";
    private static final String FILLED = "Filled";
    private static final String CANCELLED = "Cancelled";

    private static final class FakeOrder {
        int orderId;
        long permId;
        int parentId;
        String ref;
        long conid;
        Side action;
        String type;
        BigDecimal quantity;
        BigDecimal limit;
        BigDecimal aux;
        String tif;
        String status;
        String ocaGroup;

        boolean working() {
            return SUBMITTED.equals(status) || PRE_SUBMITTED.equals(status);
        }
    }

    private record Holding(BigDecimal quantity, BigDecimal avgCost) {
    }

    @Inject
    Event<BrokerReconnected> reconnected;

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
    }

    public synchronized void setClock(Clock clock) {
        this.clock = clock;
    }

    public synchronized void setConnectionState(ConnectionState state) {
        this.state = state;
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
        return toOpenOrder(newOrder("", conid, action, type, quantity, limit, aux, "GTC", 0, SUBMITTED));
    }

    /** Fills the working order with this ref at {@code price}, whatever the quote. */
    public synchronized BrokerExecution fill(String orderRef, BigDecimal price) {
        FakeOrder o = findWorking(orderRef);
        if (!SUBMITTED.equals(o.status)) {
            throw new IllegalStateException(orderRef + " is " + o.status + ", not fillable");
        }
        return execute(o, price);
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
    public synchronized PlacedBracket placeBracket(BracketOrder b) {
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
        FakeOrder parent = newOrder(b.entryRef(), b.conid(), Side.BUY, "LMT", b.quantity(), b.entryLimit(), null,
                "DAY", 0, SUBMITTED);
        String oca = "OCA-" + parent.orderId;
        FakeOrder target = newOrder(b.targetRef(), b.conid(), Side.SELL, "LMT", b.quantity(), b.targetPrice(), null,
                "GTC", parent.orderId, PRE_SUBMITTED);
        FakeOrder stop = newOrder(b.stopRef(), b.conid(), Side.SELL, "STP", b.quantity(), null, b.stopPrice(),
                "GTC", parent.orderId, PRE_SUBMITTED);
        target.ocaGroup = oca;
        stop.ocaGroup = oca;
        match(b.conid());
        return new PlacedBracket(parent.orderId, target.orderId, stop.orderId);
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
        o.aux = newStopPrice;
        match(o.conid);
    }

    @Override
    public synchronized void cancel(String orderRef) {
        requireConnected();
        FakeOrder o = findWorking(orderRef);
        o.status = CANCELLED;
        for (FakeOrder child : orders) {
            if (child.parentId == o.orderId && child.working()) {
                child.status = CANCELLED;
            }
        }
    }

    @Override
    public synchronized int marketSell(long conid, BigDecimal quantity, String orderRef) {
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
        FakeOrder o = newOrder(orderRef, conid, Side.SELL, "MKT", quantity, null, null, "DAY", 0, SUBMITTED);
        execute(o, price);
        return o.orderId;
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
                    execute(o, price);
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

    private BrokerExecution execute(FakeOrder o, BigDecimal price) {
        Holding h = holdings.get(o.conid);
        BigDecimal held = h == null ? BigDecimal.ZERO : h.quantity();
        BigDecimal gross = o.quantity.multiply(price);
        BigDecimal realized = null;
        if (o.action == Side.BUY) {
            BigDecimal cost = (h == null ? BigDecimal.ZERO : held.multiply(h.avgCost())).add(gross).add(COMMISSION);
            BigDecimal qty = held.add(o.quantity);
            holdings.put(o.conid, new Holding(qty, cost.divide(qty, 6, RoundingMode.HALF_UP)));
            cash = cash.subtract(gross).subtract(COMMISSION);
        } else {
            if (o.quantity.compareTo(held) > 0) {
                throw new IllegalStateException("fill would sell " + o.quantity + " but position is " + held);
            }
            realized = price.subtract(h.avgCost()).multiply(o.quantity).subtract(COMMISSION)
                    .setScale(6, RoundingMode.HALF_UP);
            BigDecimal qty = held.subtract(o.quantity);
            setPosition(o.conid, qty, h.avgCost());
            cash = cash.add(gross).subtract(COMMISSION);
        }
        o.status = FILLED;

        for (FakeOrder other : orders) {
            if (other.parentId == o.orderId && PRE_SUBMITTED.equals(other.status)) {
                other.status = SUBMITTED;
            }
            if (o.ocaGroup != null && other != o && o.ocaGroup.equals(other.ocaGroup) && other.working()) {
                other.status = CANCELLED;
            }
        }

        ContractInfo c = contractOf(o.conid);
        BrokerExecution e = new BrokerExecution("fake." + (nextExecId++), o.orderId, o.permId, o.ref, ACCOUNT,
                o.conid, c == null ? null : c.symbol(), o.action, o.quantity, price,
                c == null ? baseCurrency : c.currency(), COMMISSION, baseCurrency, realized, clock.instant());
        executions.add(e);
        return e;
    }

    // ======================================================================
    // Helpers
    // ======================================================================

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

    private FakeOrder newOrder(String ref, long conid, Side action, String type, BigDecimal qty, BigDecimal limit,
                               BigDecimal aux, String tif, int parentId, String status) {
        FakeOrder o = new FakeOrder();
        o.orderId = nextOrderId++;
        o.permId = 1_000_000L + o.orderId;
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
