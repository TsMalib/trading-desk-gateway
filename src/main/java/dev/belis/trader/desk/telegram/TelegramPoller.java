package dev.belis.trader.desk.telegram;

import dev.belis.trader.desk.config.DeskConfig;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Future;

/**
 * Long-polls Telegram (getUpdates, timeout 50 s) on the managed executor and hands each update to
 * {@link TelegramBot}, one at a time, in order.
 *
 * <p>Offset: after an update is taken, the next getUpdates asks for update_id + 1, which also tells Telegram to
 * drop everything up to it. The offset lives in memory; after a restart Telegram re-delivers what was not
 * confirmed (at most the last batch). Re-delivery is harmless: a tap on a decided proposal changes nothing,
 * flatten tokens do not survive a restart, and commands older than {@link TelegramBot#MAX_COMMAND_AGE} are
 * ignored.
 *
 * <p>Errors back off from 1 s to 30 s. Off when {@code desk.telegram.polling=false} (tests).
 */
@ApplicationScoped
public class TelegramPoller {

    private static final Logger LOG = Logger.getLogger(TelegramPoller.class);

    public static final int POLL_TIMEOUT_SECONDS = 50;
    private static final Duration MIN_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final TelegramClient telegram;
    private final TelegramBot bot;
    private final DeskConfig config;
    private final ManagedExecutor executor;

    private volatile boolean running;
    private volatile Long offset;
    private volatile Future<?> loop;

    public TelegramPoller(TelegramClient telegram, TelegramBot bot, DeskConfig config, ManagedExecutor executor) {
        this.telegram = telegram;
        this.bot = bot;
        this.config = config;
        this.executor = executor;
    }

    void start(@Observes StartupEvent e) {
        if (!config.telegram().polling()) {
            LOG.info("Telegram polling is off (desk.telegram.polling=false)");
            return;
        }
        running = true;
        loop = executor.runAsync(this::run);
        LOG.infof("Telegram polling started (%s mode)", config.mode());
    }

    void stop(@Observes ShutdownEvent e) {
        running = false;
        Future<?> f = loop;
        if (f != null) {
            f.cancel(true);
        }
    }

    private void run() {
        Duration backoff = MIN_BACKOFF;
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                pollOnce();
                backoff = MIN_BACKOFF;
            } catch (RuntimeException ex) {
                if (!running) {
                    return;
                }
                LOG.warnf("Telegram getUpdates failed, retrying in %d s: %s", backoff.toSeconds(), ex.getMessage());
                try {
                    Thread.sleep(backoff.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
            }
        }
    }

    /**
     * One getUpdates call from the current offset; every update returned is handled and the offset moves past
     * it, also when handling fails (a poison update must not block the queue). Returns how many came.
     *
     * @throws TelegramException when getUpdates itself fails (the offset is unchanged)
     */
    public int pollOnce() {
        List<Tg.Update> updates = telegram.getUpdates(offset, POLL_TIMEOUT_SECONDS);
        for (Tg.Update u : updates) {
            if (offset == null || u.updateId() + 1 > offset) {
                offset = u.updateId() + 1;
            }
            try {
                bot.handle(u);
            } catch (RuntimeException ex) {
                LOG.errorf(ex, "Telegram update %d failed", u.updateId());
            }
        }
        return updates.size();
    }

    /** The offset the next getUpdates sends; null before the first update. */
    public Long offset() {
        return offset;
    }
}
