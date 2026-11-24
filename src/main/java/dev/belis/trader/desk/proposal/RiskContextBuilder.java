package dev.belis.trader.desk.proposal;

import dev.belis.trader.desk.broker.AccountSummary;
import dev.belis.trader.desk.broker.Broker;
import dev.belis.trader.desk.broker.BrokerException;
import dev.belis.trader.desk.broker.BrokerExecution;
import dev.belis.trader.desk.broker.BrokerPosition;
import dev.belis.trader.desk.broker.BrokerUnavailableException;
import dev.belis.trader.desk.broker.ContractInfo;
import dev.belis.trader.desk.broker.Quote;
import dev.belis.trader.desk.broker.TradingSession;
import dev.belis.trader.desk.config.DeskClock;
import dev.belis.trader.desk.config.DeskConfig;
import dev.belis.trader.desk.risk.RiskContext;
import dev.belis.trader.desk.risk.RiskContext.OpenPosition;
import dev.belis.trader.desk.risk.RiskContext.PastExecution;
import dev.belis.trader.desk.risk.RiskContext.PendingEntry;
import dev.belis.trader.desk.risk.ThirtyDayRule;
import dev.belis.trader.desk.store.AuditActor;
import dev.belis.trader.desk.store.AuditWriter;
import dev.belis.trader.desk.store.Instrument;
import dev.belis.trader.desk.store.Proposal;
import dev.belis.trader.desk.store.ProposalKind;
import dev.belis.trader.desk.store.Setting;
import dev.belis.trader.desk.store.SettingRepository;
import dev.belis.trader.desk.store.Side;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gathers a {@link RiskContext} from the broker (IBKR is the source of truth for account, positions, quotes,
 * recent executions) and the database (pending proposals, settings, stored executions). Broker calls are made
 * outside any transaction.
 *
 * <p>Known limits, all failing closed:
 * <ul>
 *   <li>FX: the broker has no FX quote yet. When the trade currency differs from the account base currency,
 *       fxToBase and the trade-currency cash are unknown, so FX_RATE and CASH fail.</li>
 *   <li>Start-of-day equity: taken from setting start_of_day_equity when it was recorded for today's New York
 *       date; otherwise the current NetLiquidation is recorded as today's start (so today's P&amp;L counts from
 *       the first proposal of the day until a scheduled job records it at the open).</li>
 * </ul>
 */
@ApplicationScoped
public class RiskContextBuilder {

    private static final Logger LOG = Logger.getLogger(RiskContextBuilder.class);

    public static final String KILL_SWITCH = "kill_switch";
    public static final String START_OF_DAY_EQUITY = "start_of_day_equity";
    public static final String START_OF_DAY_DATE = "start_of_day_date";

    /** Executions this far back cover the 30-day window in New York calendar days. */
    static final Duration EXECUTION_LOOKBACK = Duration.ofDays(ThirtyDayRule.WINDOW_DAYS + 1);

    private final Broker broker;
    private final DeskConfig config;
    private final DeskClock clock;
    private final EntityManager em;
    private final SettingRepository settings;
    private final AuditWriter audit;

    /** Industry by conid (from contract details; it does not change). Empty string when IBKR has none. */
    private final Map<Long, String> industries = new ConcurrentHashMap<>();

    public RiskContextBuilder(Broker broker, DeskConfig config, DeskClock clock, EntityManager em,
                              SettingRepository settings, AuditWriter audit) {
        this.broker = broker;
        this.config = config;
        this.clock = clock;
        this.em = em;
        this.settings = settings;
        this.audit = audit;
    }

    /**
     * Context for an ENTRY in {@code contract}.
     *
     * @throws BrokerException when IBKR cannot be asked (not connected, timeout, rejected)
     */
    public RiskContext forEntry(ContractInfo contract, boolean earningsSoon) {
        Instant now = clock.instant();
        rememberIndustry(contract);

        AccountSummary account = broker.accountSummary();
        List<BrokerPosition> positions = broker.positions();
        Optional<Quote> quote = quote(contract.symbol());
        List<PastExecution> executions = recentExecutions(contract, now);

        boolean sameCurrency = contract.currency() != null && contract.currency().equalsIgnoreCase(account.baseCurrency());
        BigDecimal fx = sameCurrency ? BigDecimal.ONE : null;
        BigDecimal cash = sameCurrency ? account.cash() : null;

        BigDecimal startOfDay = startOfDayEquity(account.netLiquidation());
        BigDecimal todayPnl = account.netLiquidation().subtract(startOfDay);

        List<OpenPosition> open = positions.stream()
                .filter(p -> p.quantity() != null && p.quantity().signum() != 0)
                .map(p -> new OpenPosition(p.conid(), p.symbol(), industryOf(p.conid(), p.symbol()), p.quantity()))
                .toList();
        Set<Long> openConids = new HashSet<>();
        open.forEach(o -> openConids.add(o.conid()));

        return new RiskContext(account.baseCurrency(), account.netLiquidation(), startOfDay, todayPnl, fx, cash,
                open, pendingEntries(now, openConids), entriesSentToday(), quote,
                now.atZone(DeskConfig.MARKET_ZONE), contract.todaySession().map(TradingSession::close),
                killSwitch(), whitelist(), earningsSoon, executions);
    }

    /** Executions of the contract's ISIN in the last 31 days: the database plus what IBKR still reports. */
    public List<PastExecution> recentExecutions(ContractInfo contract, Instant now) {
        Instant since = now.minus(EXECUTION_LOOKBACK);
        List<PastExecution> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = QuarkusTransaction.joiningExisting().call(() -> em.createNativeQuery("""
                        select e.ib_exec_id, e.side, e.quantity, e.price, e.currency, e.realized_pnl, e.executed_at
                        from execution e join instrument i on i.conid = e.conid
                        where i.isin = :isin and e.executed_at >= :since
                        """)
                .setParameter("isin", contract.isin())
                .setParameter("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                .getResultList());
        for (Object[] r : rows) {
            seen.add((String) r[0]);
            out.add(new PastExecution(contract.isin(), Side.valueOf(((String) r[1]).trim()), (BigDecimal) r[2],
                    (BigDecimal) r[3], trimmed(r[4]), (BigDecimal) r[5], trimmed(r[4]), toInstant(r[6])));
        }
        for (BrokerExecution e : broker.executionsSince(since)) {
            if (e.conid() == contract.conid() && seen.add(e.execId())) {
                out.add(new PastExecution(contract.isin(), e.side(), e.quantity(), e.price(), e.currency(),
                        e.realizedPnl(), e.commissionCurrency(), e.executedAt()));
            }
        }
        return out;
    }

    public boolean killSwitch() {
        Setting s = QuarkusTransaction.joiningExisting().call(() -> settings.findById(KILL_SWITCH));
        if (s == null || s.value == null) {
            return false;
        }
        String v = s.value.trim().toLowerCase(Locale.ROOT);
        return v.equals("true") || v.equals("on") || v.equals("1");
    }

    // ---------------------------------------------------------------------------------------------------

    private Set<String> whitelist() {
        return config.risk().whitelist().map(Set::copyOf).orElse(Set.of());
    }

    private Optional<Quote> quote(String symbol) {
        try {
            return Optional.of(broker.quote(symbol));
        } catch (BrokerUnavailableException e) {
            throw e;
        } catch (BrokerException e) {
            LOG.infof("No quote for %s: %s", symbol, e.getMessage());
            return Optional.empty(); // the QUOTE and DRIFT checks fail
        }
    }

    private BigDecimal startOfDayEquity(BigDecimal equityNow) {
        LocalDate today = clock.marketToday();
        return QuarkusTransaction.requiringNew().call(() -> {
            Setting date = settings.findById(START_OF_DAY_DATE);
            Setting value = settings.findById(START_OF_DAY_EQUITY);
            if (date != null && value != null && today.toString().equals(date.value)) {
                try {
                    return new BigDecimal(value.value.trim());
                } catch (NumberFormatException e) {
                    LOG.warnf("Bad %s setting '%s'; recording it again", START_OF_DAY_EQUITY, value.value);
                }
            }
            put(START_OF_DAY_DATE, today.toString());
            put(START_OF_DAY_EQUITY, equityNow.toPlainString());
            audit.write(AuditActor.SYSTEM, "START_OF_DAY_EQUITY_SET", null,
                    Map.of("date", today.toString(), "equity", equityNow.toPlainString(),
                            "source", "first proposal of the day"));
            return equityNow;
        });
    }

    private void put(String key, String value) {
        Setting s = settings.findById(key);
        if (s == null) {
            s = new Setting();
            s.key = key;
            s.value = value;
            settings.persist(s);
        } else {
            s.value = value;
        }
    }

    /** ENTRY proposals that are proposed (not expired), approved or submitted and not yet a position. */
    private List<PendingEntry> pendingEntries(Instant now, Set<Long> openConids) {
        List<Object[]> rows = QuarkusTransaction.joiningExisting().call(() -> em.createQuery("""
                        select p, i from Proposal p, Instrument i
                        where i.conid = p.conid and p.kind = :kind and p.status in :statuses
                          and (p.status <> :proposed or p.expiresAt is null or p.expiresAt > :now)
                        order by p.id
                        """, Object[].class)
                .setParameter("kind", ProposalKind.ENTRY)
                .setParameter("statuses", ProposalStatus.IN_FLIGHT.stream().map(Enum::name).toList())
                .setParameter("proposed", ProposalStatus.PROPOSED.name())
                .setParameter("now", now)
                .getResultList());
        List<PendingEntry> out = new ArrayList<>();
        for (Object[] r : rows) {
            Proposal p = (Proposal) r[0];
            Instrument i = (Instrument) r[1];
            if (openConids.contains(i.conid)) {
                continue; // (partly) filled: IBKR already reports it as a position
            }
            out.add(new PendingEntry(p.id, i.conid, i.symbol, industryOf(i.conid, i.symbol), i.currency,
                    p.quantity, p.entryLimit));
        }
        return out;
    }

    /** ENTRY proposals that reached SUBMITTED since midnight New York, counted from the audit trail. */
    private int entriesSentToday() {
        OffsetDateTime dayStart = OffsetDateTime.ofInstant(clock.marketDayStart(), ZoneOffset.UTC);
        Number n = QuarkusTransaction.joiningExisting().call(() -> (Number) em.createNativeQuery("""
                        select count(distinct a.proposal_id)
                        from audit_log a join proposal p on p.id = a.proposal_id
                        where p.kind = 'ENTRY' and a.event = :event and a.details ->> 'to' = :to and a.at >= :since
                        """)
                .setParameter("event", ProposalService.EVENT_STATUS_CHANGED)
                .setParameter("to", ProposalStatus.SUBMITTED.name())
                .setParameter("since", dayStart)
                .getSingleResult());
        return n.intValue();
    }

    private void rememberIndustry(ContractInfo c) {
        industries.put(c.conid(), c.industry() == null ? "" : c.industry());
    }

    /** Cached industry; looked up once per conid from IBKR. Null (counts as UNKNOWN) when it cannot be found. */
    private String industryOf(long conid, String symbol) {
        String cached = industries.get(conid);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        try {
            ContractInfo c = broker.contract(symbol);
            if (c.conid() == conid) {
                rememberIndustry(c);
                return c.industry();
            }
            LOG.warnf("Contract lookup for %s returned conid %d, expected %d", symbol, c.conid(), conid);
        } catch (BrokerUnavailableException e) {
            throw e;
        } catch (BrokerException e) {
            LOG.warnf("No contract details for %s (%d): %s", symbol, conid, e.getMessage());
        }
        return null;
    }

    private static String trimmed(Object o) {
        return o == null ? null : o.toString().trim();
    }

    private static Instant toInstant(Object o) {
        if (o instanceof Instant i) {
            return i;
        }
        if (o instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (o instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        }
        throw new IllegalStateException("unexpected timestamp type " + (o == null ? null : o.getClass()));
    }
}
