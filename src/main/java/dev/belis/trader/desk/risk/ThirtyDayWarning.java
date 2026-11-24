package dev.belis.trader.desk.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A 30-day-rule hit. Never a failure: shown to the owner with the proposal and stored in proposal.warnings.
 *
 * @param amount         for {@link Type#BUY_AFTER_LOSS_SALE} the realized P&amp;L of the sale (negative);
 *                       for {@link Type#EXIT_AFTER_RECENT_BUY} the value of the buy (quantity * price)
 * @param currency       currency of {@code amount}
 * @param tradeDate      date of the earlier execution in the rule's zone
 * @param windowClosesOn last day the earlier execution is inside the 30-day window; from the next day on
 *                       it no longer matches
 */
public record ThirtyDayWarning(Type type, String isin, Instant executedAt, LocalDate tradeDate, BigDecimal quantity,
                               BigDecimal amount, String currency, LocalDate windowClosesOn, String message) {

    public enum Type {
        /** Buying an ISIN that was sold at a loss within the last 30 days. */
        BUY_AFTER_LOSS_SALE,
        /** Selling an ISIN that was bought within the last 30 days. */
        EXIT_AFTER_RECENT_BUY
    }
}
