package dev.belis.trader.desk.proposal;

import dev.belis.trader.desk.store.AuditActor;
import dev.belis.trader.desk.store.AuditWriter;
import dev.belis.trader.desk.store.Setting;
import dev.belis.trader.desk.store.SettingRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The kill switch (setting kill_switch). While on, every ENTRY fails the KILL_SWITCH check, at proposal time
 * and at approval time. Exits and stop updates are still allowed: they reduce risk.
 */
@ApplicationScoped
public class KillSwitch {

    public static final String EVENT_ON = "KILL_SWITCH_ON";
    public static final String EVENT_OFF = "KILL_SWITCH_OFF";

    private final RiskContextBuilder contexts;
    private final SettingRepository settings;
    private final AuditWriter audit;

    public KillSwitch(RiskContextBuilder contexts, SettingRepository settings, AuditWriter audit) {
        this.contexts = contexts;
        this.settings = settings;
        this.audit = audit;
    }

    public boolean isOn() {
        return QuarkusTransaction.requiringNew().call(contexts::killSwitch);
    }

    /**
     * Sets the switch and writes an audit row (KILL_SWITCH_ON / KILL_SWITCH_OFF), also when it was already in
     * that position. Returns the previous position.
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
