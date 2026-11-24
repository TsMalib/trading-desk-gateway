package dev.belis.trader.desk.broker;

import java.math.BigDecimal;

/**
 * A position as IBKR reports it. Zero positions are not returned.
 *
 * @param avgCost average cost per share in the contract currency, including commissions
 */
public record BrokerPosition(String account, long conid, String symbol, String currency,
                             BigDecimal quantity, BigDecimal avgCost) {
}
