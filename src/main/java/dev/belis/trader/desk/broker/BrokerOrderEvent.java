package dev.belis.trader.desk.broker;

import java.math.BigDecimal;

/**
 * CDI event: something happened to an order at IBKR (orderStatus, execDetails, an order error). Fired for every
 * order IBKR tells this client about, ours or not; observers match {@code orderRef} ("P&lt;id&gt;-ROLE").
 *
 * <p>{@link IbkrBroker} fires them in the order IBKR sent them, on its {@code ibkr-events} thread, so observers
 * may call the broker. Events are lost while disconnected: after a reconnect, compare with
 * {@link Broker#openOrders()} and {@link Broker#executionsSince}. IBKR repeats events; observers must be
 * idempotent.
 */
public sealed interface BrokerOrderEvent {

    int orderId();

    /** Our "P&lt;id&gt;-ROLE" ref, or null when the broker does not know it (external order, or not seen yet). */
    String orderRef();

    /**
     * An orderStatus callback.
     *
     * @param status       IBKR status: PendingSubmit, PreSubmitted, Submitted, PendingCancel, Cancelled,
     *                     ApiCancelled, Filled, Inactive
     * @param filled       cumulative filled quantity; null when unknown (e.g. a status rebuilt from open orders)
     * @param avgFillPrice null when nothing filled or unknown
     */
    record Status(int orderId, long permId, String orderRef, String status, BigDecimal filled,
                  BigDecimal remaining, BigDecimal avgFillPrice, String whyHeld) implements BrokerOrderEvent {
    }

    /** A fill (execDetails), joined with its commission report when that arrived in time. */
    record Fill(BrokerExecution execution) implements BrokerOrderEvent {
        @Override
        public int orderId() {
            return execution.orderId();
        }

        @Override
        public String orderRef() {
            return execution.orderRef();
        }
    }

    /**
     * IBKR reported an error for this order outside a write call (a write call throws its error instead), e.g.
     * a rejection after the order was accepted. Code 202 (cancel confirmed) is not reported here: a Status
     * Cancelled follows.
     */
    record Error(int orderId, String orderRef, int code, String message) implements BrokerOrderEvent {
    }
}
