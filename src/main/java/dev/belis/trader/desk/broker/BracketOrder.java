package dev.belis.trader.desk.broker;

import dev.belis.trader.desk.store.OrderRole;

import java.math.BigDecimal;

/**
 * Entry bracket: parent LMT BUY (DAY), child LMT SELL target (GTC), child STP SELL stop (GTC).
 * Order refs are derived from the proposal id, see {@link OrderRefs}.
 */
public record BracketOrder(long proposalId, long conid, BigDecimal quantity,
                           BigDecimal entryLimit, BigDecimal targetPrice, BigDecimal stopPrice) {

    public String entryRef() {
        return OrderRefs.of(proposalId, OrderRole.ENTRY);
    }

    public String targetRef() {
        return OrderRefs.of(proposalId, OrderRole.TARGET);
    }

    public String stopRef() {
        return OrderRefs.of(proposalId, OrderRole.STOP);
    }
}
