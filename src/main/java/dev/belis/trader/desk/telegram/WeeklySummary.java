package dev.belis.trader.desk.telegram;

import dev.belis.trader.desk.broker.Broker;
import dev.belis.trader.desk.broker.BrokerException;
import dev.belis.trader.desk.broker.BrokerOpenOrder;
import dev.belis.trader.desk.broker.BrokerPosition;
import dev.belis.trader.desk.broker.TradingSession;
import dev.belis.trader.desk.config.DeskClock;
import dev.belis.trader.desk.config.DeskConfig;
import dev.belis.trader.desk.proposal.ProposalService;
import dev.belis.trader.desk.risk.RiskContext.PastExecution;
import dev.belis.trader.desk.risk.ThirtyDayRule;
import dev.belis.trader.desk.risk.ThirtyDayWarning;
import dev.belis.trader.desk.store.AuditActor;
import dev.belis.trader.desk.store.AuditWriter;
import dev.belis.trader.desk.store.Instrument;
import dev.belis.trader.desk.store.InstrumentRepository;
import dev.belis.trader.desk.store.PositionMeta;
import dev.belis.trader.desk.store.PositionMetaRepository;
import dev.belis.trader.desk.store.PositionOrigin;
import dev.belis.trader.desk.store.Setting;
import dev.belis.trader.desk.store.SettingRepository;
import dev.belis.trader.desk.store.Side;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static dev.belis.trader.desk.telegram.ProposalMessages.esc;

/**
 * Every Friday, 10 minutes after the close (as IBKR reports it, so 13:10 on a half day; 16:10 New York when
 * IBKR cannot say), sends the week to Telegram:
 * <ul>
 *   <li>the ENTRY trades closed since Monday 00:00 New York, with realized P&amp;L and R (P&amp;L divided by the
 *       planned risk, filled quantity x (average entry - original stop));</li>
 *   <li>win rate (P&amp;L &gt; 0) and average R, over the trades whose P&amp;L is known;</li>
 *   <li>open risk: for each position IBKR reports, (average cost - highest working stop) x quantity, at least 0;
 *       a position without a stop is flagged;</li>
 *   <li>30-day warnings: ISINs sold at a loss in the last 30 days (buying back may fall under the rule) and held
 *       ISINs bought in the last 30 days (selling may).</li>
 * </ul>
 * Sent once per Friday (setting weekly_summary_date); audited as WEEKLY_SUMMARY_SENT.
 */
@ApplicationScoped
public class WeeklySummary {

    private static final Logger LOG = Logger.getLogger(WeeklySummary.class);

    public static final String EVENT_SENT = "WEEKLY_SUMMARY_SENT";
    public static final String SENT_ON = "weekly_summary_date";

    static final Duration AFTER_CLOSE = Duration.ofMinutes(10);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ROOT);

    private final Broker broker;
    private final TelegramClient telegram;
    private final EntityManager em;
    private final InstrumentRepository instruments;
    private final PositionMetaRepository positionMetas;
    private final SettingRepository settings;
    private final AuditWriter audit;
    private final DeskClock clock;
    private final ProposalMessages messages;

    public WeeklySummary(Broker broker, TelegramClient telegram, EntityManager em, InstrumentRepository instruments,
                         PositionMetaRepository positionMetas, SettingRepository settings, AuditWriter audit,
                         DeskClock clock, DeskConfig config) {
        this.broker = broker;
        this.telegram = telegram;
        this.em = em;
        this.instruments = instruments;
        this.positionMetas = positionMetas;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
        this.messages = new ProposalMessages(config.mode());
    }

    /** Every minute on Friday afternoons; {@link #sendIfDue} decides. */
    @Scheduled(cron = "0 * 13-16 ? * FRI", timeZone = "America/New_York",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void job() {
        sendIfDue();
    }

    /** Sends the summary when it is Friday, 10 minutes past today's close, and not sent yet today. */
    public boolean sendIfDue() {
        ZonedDateTime now = clock.marketNow();
        LocalDate today = now.toLocalDate();
        if (today.getDayOfWeek() != DayOfWeek.FRIDAY || alreadySent(today)) {
            return false;
        }
        Instant close = closeOf(today).orElse(today.atTime(16, 0).atZone(DeskConfig.MARKET_ZONE).toInstant());
        if (now.toInstant().isBefore(close.plus(AFTER_CLOSE))) {
            return false;
        }
        String text = build(now.toInstant());
        try {
            telegram.send(text, null);
        } catch (RuntimeException e) {
            LOG.errorf("Weekly summary not sent: %s", e.getMessage());
            return false; // the next minute retries
        }
        QuarkusTransaction.requiringNew().run(() -> {
            Setting s = settings.findById(SENT_ON);
            if (s == null) {
                s = new Setting();
                s.key = SENT_ON;
                s.value = today.toString();
                settings.persist(s);
            } else {
                s.value = today.toString();
            }
            audit.write(AuditActor.SYSTEM, EVENT_SENT, null, Map.of("week", weekStart(today).toString(),
                    "date", today.toString()));
        });
        return true;
    }

    // =====================================================================================================
    // Content
    // =====================================================================================================

    /** One closed trade. {@code pnl} and {@code r} are null when unknown. */
    record Trade(long proposalId, String symbol, BigDecimal quantity, BigDecimal entry, BigDecimal pnl,
                 String currency, BigDecimal r, boolean estimated, String by) {
    }

    /** The summary as Telegram HTML, for the week up to {@code now}. */
    public String build(Instant now) {
        LocalDate today = now.atZone(DeskConfig.MARKET_ZONE).toLocalDate();
        LocalDate monday = weekStart(today);
        Instant from = monday.atStartOfDay(DeskConfig.MARKET_ZONE).toInstant();
        List<Trade> trades = QuarkusTransaction.requiringNew().call(() -> closedTrades(from, now));

        StringBuilder b = new StringBuilder(messages.tag()).append(" 📅 <b>Week of ")
                .append(esc(DAY.format(monday))).append("</b>\n");

        // closed trades
        b.append("\n<b>Closed trades</b> (").append(trades.size()).append(')');
        if (trades.isEmpty()) {
            b.append("\nnone");
        }
        List<String> rows = new ArrayList<>();
        for (Trade t : trades) {
            rows.add("#" + t.proposalId() + " " + t.symbol() + " " + plain(t.quantity()) + " @ " + plain(t.entry())
                    + "  " + (t.pnl() == null ? "P&L ?" : signed(t.pnl()) + " " + t.currency()
                    + (t.estimated() ? "~" : ""))
                    + "  " + (t.r() == null ? "R ?" : signed(t.r()) + "R") + "  " + t.by());
        }
        pre(b, rows);

        // stats
        List<Trade> known = trades.stream().filter(t -> t.pnl() != null).toList();
        List<BigDecimal> rs = trades.stream().map(Trade::r).filter(Objects::nonNull).toList();
        if (!trades.isEmpty()) {
            long wins = known.stream().filter(t -> t.pnl().signum() > 0).count();
            b.append("\nWin rate: ").append(known.isEmpty() ? "n/a" : pct(wins, known.size()) + " (" + wins + " of "
                    + known.size() + ")");
            b.append("\nAverage R: ").append(rs.isEmpty() ? "n/a" : signed(rs.stream().reduce(BigDecimal.ZERO,
                    BigDecimal::add).divide(BigDecimal.valueOf(rs.size()), 2, RoundingMode.HALF_UP)) + "R");
            Map<String, BigDecimal> total = new TreeMap<>();
            known.forEach(t -> total.merge(t.currency(), t.pnl(), BigDecimal::add));
            if (!total.isEmpty()) {
                List<String> parts = new ArrayList<>();
                total.forEach((ccy, v) -> parts.add(signed(v) + " " + ccy));
                b.append("\nRealized: ").append(esc(String.join(", ", parts)));
            }
            if (known.size() < trades.size()) {
                b.append("\n(").append(trades.size() - known.size()).append(" trade(s) without a known P&amp;L)");
            }
        }

        // open risk
        b.append("\n\n<b>Open risk</b> (loss from entry if every stop fills)");
        appendOpenRisk(b);

        // 30-day warnings
        List<String> warnings = QuarkusTransaction.requiringNew().call(() -> thirtyDayWarnings(now));
        b.append("\n\n<b>30-day rule</b>");
        if (warnings.isEmpty()) {
            b.append("\nnone");
        }
        warnings.forEach(w -> b.append("\n• ").append(esc(w)));
        return b.toString();
    }

    private List<Trade> closedTrades(Instant from, Instant to) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                        select p.id, coalesce(i.symbol, 'conid ' || p.conid), p.stop_price,
                               a.details ->> 'realizedPnl', a.details ->> 'currency', a.details ->> 'estimated',
                               a.details ->> 'by'
                        from audit_log a
                          join proposal p on p.id = a.proposal_id
                          left join instrument i on i.conid = p.conid
                        where a.event = :event and a.details ->> 'to' = 'CLOSED' and p.kind = 'ENTRY'
                          and a.at >= :from and a.at < :to
                        order by a.at, p.id
                        """)
                .setParameter("event", ProposalService.EVENT_STATUS_CHANGED)
                .setParameter("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .setParameter("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .getResultList();
        List<Trade> out = new ArrayList<>();
        for (Object[] r : rows) {
            long id = ((Number) r[0]).longValue();
            BigDecimal stop = (BigDecimal) r[2];
            BigDecimal pnl = decimal((String) r[3]);
            Object[] fill = (Object[]) em.createNativeQuery("""
                            select coalesce(sum(e.quantity), 0), coalesce(sum(e.quantity * e.price), 0)
                            from execution e join broker_order o on o.id = e.broker_order_id
                            where o.proposal_id = :id and o.role = 'ENTRY'
                            """)
                    .setParameter("id", id).getSingleResult();
            BigDecimal qty = (BigDecimal) fill[0];
            BigDecimal entry = qty.signum() == 0 ? null
                    : ((BigDecimal) fill[1]).divide(qty, 4, RoundingMode.HALF_UP);
            BigDecimal r1 = null;
            if (pnl != null && entry != null && stop != null && entry.compareTo(stop) > 0) {
                r1 = pnl.divide(qty.multiply(entry.subtract(stop)), 2, RoundingMode.HALF_UP);
            }
            String by = r[6] == null ? "" : (String) r[6];
            out.add(new Trade(id, (String) r[1], qty, entry, pnl, r[4] == null ? "" : (String) r[4], r1,
                    "true".equals(r[5]), by));
        }
        return out;
    }

    private void appendOpenRisk(StringBuilder b) {
        List<BrokerPosition> positions;
        List<BrokerOpenOrder> orders;
        try {
            positions = broker.positions();
            orders = broker.openOrders();
        } catch (BrokerException e) {
            b.append("\nIBKR unavailable: ").append(esc(e.getMessage()));
            return;
        }
        positions = positions.stream().filter(p -> p.quantity() != null && p.quantity().signum() > 0).toList();
        if (positions.isEmpty()) {
            b.append("\nno positions");
            return;
        }
        List<String> rows = new ArrayList<>();
        Map<String, BigDecimal> total = new TreeMap<>();
        List<String> unprotected = new ArrayList<>();
        for (BrokerPosition p : positions) {
            Optional<BigDecimal> stop = orders.stream()
                    .filter(o -> o.conid() == p.conid() && o.action() == Side.SELL && o.auxPrice() != null
                            && o.orderType() != null && o.orderType().toUpperCase(Locale.ROOT).startsWith("STP"))
                    .map(BrokerOpenOrder::auxPrice).max(BigDecimal::compareTo);
            PositionMeta m = QuarkusTransaction.requiringNew().call(() -> positionMetas.findById(p.conid()));
            String origin = m != null && m.origin == PositionOrigin.GATEWAY ? "" : " (external)";
            String symbol = p.symbol() == null ? "conid " + p.conid() : p.symbol();
            if (stop.isEmpty()) {
                unprotected.add(symbol);
                rows.add(symbol + " " + plain(p.quantity()) + "  NO STOP" + origin);
                continue;
            }
            BigDecimal risk = p.avgCost() == null ? BigDecimal.ZERO
                    : p.avgCost().subtract(stop.get()).max(BigDecimal.ZERO).multiply(p.quantity());
            total.merge(p.currency(), risk, BigDecimal::add);
            rows.add(symbol + " " + plain(p.quantity()) + "  stop " + plain(stop.get()) + "  "
                    + money(risk) + " " + p.currency() + origin);
        }
        pre(b, rows);
        List<String> parts = new ArrayList<>();
        total.forEach((ccy, v) -> parts.add(money(v) + " " + ccy));
        b.append("\nTotal: ").append(parts.isEmpty() ? "n/a" : esc(String.join(", ", parts)));
        if (!unprotected.isEmpty()) {
            b.append("\n⚠️ No stop: ").append(esc(String.join(", ", unprotected)));
        }
    }

    private List<String> thirtyDayWarnings(Instant now) {
        Instant since = now.minus(Duration.ofDays(ThirtyDayRule.WINDOW_DAYS + 1));
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                        select i.symbol, i.isin, e.side, e.quantity, e.price, e.currency, e.realized_pnl, e.executed_at
                        from execution e join instrument i on i.conid = e.conid
                        where e.executed_at >= :since
                        order by e.executed_at
                        """)
                .setParameter("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                .getResultList();
        Map<String, List<PastExecution>> byIsin = new LinkedHashMap<>();
        Map<String, String> symbols = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String isin = (String) r[1];
            symbols.put(isin, (String) r[0]);
            String ccy = ((String) r[5]).trim();
            byIsin.computeIfAbsent(isin, k -> new ArrayList<>()).add(new PastExecution(isin,
                    Side.valueOf(((String) r[2]).trim()), (BigDecimal) r[3], (BigDecimal) r[4], ccy,
                    (BigDecimal) r[6], ccy, toInstant(r[7])));
        }
        Set<String> held = new LinkedHashSet<>();
        try {
            for (BrokerPosition p : broker.positions()) {
                Instrument i = instruments.findById(p.conid());
                if (i != null && p.quantity() != null && p.quantity().signum() > 0) {
                    held.add(i.isin);
                }
            }
        } catch (BrokerException e) {
            LOG.debugf("30-day warnings without positions: %s", e.getMessage());
        }
        ThirtyDayRule rule = new ThirtyDayRule();
        Set<String> out = new LinkedHashSet<>();
        byIsin.forEach((isin, execs) -> {
            String symbol = symbols.get(isin);
            for (ThirtyDayWarning w : rule.forBuy(isin, now, execs)) {
                out.add(symbol + ": " + w.message());
            }
            if (held.contains(isin)) {
                for (ThirtyDayWarning w : rule.forExit(isin, now, execs)) {
                    out.add(symbol + ": " + w.message());
                }
            }
        });
        return List.copyOf(out);
    }

    // =====================================================================================================
    // Helpers
    // =====================================================================================================

    private Optional<Instant> closeOf(LocalDate today) {
        try {
            String symbol = QuarkusTransaction.requiringNew().call(() -> instruments.findAll().firstResultOptional()
                    .map(i -> i.symbol).orElse("SPY"));
            return broker.contract(symbol).todaySession().filter(s -> s.date().equals(today))
                    .map(TradingSession::close);
        } catch (BrokerException e) {
            return Optional.empty();
        }
    }

    private boolean alreadySent(LocalDate today) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Setting s = settings.findById(SENT_ON);
            return s != null && today.toString().equals(s.value);
        });
    }

    static LocalDate weekStart(LocalDate d) {
        return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static void pre(StringBuilder b, List<String> rows) {
        if (!rows.isEmpty()) {
            b.append("\n<pre>").append(esc(String.join("\n", rows))).append("</pre>");
        }
    }

    private static BigDecimal decimal(String s) {
        if (s == null || s.isBlank() || "null".equals(s)) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant toInstant(Object o) {
        return switch (o) {
            case Instant i -> i;
            case OffsetDateTime odt -> odt.toInstant();
            case Timestamp ts -> ts.toInstant();
            default -> throw new IllegalStateException("unexpected timestamp type " + o.getClass());
        };
    }

    private static String pct(long part, long whole) {
        return BigDecimal.valueOf(part * 100L).divide(BigDecimal.valueOf(whole), 0, RoundingMode.HALF_UP) + "%";
    }

    private static String signed(BigDecimal v) {
        String s = v.setScale(2, RoundingMode.HALF_UP).toPlainString();
        return v.signum() > 0 ? "+" + s : s;
    }

    private static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String plain(BigDecimal v) {
        return v == null ? "?" : v.stripTrailingZeros().toPlainString();
    }
}
