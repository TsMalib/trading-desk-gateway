package dev.belis.trader.desk.telegram;

import dev.belis.trader.desk.broker.BrokerReconnected;
import dev.belis.trader.desk.config.DeskClock;
import dev.belis.trader.desk.config.DeskConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Alerts to the owner's Telegram chat, for any package: {@code alerts.warning("IBKR connection lost")}.
 *
 * <p>Never blocks and never throws: the message is sent on the managed executor, and a failure is logged.
 * Every alert is also logged. The same text at the same level is sent at most once per
 * {@link #DEDUPE_WINDOW}, so a flapping condition cannot flood the chat.
 */
@ApplicationScoped
public class AlertService {

    private static final Logger LOG = Logger.getLogger(AlertService.class);

    /** The same alert is not repeated within this window. */
    public static final Duration DEDUPE_WINDOW = Duration.ofMinutes(5);

    public enum Level {
        INFO("ℹ️"), WARNING("⚠️"), CRITICAL("🚨");

        final String icon;

        Level(String icon) {
            this.icon = icon;
        }
    }

    private final TelegramClient telegram;
    private final DeskConfig config;
    private final DeskClock clock;
    private final ManagedExecutor executor;
    private final Map<String, Instant> lastSent = new ConcurrentHashMap<>();

    public AlertService(TelegramClient telegram, DeskConfig config, DeskClock clock, ManagedExecutor executor) {
        this.telegram = telegram;
        this.config = config;
        this.clock = clock;
        this.executor = executor;
    }

    public CompletableFuture<Void> info(String text) {
        return alert(Level.INFO, text);
    }

    public CompletableFuture<Void> warning(String text) {
        return alert(Level.WARNING, text);
    }

    public CompletableFuture<Void> critical(String text) {
        return alert(Level.CRITICAL, text);
    }

    /**
     * Sends {@code text} (plain text, escaped here) to the owner. The future completes when it was sent,
     * skipped as a duplicate, or failed; it never completes exceptionally.
     */
    public CompletableFuture<Void> alert(Level level, String text) {
        String t = text == null ? "" : text.strip();
        switch (level) {
            case INFO -> LOG.infof("Alert: %s", t);
            case WARNING -> LOG.warnf("Alert: %s", t);
            case CRITICAL -> LOG.errorf("Alert: %s", t);
        }
        Instant now = clock.instant();
        String key = level + "|" + t;
        Instant previous = lastSent.get(key);
        if (previous != null && now.isBefore(previous.plus(DEDUPE_WINDOW))) {
            return CompletableFuture.completedFuture(null);
        }
        lastSent.put(key, now);
        String html = "[" + config.mode().name() + "] " + level.icon + " " + ProposalMessages.esc(t);
        return executor.runAsync(() -> {
            try {
                telegram.send(html, null);
            } catch (RuntimeException e) {
                LOG.errorf("Could not send alert to Telegram: %s", e.getMessage());
            }
        });
    }

    void onReconnected(@Observes BrokerReconnected e) {
        warning("IBKR connection restored (" + switch (e.reason()) {
            case SOCKET_RECONNECTED -> "IB Gateway socket reconnected";
            case IB_SERVER_RESTORED -> "IB Gateway reconnected to IBKR";
        } + "). Check positions and orders with /positions.");
    }

    /** Forgets what was sent, so the next alert of any text goes out (tests). */
    void resetDedupe() {
        lastSent.clear();
    }
}
