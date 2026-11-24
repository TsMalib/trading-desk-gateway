package dev.belis.trader.desk.broker;

import dev.belis.trader.desk.store.Side;

import java.math.BigDecimal;

/**
 * A working order as IBKR reports it (from any client, including manual TWS orders).
 *
 * @param orderId    API order id; 0 for orders not placed through the API
 * @param permId     IBKR permanent id, unique across clients
 * @param parentId   order id of the bracket parent, 0 if none
 * @param orderRef   our "P&lt;proposalId&gt;-&lt;ROLE&gt;" reference, or null/blank for external orders
 * @param limitPrice null when not set
 * @param auxPrice   stop trigger price; null when not set
 * @param status     IBKR order status, e.g. Submitted, PreSubmitted
 */
public record BrokerOpenOrder(int orderId, long permId, int clientId, int parentId, String orderRef,
                              String account, long conid, String symbol, Side action, String orderType,
                              BigDecimal quantity, BigDecimal limitPrice, BigDecimal auxPrice,
                              String tif, String status) {
}
