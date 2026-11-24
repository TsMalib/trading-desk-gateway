package dev.belis.trader.desk.risk;

import dev.belis.trader.desk.config.DeskConfig;
import dev.belis.trader.desk.risk.RiskContext.PastExecution;
import dev.belis.trader.desk.risk.ThirtyDayWarning.Type;
import dev.belis.trader.desk.store.Side;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The 30-day rule, as warnings only: it never blocks a trade.
 *
 * <ul>
 *   <li>Buy: every sell of the same ISIN with realized P&amp;L &lt; 0 in the last 30 days.</li>
 *   <li>Exit: every buy of the same ISIN in the last 30 days.</li>
 * </ul>
 *
 * Days are calendar days in {@link #zone()} (default America/New_York, the exchange trade date). An execution on
 * day D matches through D + 30 inclusive; {@link ThirtyDayWarning#windowClosesOn()} is that last day.
 * Pure: no I/O, the caller passes the time and the executions.
 */
public final class ThirtyDayRule {

    public static final int WINDOW_DAYS = 30;

    private final ZoneId zone;

    public ThirtyDayRule() {
        this(DeskConfig.MARKET_ZONE);
    }

    public ThirtyDayRule(ZoneId zone) {
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    public ZoneId zone() {
        return zone;
    }

    /** Warnings for buying {@code isin} at {@code now}. */
    public List<ThirtyDayWarning> forBuy(String isin, Instant now, List<PastExecution> executions) {
        return inWindow(isin, now, executions)
                .filter(e -> e.side() == Side.SELL)
                .filter(e -> e.realizedPnl() != null && e.realizedPnl().signum() < 0)
                .map(e -> warning(Type.BUY_AFTER_LOSS_SALE, e, e.realizedPnl(),
                        e.pnlCurrency() != null ? e.pnlCurrency() : e.currency(),
                        "Sold at a loss of %s %s on %s; buying back before %s may fall under the 30-day rule."))
                .toList();
    }

    /** Warnings for selling (exiting) {@code isin} at {@code now}. */
    public List<ThirtyDayWarning> forExit(String isin, Instant now, List<PastExecution> executions) {
        return inWindow(isin, now, executions)
                .filter(e -> e.side() == Side.BUY)
                .map(e -> warning(Type.EXIT_AFTER_RECENT_BUY, e, value(e), e.currency(),
                        "Bought %s %s worth on %s; selling before %s may fall under the 30-day rule."))
                .toList();
    }

    /** Convenience: uses the context's time and executions. */
    public List<ThirtyDayWarning> forBuy(String isin, RiskContext ctx) {
        return forBuy(isin, ctx.now().toInstant(), ctx.recentExecutions());
    }

    /** Convenience: uses the context's time and executions. */
    public List<ThirtyDayWarning> forExit(String isin, RiskContext ctx) {
        return forExit(isin, ctx.now().toInstant(), ctx.recentExecutions());
    }

    // ---------------------------------------------------------------------------------------------------

    private Stream<PastExecution> inWindow(String isin, Instant now, List<PastExecution> executions) {
        Objects.requireNonNull(now, "now");
        if (isin == null || isin.isBlank() || executions == null) {
            return Stream.empty();
        }
        LocalDate today = now.atZone(zone).toLocalDate();
        return executions.stream()
                .filter(Objects::nonNull)
                .filter(e -> e.executedAt() != null && isin.trim().equalsIgnoreCase(trim(e.isin())))
                .filter(e -> !e.executedAt().isAfter(now))
                .filter(e -> !today.isAfter(closesOn(e)))
                .sorted(Comparator.comparing(PastExecution::executedAt));
    }

    private ThirtyDayWarning warning(Type type, PastExecution e, BigDecimal amount, String currency, String text) {
        LocalDate tradeDate = tradeDate(e);
        LocalDate closes = closesOn(e);
        String message = text.formatted(amount == null ? "?" : amount.abs().toPlainString(), currency, tradeDate,
                closes.plusDays(1));
        return new ThirtyDayWarning(type, e.isin(), e.executedAt(), tradeDate, e.quantity(), amount, currency, closes,
                message);
    }

    private LocalDate tradeDate(PastExecution e) {
        return e.executedAt().atZone(zone).toLocalDate();
    }

    private LocalDate closesOn(PastExecution e) {
        return tradeDate(e).plusDays(WINDOW_DAYS);
    }

    private static BigDecimal value(PastExecution e) {
        return e.quantity() == null || e.price() == null ? null : e.quantity().multiply(e.price());
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
