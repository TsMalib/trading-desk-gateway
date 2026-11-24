package dev.belis.trader.desk.risk;

import dev.belis.trader.desk.broker.Quote;
import dev.belis.trader.desk.config.DeskConfig;
import dev.belis.trader.desk.risk.RiskContext.OpenPosition;
import dev.belis.trader.desk.risk.RiskContext.PendingEntry;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Pure risk checks for an ENTRY proposal. No I/O, no clock, no CDI: the same inputs always give the same result.
 * Every rule is evaluated and reported, even after one has failed, so the owner sees the whole picture.
 *
 * <p>Sizing: quantity = floor(riskPerTrade / ((entry - stop) * fx)), capped at
 * floor(maxPosition / (entry * fx)), where riskPerTrade and maxPosition are percents of equity in the base
 * currency and fx converts the trade currency to the base currency.
 */
public final class RiskEngine {

    static final ZoneId NY = DeskConfig.MARKET_ZONE;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final String UNKNOWN_INDUSTRY = "UNKNOWN";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ROOT);
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private final RiskLimits limits;

    public RiskEngine(RiskLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public RiskLimits limits() {
        return limits;
    }

    public RiskResult evaluate(EntryProposal p, RiskContext ctx) {
        Objects.requireNonNull(p, "proposal");
        Objects.requireNonNull(ctx, "context");
        Objects.requireNonNull(ctx.now(), "context.now");

        BigDecimal entry = p.entryLimit();
        BigDecimal stop = p.stopPrice();
        BigDecimal target = p.targetPrice();
        boolean pricesValid = pricesInOrder(entry, stop, target);
        boolean fxValid = fxValid(p.currency(), ctx);
        BigDecimal fx = fxValid ? ctx.fxToBase() : null;
        BigDecimal equity = ctx.equity();
        boolean equityValid = equity != null && equity.signum() > 0;

        List<PendingEntry> otherPending = ctx.pendingProposals().stream()
                .filter(pe -> p.proposalId() == null || !p.proposalId().equals(pe.proposalId()))
                .toList();

        // --- sizing -------------------------------------------------------------------------------------
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal riskEur = null;
        BigDecimal rewardRisk = null;
        BigDecimal maxPositionBase = equityValid ? pct(equity, limits.maxPositionPct()) : null;
        if (pricesValid) {
            BigDecimal perShare = entry.subtract(stop);
            rewardRisk = target.subtract(entry).divide(perShare, 2, RoundingMode.DOWN);
            if (fxValid && equityValid) {
                BigDecimal riskBudget = pct(equity, limits.riskPerTradePct());
                BigDecimal byRisk = riskBudget.divide(perShare.multiply(fx), 0, RoundingMode.DOWN);
                BigDecimal byPosition = maxPositionBase.divide(entry.multiply(fx), 0, RoundingMode.DOWN);
                quantity = byRisk.min(byPosition).max(BigDecimal.ZERO);
                riskEur = quantity.multiply(perShare).multiply(fx).setScale(2, RoundingMode.HALF_UP);
            }
        }

        List<RiskCheck> checks = new ArrayList<>();

        // --- account-wide --------------------------------------------------------------------------------
        checks.add(new RiskCheck(RiskRule.KILL_SWITCH, !ctx.killSwitch(), ctx.killSwitch() ? "on" : "off", "off"));
        checks.add(dailyLoss(ctx));

        String symbol = RiskContext.normalizeSymbol(p.symbol());
        checks.add(new RiskCheck(RiskRule.WHITELIST, !symbol.isEmpty() && ctx.whitelist().contains(symbol),
                symbol.isEmpty() ? "n/a" : symbol,
                ctx.whitelist().isEmpty() ? "(empty)" : String.join(",", new TreeSet<>(ctx.whitelist()))));
        checks.add(new RiskCheck(RiskRule.EARNINGS, !ctx.earningsSoon(),
                ctx.earningsSoon() ? "earnings soon" : "none", "none"));

        // --- prices --------------------------------------------------------------------------------------
        checks.add(new RiskCheck(RiskRule.PRICE_ORDER, pricesValid,
                "stop " + plain(stop) + ", entry " + plain(entry) + ", target " + plain(target),
                "0 < stop < entry < target"));
        checks.add(stopDistance(entry, stop, pricesValid));
        checks.add(new RiskCheck(RiskRule.REWARD_RISK,
                rewardRisk != null && rewardRisk.compareTo(limits.minRewardRisk()) >= 0,
                plain(rewardRisk), ">= " + plain(limits.minRewardRisk())));

        // --- market --------------------------------------------------------------------------------------
        Optional<BigDecimal> reference = ctx.lastQuote().flatMap(RiskEngine::referencePrice);
        checks.add(quoteCheck(ctx.lastQuote(), reference));
        checks.add(drift(entry, reference));
        checks.add(entryWindow(ctx.now(), ctx.sessionClose()));
        checks.add(new RiskCheck(RiskRule.FX_RATE, fxValid,
                plain(ctx.fxToBase()) + " " + ctx.baseCurrency() + " per " + p.currency(),
                p.currency() != null && p.currency().equalsIgnoreCase(ctx.baseCurrency()) ? "= 1" : "> 0"));

        // --- size and money ------------------------------------------------------------------------------
        checks.add(new RiskCheck(RiskRule.MIN_QUANTITY, quantity.compareTo(BigDecimal.ONE) >= 0,
                plain(quantity), ">= 1"));
        checks.add(positionSize(quantity, entry, fx, maxPositionBase, pricesValid, ctx.baseCurrency()));
        checks.add(cash(p, ctx, quantity, otherPending, pricesValid));

        // --- portfolio -----------------------------------------------------------------------------------
        checks.add(duplicate(p, symbol, ctx.openPositions(), otherPending));
        int openAfter = ctx.openPositions().size() + otherPending.size() + 1;
        checks.add(new RiskCheck(RiskRule.MAX_OPEN_POSITIONS, openAfter <= limits.maxOpenPositions(),
                String.valueOf(openAfter), "<= " + limits.maxOpenPositions()));
        checks.add(industry(p, ctx.openPositions(), otherPending));
        int entriesAfter = ctx.entriesToday() + 1;
        checks.add(new RiskCheck(RiskRule.MAX_ENTRIES_PER_DAY, entriesAfter <= limits.maxEntriesPerDay(),
                String.valueOf(entriesAfter), "<= " + limits.maxEntriesPerDay()));

        return new RiskResult(checks, quantity, riskEur, rewardRisk);
    }

    // ---------------------------------------------------------------------------------------------------

    private RiskCheck dailyLoss(RiskContext ctx) {
        BigDecimal sod = ctx.startOfDayEquity();
        BigDecimal pnl = ctx.todayPnl();
        String ccy = " " + ctx.baseCurrency();
        if (sod == null || sod.signum() <= 0 || pnl == null) {
            return new RiskCheck(RiskRule.DAILY_LOSS, false, "pnl " + plain(pnl) + ", start-of-day " + plain(sod),
                    limits.dailyLossPct().toPlainString() + "% of start-of-day equity");
        }
        BigDecimal loss = pnl.signum() < 0 ? pnl.negate() : BigDecimal.ZERO;
        BigDecimal limit = pct(sod, limits.dailyLossPct());
        // The kill switch trips when the loss *reaches* the limit, so equality fails.
        return new RiskCheck(RiskRule.DAILY_LOSS, loss.compareTo(limit) < 0,
                plain(loss) + ccy, "< " + plain(limit.setScale(2, RoundingMode.HALF_UP)) + ccy);
    }

    private RiskCheck stopDistance(BigDecimal entry, BigDecimal stop, boolean pricesValid) {
        String limit = plain(limits.minStopPct()) + "% to " + plain(limits.maxStopPct()) + "%";
        if (!pricesValid) {
            return new RiskCheck(RiskRule.STOP_DISTANCE, false, "n/a", limit);
        }
        BigDecimal pct = entry.subtract(stop).multiply(HUNDRED).divide(entry, MC);
        boolean ok = pct.compareTo(limits.minStopPct()) >= 0 && pct.compareTo(limits.maxStopPct()) <= 0;
        return new RiskCheck(RiskRule.STOP_DISTANCE, ok, display(pct) + "%", limit);
    }

    private static RiskCheck quoteCheck(Optional<Quote> quote, Optional<BigDecimal> reference) {
        if (quote.isEmpty()) {
            return new RiskCheck(RiskRule.QUOTE, false, "none", "live quote with a price");
        }
        boolean delayed = quote.get().delayed();
        String actual = reference.map(r -> "ref " + r.toPlainString()).orElse("no price") + (delayed ? ", delayed" : "");
        return new RiskCheck(RiskRule.QUOTE, !delayed && reference.isPresent(), actual, "live quote with a price");
    }

    private RiskCheck drift(BigDecimal entry, Optional<BigDecimal> reference) {
        String limit = "<= " + plain(limits.maxDriftPct()) + "%";
        if (entry == null || entry.signum() <= 0 || reference.isEmpty()) {
            return new RiskCheck(RiskRule.DRIFT, false, "n/a", limit);
        }
        BigDecimal ref = reference.get();
        BigDecimal drift = entry.subtract(ref).abs().multiply(HUNDRED).divide(ref, MC);
        return new RiskCheck(RiskRule.DRIFT, drift.compareTo(limits.maxDriftPct()) <= 0, display(drift) + "%", limit);
    }

    /** Open from windowStart (inclusive) to close - buffer (exclusive), both on today's New York date. */
    private RiskCheck entryWindow(ZonedDateTime nowAny, Optional<Instant> sessionClose) {
        ZonedDateTime now = nowAny.withZoneSameInstant(NY);
        LocalDate today = now.toLocalDate();
        String actual = TIME.format(now);
        if (sessionClose.isEmpty()) {
            return new RiskCheck(RiskRule.ENTRY_WINDOW, false, actual, "market closed today");
        }
        ZonedDateTime close = sessionClose.get().atZone(NY);
        if (!close.toLocalDate().equals(today)) {
            return new RiskCheck(RiskRule.ENTRY_WINDOW, false, actual,
                    "session close is for " + close.toLocalDate() + ", not today");
        }
        ZonedDateTime open = ZonedDateTime.of(today, limits.windowStart(), NY);
        ZonedDateTime end = close.minusMinutes(limits.closeBufferMinutes());
        boolean ok = !now.isBefore(open) && now.isBefore(end);
        return new RiskCheck(RiskRule.ENTRY_WINDOW, ok, actual,
                "[" + HHMM.format(open) + ", " + HHMM.format(end) + ") " + NY.getId());
    }

    private RiskCheck positionSize(BigDecimal quantity, BigDecimal entry, BigDecimal fx, BigDecimal maxPositionBase,
                                   boolean pricesValid, String baseCurrency) {
        String limit = maxPositionBase == null ? "n/a"
                : "<= " + plain(maxPositionBase.setScale(2, RoundingMode.HALF_UP)) + " " + baseCurrency;
        if (!pricesValid || fx == null || maxPositionBase == null) {
            return new RiskCheck(RiskRule.POSITION_SIZE, false, "n/a", limit);
        }
        BigDecimal value = quantity.multiply(entry).multiply(fx);
        return new RiskCheck(RiskRule.POSITION_SIZE, value.compareTo(maxPositionBase) <= 0,
                plain(value.setScale(2, RoundingMode.HALF_UP)) + " " + baseCurrency, limit);
    }

    /** Cash account: the entry must be paid from cash in the trade currency, net of pending entries. */
    private static RiskCheck cash(EntryProposal p, RiskContext ctx, BigDecimal quantity, List<PendingEntry> others,
                                  boolean pricesValid) {
        String ccy = " " + p.currency();
        if (!pricesValid || ctx.availableCash() == null) {
            return new RiskCheck(RiskRule.CASH, false, "n/a", "cash " + plain(ctx.availableCash()) + ccy);
        }
        BigDecimal reserved = others.stream()
                .filter(pe -> pe.currency() != null && pe.currency().equalsIgnoreCase(p.currency()))
                .filter(pe -> pe.quantity() != null && pe.entryLimit() != null)
                .map(pe -> pe.quantity().multiply(pe.entryLimit()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal available = ctx.availableCash().subtract(reserved);
        BigDecimal value = quantity.multiply(p.entryLimit());
        boolean ok = available.signum() >= 0 && value.compareTo(available) <= 0;
        String limit = "<= " + plain(available) + ccy
                + (reserved.signum() == 0 ? "" : " (cash " + plain(ctx.availableCash()) + " - pending " + plain(reserved) + ")");
        return new RiskCheck(RiskRule.CASH, ok, plain(value) + ccy, limit);
    }

    private static RiskCheck duplicate(EntryProposal p, String symbol, List<OpenPosition> open,
                                       List<PendingEntry> pending) {
        String where = null;
        if (open.stream().anyMatch(o -> o.conid() == p.conid() || sameSymbol(symbol, o.symbol()))) {
            where = symbol + " in open positions";
        } else if (pending.stream().anyMatch(pe -> pe.conid() == p.conid() || sameSymbol(symbol, pe.symbol()))) {
            where = symbol + " in pending proposals";
        }
        return new RiskCheck(RiskRule.DUPLICATE_SYMBOL, where == null, where == null ? "none" : where, "none");
    }

    private RiskCheck industry(EntryProposal p, List<OpenPosition> open, List<PendingEntry> pending) {
        String industry = industryKey(p.industry());
        long count = open.stream().filter(o -> industryKey(o.industry()).equals(industry)).count()
                + pending.stream().filter(pe -> industryKey(pe.industry()).equals(industry)).count()
                + 1;
        return new RiskCheck(RiskRule.MAX_PER_INDUSTRY, count <= limits.maxPerIndustry(),
                count + " in " + industry, "<= " + limits.maxPerIndustry());
    }

    // ---------------------------------------------------------------------------------------------------

    static boolean pricesInOrder(BigDecimal entry, BigDecimal stop, BigDecimal target) {
        return entry != null && stop != null && target != null
                && stop.signum() > 0
                && stop.compareTo(entry) < 0
                && entry.compareTo(target) < 0;
    }

    private static boolean fxValid(String currency, RiskContext ctx) {
        BigDecimal fx = ctx.fxToBase();
        if (currency == null || ctx.baseCurrency() == null || fx == null || fx.signum() <= 0) {
            return false;
        }
        return !currency.equalsIgnoreCase(ctx.baseCurrency()) || fx.compareTo(BigDecimal.ONE) == 0;
    }

    /** Last trade, else the bid/ask midpoint. */
    static Optional<BigDecimal> referencePrice(Quote q) {
        if (q.last() != null && q.last().signum() > 0) {
            return Optional.of(q.last());
        }
        if (q.bid() != null && q.ask() != null && q.bid().signum() > 0 && q.ask().signum() > 0) {
            return Optional.of(q.bid().add(q.ask()).divide(BigDecimal.valueOf(2), MC));
        }
        return Optional.empty();
    }

    private static BigDecimal pct(BigDecimal amount, BigDecimal percent) {
        return amount.multiply(percent).divide(HUNDRED, MC);
    }

    private static String industryKey(String industry) {
        return industry == null || industry.isBlank() ? UNKNOWN_INDUSTRY : industry.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean sameSymbol(String normalized, String other) {
        return !normalized.isEmpty() && normalized.equals(RiskContext.normalizeSymbol(other));
    }

    private static String plain(BigDecimal v) {
        return v == null ? "n/a" : v.toPlainString();
    }

    private static String display(BigDecimal v) {
        return v.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
