package dev.belis.trader.desk.risk;

import dev.belis.trader.desk.broker.Quote;
import dev.belis.trader.desk.store.Side;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Everything the risk engine needs to know about the world, gathered by the caller. The engine does no I/O.
 *
 * @param baseCurrency     account base currency, e.g. EUR
 * @param equity           NetLiquidation now, base currency
 * @param startOfDayEquity NetLiquidation at the start of the trading day, base currency
 * @param todayPnl         today's realized + unrealized P&amp;L, base currency (negative is a loss)
 * @param fxToBase         units of base currency per one unit of the trade currency (EUR per USD);
 *                         must be 1 when the trade currency is the base currency
 * @param availableCash    cash balance in the trade currency as IBKR reports it. The engine subtracts the
 *                         value of pending entries in the same currency. Negative means a margin loan.
 * @param openPositions    positions IBKR reports
 * @param pendingProposals entries proposed, approved or submitted that are not (yet) positions
 * @param entriesToday     entries already sent to IBKR today (America/New_York date)
 * @param lastQuote        fresh quote for the proposal's instrument; empty when none could be fetched
 * @param now              current time; evaluated in America/New_York whatever zone it carries
 * @param sessionClose     today's regular-session close from IBKR trading hours; empty on a holiday or weekend
 * @param killSwitch       true when the kill switch is on
 * @param whitelist        symbols that may be traded (desk.risk.whitelist), matched case-insensitively;
 *                         empty means nothing may be traded
 * @param earningsSoon     true when the instrument reports earnings soon enough to block an entry
 * @param recentExecutions executions of the same ISIN covering at least the last 30 days; used by
 *                         {@link ThirtyDayRule}, not by the engine
 */
public record RiskContext(String baseCurrency, BigDecimal equity, BigDecimal startOfDayEquity, BigDecimal todayPnl,
                          BigDecimal fxToBase, BigDecimal availableCash, List<OpenPosition> openPositions,
                          List<PendingEntry> pendingProposals, int entriesToday, Optional<Quote> lastQuote,
                          ZonedDateTime now, Optional<Instant> sessionClose, boolean killSwitch,
                          Set<String> whitelist, boolean earningsSoon, List<PastExecution> recentExecutions) {

    public RiskContext {
        openPositions = openPositions == null ? List.of() : List.copyOf(openPositions);
        pendingProposals = pendingProposals == null ? List.of() : List.copyOf(pendingProposals);
        recentExecutions = recentExecutions == null ? List.of() : List.copyOf(recentExecutions);
        whitelist = whitelist == null ? Set.of() : whitelist.stream()
                .filter(Objects::nonNull)
                .map(RiskContext::normalizeSymbol)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        lastQuote = lastQuote == null ? Optional.empty() : lastQuote;
        sessionClose = sessionClose == null ? Optional.empty() : sessionClose;
    }

    static String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    /** A position IBKR reports. {@code industry} null or blank counts as "UNKNOWN". */
    public record OpenPosition(long conid, String symbol, String industry, BigDecimal quantity) {
    }

    /**
     * An entry not yet filled. Its quantity times entry limit (trade currency) reserves cash.
     *
     * @param proposalId may be null; quantity may be null when not sized yet (then it reserves nothing)
     */
    public record PendingEntry(Long proposalId, long conid, String symbol, String industry, String currency,
                               BigDecimal quantity, BigDecimal entryLimit) {
    }

    /**
     * A past fill.
     *
     * @param realizedPnl realized P&amp;L of a closing fill; null for opening fills or when not reported
     * @param pnlCurrency currency of realizedPnl as IBKR reports it
     */
    public record PastExecution(String isin, Side side, BigDecimal quantity, BigDecimal price, String currency,
                                BigDecimal realizedPnl, String pnlCurrency, Instant executedAt) {
    }
}
