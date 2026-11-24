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

    /**
     * Places parent LMT BUY (DAY) + child LMT SELL target (GTC) + child STP SELL stop (GTC).
     * Parent and target transmit=false, stop transmit=true.
     */
    PlacedBracket placeBracket(BracketOrder order);

    /** Moves the stop of the working STP order with this ref. Stops only move up. */
    void modifyStop(String stopOrderRef, BigDecimal newStopPrice);

    void cancel(String orderRef);

    /** Market SELL; quantity must not exceed the position IBKR reports. Returns the IBKR order id. */
    int marketSell(long conid, BigDecimal quantity, String orderRef);
}
