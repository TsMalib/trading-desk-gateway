package dev.belis.trader.desk.proposal;

import dev.belis.trader.desk.broker.AccountSummary;
import dev.belis.trader.desk.broker.Broker;
import dev.belis.trader.desk.broker.BrokerException;
import dev.belis.trader.desk.config.DeskClock;
import dev.belis.trader.desk.config.DeskConfig;
import dev.belis.trader.desk.store.AuditActor;
import dev.belis.trader.desk.store.AuditWriter;
import dev.belis.trader.desk.store.Setting;
import dev.belis.trader.desk.store.SettingRepository;
import dev.belis.trader.desk.telegram.AlertService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The daily loss limit.
 *
 * <ul>
 *   <li>09:30 New York, Monday to Friday: records NetLiquidation as setting start_of_day_equity (with
 *       start_of_day_date). If the service was not running then, the first check of the day after 09:30 records
 *       it, which misses whatever was lost before.</li>
 *   <li>Every minute: today's P&amp;L = NetLiquidation - start_of_day_equity. IBKR marks every position to market
 *       in NetLiquidation, so this is realized + unrealized P&amp;L for the day, already in the base currency
 *       (no FX quote needed); deposits and withdrawals also move it. At or below -dailyLossPct of
 *       start_of_day_equity the kill switch is engaged ({@link KillSwitch#engage}: new entries blocked, working
 *       entries cancelled, stops and targets stay) and a CRITICAL alert is sent. Once per day: after the owner's
 *       /resume it does not trip again that day (the DAILY_LOSS risk check still refuses new entries while the
 *       loss is beyond the limit).</li>
 * </ul>
 * This is the same P&amp;L the DAILY_LOSS risk check and /status use.
 */
@ApplicationScoped
public class DailyLossGuard {

    private static final Logger LOG = Logger.getLogger(DailyLossGuard.class);

    public static final String EVENT_START_OF_DAY = "START_OF_DAY_EQUITY_SET";
    public static final String EVENT_TRIPPED = "DAILY_LOSS_LIMIT";
    /** Setting: New York date on which the limit last tripped. */
    public static final String TRIPPED_ON = "daily_loss_tripped_date";

    static final LocalTime OPEN = LocalTime.of(9, 30);
    static final LocalTime CLOSE = LocalTime.of(16, 0);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final Broker broker;
    private final KillSwitch killSwitch;
    private final SettingRepository settings;
    private final AuditWriter audit;
    private final AlertService alerts;
    private final DeskClock clock;
    private final DeskConfig config;

    public DailyLossGuard(Broker broker, KillSwitch killSwitch, SettingRepository settings, AuditWriter audit,
                          AlertService alerts, DeskClock clock, DeskConfig config) {
        this.broker = broker;
        this.killSwitch = killSwitch;
        this.settings = settings;
        this.audit = audit;
        this.alerts = alerts;
        this.clock = clock;
        this.config = config;
    }

    @Scheduled(cron = "0 30 9 ? * MON-FRI", timeZone = "America/New_York",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void atOpen() {
        recordStartOfDay("09:30 open");
    }

    @Scheduled(every = "60s", delayed = "90s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void everyMinute() {
        check();
    }

    /** Records NetLiquidation as today's start-of-day equity. Empty when IBKR cannot be asked. */
    public Optional<BigDecimal> recordStartOfDay(String source) {
        AccountSummary a;
        try {
            a = broker.accountSummary();
        } catch (BrokerException e) {
            LOG.warnf("Start-of-day equity not recorded (%s): %s", source, e.getMessage());
            return Optional.empty();
        }
        LocalDate today = clock.marketToday();
        BigDecimal equity = a.netLiquidation();
        QuarkusTransaction.requiringNew().run(() -> {
            put(RiskContextBuilder.START_OF_DAY_DATE, today.toString());
            put(RiskContextBuilder.START_OF_DAY_EQUITY, equity.toPlainString());
            audit.write(AuditActor.SYSTEM, EVENT_START_OF_DAY, null, Map.of("date", today.toString(),
                    "equity", equity.toPlainString(), "currency", a.baseCurrency(), "source", source));
        });
        LOG.infof("Start-of-day equity %s %s (%s)", equity.toPlainString(), a.baseCurrency(), source);
        return Optional.of(equity);
    }

    /**
     * Today's P&amp;L against the limit.
     *
     * @param tripped true when this call engaged the kill switch
     */
    public record Check(BigDecimal startOfDay, BigDecimal equity, BigDecimal pnl, BigDecimal limit, String currency,
                        boolean tripped) {
    }

    /** One check. Empty when there is nothing to compare (no start-of-day equity yet, IBKR unavailable). */
    public Optional<Check> check() {
        ZonedDateTime now = clock.marketNow();
        LocalDate today = now.toLocalDate();
        Optional<BigDecimal> sod = startOfDay(today);
        if (sod.isEmpty()) {
            if (!tradingHours(now)) {
                return Optional.empty();
            }
            sod = recordStartOfDay("first check after the open (the service was not running at 09:30)");
            if (sod.isEmpty()) {
                return Optional.empty();
            }
        }
        AccountSummary a;
        try {
            a = broker.accountSummary();
        } catch (BrokerException e) {
            LOG.debugf("Daily loss check skipped: %s", e.getMessage());
            return Optional.empty();
        }
        BigDecimal start = sod.get();
        BigDecimal pnl = a.netLiquidation().subtract(start);
        BigDecimal limit = start.multiply(config.risk().dailyLossPct()).divide(HUNDRED, 2, RoundingMode.HALF_UP)
                .negate();
        if (pnl.compareTo(limit) > 0 || start.signum() <= 0) {
            return Optional.of(new Check(start, a.netLiquidation(), pnl, limit, a.baseCurrency(), false));
        }
        boolean first = QuarkusTransaction.requiringNew().call(() -> {
            Setting s = settings.findById(TRIPPED_ON);
            if (s != null && today.toString().equals(s.value)) {
                return false;
            }
            put(TRIPPED_ON, today.toString());
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("date", today.toString());
            d.put("startOfDayEquity", start);
            d.put("equity", a.netLiquidation());
            d.put("pnl", pnl);
            d.put("limit", limit);
            d.put("dailyLossPct", config.risk().dailyLossPct());
            d.put("currency", a.baseCurrency());
            audit.write(AuditActor.SYSTEM, EVENT_TRIPPED, null, d);
            return true;
        });
        if (!first) {
            return Optional.of(new Check(start, a.netLiquidation(), pnl, limit, a.baseCurrency(), false));
        }
        String ccy = " " + a.baseCurrency();
        KillSwitch.Engaged k = killSwitch.engage(AuditActor.SYSTEM, "daily loss " + money(pnl) + ccy + " reached the "
                + "limit of " + config.risk().dailyLossPct().stripTrailingZeros().toPlainString() + "%");
        BigDecimal pct = pnl.multiply(HUNDRED).divide(start, 2, RoundingMode.HALF_UP);
        alerts.critical("Daily loss limit reached: today " + money(pnl) + ccy + " (" + pct.toPlainString()
                + "% of start-of-day equity " + money(start) + ccy + "; limit -"
                + config.risk().dailyLossPct().stripTrailingZeros().toPlainString() + "%). Kill switch ON: new "
                + "entries blocked" + (k.cancelled().isEmpty() ? "" : ", cancelled " + String.join(", ", k.cancelled()))
                + ". Stops and targets stay. /resume to allow entries again.");
        return Optional.of(new Check(start, a.netLiquidation(), pnl, limit, a.baseCurrency(), true));
    }

    private Optional<BigDecimal> startOfDay(LocalDate today) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Setting date = settings.findById(RiskContextBuilder.START_OF_DAY_DATE);
            Setting value = settings.findById(RiskContextBuilder.START_OF_DAY_EQUITY);
            if (date == null || value == null || !today.toString().equals(date.value)) {
                return Optional.<BigDecimal>empty();
            }
            try {
                return Optional.of(new BigDecimal(value.value.trim()));
            } catch (NumberFormatException e) {
                return Optional.<BigDecimal>empty();
            }
        });
    }

    private static boolean tradingHours(ZonedDateTime now) {
        DayOfWeek d = now.getDayOfWeek();
        LocalTime t = now.toLocalTime();
        return d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY && !t.isBefore(OPEN) && t.isBefore(CLOSE);
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

    private static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
