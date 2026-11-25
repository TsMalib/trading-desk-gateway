package dev.belis.trader.desk.proposal;

import dev.belis.trader.desk.store.AuditActor;
import dev.belis.trader.desk.store.AuditWriter;
import dev.belis.trader.desk.store.Setting;
import dev.belis.trader.desk.store.SettingRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The kill switch (setting kill_switch). While on, every ENTRY fails the KILL_SWITCH check, at proposal time
 * and at approval time. Exits and stop updates are still allowed: they reduce risk.
 *
 * <p>{@link #engage} (Telegram /kill, the daily loss limit) also cancels the unfilled part of every ENTRY parent
 * still working at IBKR, and approved entries not sent yet. Targets and stops of open positions stay.
 */
@ApplicationScoped
public class KillSwitch {

    private static final Logger LOG = Logger.getLogger(KillSwitch.class);

    public static final String EVENT_ON = "KILL_SWITCH_ON";
    public static final String EVENT_OFF = "KILL_SWITCH_OFF";

    /** How long {@link #engage} waits for the order executor to cancel the entries. */
    static final Duration ENGAGE_WAIT = Duration.ofSeconds(30);

    private final RiskContextBuilder contexts;
    private final SettingRepository settings;
    private final AuditWriter audit;
    private final OrderExecutor executor;

    public KillSwitch(RiskContextBuilder contexts, SettingRepository settings, AuditWriter audit,
                      OrderExecutor executor) {
        this.contexts = contexts;
        this.settings = settings;
        this.audit = audit;
        this.executor = executor;
    }

    public boolean isOn() {
        return QuarkusTransaction.requiringNew().call(contexts::killSwitch);
    }

    /**
     * What {@link #engage} did.
     *
     * @param wasOn     the switch was already on
     * @param cancelled entry refs IBKR was asked to cancel
     * @param complete  false when the order executor did not finish within {@link #ENGAGE_WAIT} (it carries on)
     */
    public record Engaged(boolean wasOn, List<String> cancelled, boolean complete) {
    }

    /** Switch on, then cancel working entry parents on the order executor's worker. */
    public Engaged engage(AuditActor actor, String reason) {
        boolean was = set(true, actor, reason);
        List<String> cancelled = new ArrayList<>();
        try {
            executor.submit("Kill switch: cancel entries", () ->
                            cancelled.addAll(executor.cancelWorkingEntries(actor, "kill switch: " + reason)))
                    .get(ENGAGE_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            return new Engaged(was, List.copyOf(cancelled), true);
        } catch (TimeoutException e) {
            LOG.warnf("Kill switch: entries not cancelled within %s; the order executor carries on", ENGAGE_WAIT);
            return new Engaged(was, List.of(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Engaged(was, List.of(), false);
        } catch (java.util.concurrent.ExecutionException e) {
            return new Engaged(was, List.of(), false); // submit() never fails; kept for the checked signature
        }
    }

    /**
     * Sets the switch and writes an audit row (KILL_SWITCH_ON / KILL_SWITCH_OFF), also when it was already in
     * that position. Returns the previous position. Touches no order: see {@link #engage}.
     */
    public boolean set(boolean on, AuditActor actor, String reason) {
        return QuarkusTransaction.requiringNew().call(() -> {
            boolean was = contexts.killSwitch();
            Setting s = settings.findById(RiskContextBuilder.KILL_SWITCH);
            if (s == null) {
                s = new Setting();
                s.key = RiskContextBuilder.KILL_SWITCH;
                s.value = Boolean.toString(on);
                settings.persist(s);
            } else {
                s.value = Boolean.toString(on);
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("was", was);
            d.put("reason", reason);
            audit.write(actor, on ? EVENT_ON : EVENT_OFF, null, d);
            return was;
        });
    }
}
