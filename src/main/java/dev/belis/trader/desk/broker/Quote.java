package dev.belis.trader.desk.broker;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Snapshot quote. Any price may be null when IBKR has none (e.g. no last trade yet).
 *
 * @param close   previous session close
 * @param delayed true when IBKR served delayed data instead of live data; risk checks must not trust it
 * @param at      when the snapshot completed
 */
public record Quote(String symbol, BigDecimal last, BigDecimal bid, BigDecimal ask, BigDecimal close,
                    boolean delayed, Instant at) {
}
