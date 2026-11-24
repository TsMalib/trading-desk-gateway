package dev.belis.trader.desk.broker;

import dev.belis.trader.desk.store.Side;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A fill, from execDetails joined with its commission report by execId.
 *
 * @param currency           currency of the price (the contract currency)
 * @param commission         commission and fees; null if the report has not arrived
 * @param commissionCurrency currency of commission and realizedPnl as IBKR reports it
 * @param realizedPnl        realized P&amp;L from the commission report; null for opening fills or if not reported
 */
public record BrokerExecution(String execId, int orderId, long permId, String orderRef, String account,
                              long conid, String symbol, Side side, BigDecimal quantity, BigDecimal price,
                              String currency, BigDecimal commission, String commissionCurrency,
                              BigDecimal realizedPnl, Instant executedAt) {
}
