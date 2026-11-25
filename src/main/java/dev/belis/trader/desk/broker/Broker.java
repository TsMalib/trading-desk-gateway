package dev.belis.trader.desk.broker;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The only way the service talks to the broker. Returns plain records, never IBKR classes.
 * IBKR is the source of truth for positions, orders and executions.
 *
 * <p>Every method blocks until IBKR answers and throws {@link BrokerUnavailableException} when not
 * connected, {@link BrokerTimeoutException} when IBKR does not answer, and {@link BrokerException}
 * when IBKR rejects the request.
 */
public interface Broker {

    // ---- connection -------------------------------------------------------

    ConnectionState connectionState();

    default boolean isConnected() {
        return connectionState() == ConnectionState.CONNECTED;
    }

    // ---- read -------------------------------------------------------------

    /** Net liquidation and cash in the account's base currency. */
    AccountSummary accountSummary();

    /** Non-zero positions. */
    List<BrokerPosition> positions();

    /** All working orders, from every API client and from TWS. */
    List<BrokerOpenOrder> openOrders();

    /**
     * Executions at or after {@code since}, with commission and realized P&amp;L where IBKR reported them.
     * IBKR only returns recent executions (normally since the last Gateway restart / the current day).
     */
    List<BrokerExecution> executionsSince(Instant since);

    /** Snapshot quote for a US stock (STK, SMART, USD). */
    Quote quote(String symbol);

    /**
     * Contract details for a US stock (STK, SMART, USD).
     *
     * @throws BrokerException if the symbol is unknown, ambiguous, or IBKR returns no ISIN
     */
    ContractInfo contract(String symbol);

    // ---- write ------------------------------------------------------------
    //
    // Write calls wait until IBKR acknowledged the order (or rejected it). What they throw says what was sent:
    //   BrokerUnavailableException  nothing was sent
    //   BrokerTimeoutException      it was sent but IBKR did not answer: the outcome is unknown, check openOrders
    //   BrokerException             IBKR rejected it (code() is the IBKR error code)
    // Later news about an order (fills, status changes, late rejections) arrives as BrokerOrderEvent.

    /**
     * Reserves the next IBKR order id, so the order can be stored before it is sent.
     *
     * @throws BrokerUnavailableException when not connected
     */
    int nextOrderId();

    /**
     * Places parent LMT BUY (DAY) + child LMT SELL target (GTC) + child STP SELL stop (GTC) under the order ids
     * reserved with {@link #nextOrderId()}. Parent and target transmit=false, stop transmit=true.
     */
    void placeBracket(BracketOrder order, PlacedBracket ids);

    /** {@link #placeBracket(BracketOrder, PlacedBracket)} with freshly reserved ids. */
    default PlacedBracket placeBracket(BracketOrder order) {
        PlacedBracket ids = new PlacedBracket(nextOrderId(), nextOrderId(), nextOrderId());
        placeBracket(order, ids);
        return ids;
    }

    /**
     * Moves the trigger of the working STP SELL order with this ref, in place (same order id).
     *
     * @throws IllegalArgumentException when the new stop is lower than the current one: stops only move up
     */
    void modifyStop(String stopOrderRef, BigDecimal newStopPrice);

    /**
     * Lowers the quantity of the working order with this ref, in place.
     *
     * @throws IllegalArgumentException when {@code newQuantity} is not positive or not lower than the current one
     */
    void reduceQuantity(String orderRef, BigDecimal newQuantity);

    /**
     * Asks IBKR to cancel the working order with this ref. Returns once the request is sent: the order stays
     * in {@link #openOrders()} (PendingCancel) until IBKR confirms; a refusal arrives as a BrokerOrderEvent.
     *
     * @throws BrokerException when no working order has this ref
     */
    void cancel(String orderRef);

    /**
     * Market SELL (DAY) under an order id reserved with {@link #nextOrderId()}.
     *
     * @throws IllegalArgumentException when {@code quantity} exceeds the position IBKR reports
     */
    void marketSell(long conid, BigDecimal quantity, String orderRef, int orderId);

    /** {@link #marketSell(long, BigDecimal, String, int)} with a freshly reserved id; returns that id. */
    default int marketSell(long conid, BigDecimal quantity, String orderRef) {
        int id = nextOrderId();
        marketSell(conid, quantity, orderRef, id);
        return id;
    }
}
