package dev.belis.trader.desk.support;

import dev.belis.trader.desk.telegram.Tg;
import dev.belis.trader.desk.telegram.TelegramClient;
import dev.belis.trader.desk.telegram.TelegramException;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Replaces the Telegram client in every test: records every call in order, returns message ids from 1000 up,
 * and serves queued updates to getUpdates. Nothing goes over the network.
 */
@Mock
@ApplicationScoped
public class RecordingTelegram extends TelegramClient {

    public sealed interface Call permits Sent, Edited, Answered {
    }

    public record Sent(long messageId, String text, Tg.InlineKeyboardMarkup keyboard) implements Call {
    }

    public record Edited(long messageId, String text, Tg.InlineKeyboardMarkup keyboard) implements Call {
    }

    public record Answered(String callbackQueryId, String text, boolean alert) implements Call {
    }

    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Deque<List<Tg.Update>> updates = new ArrayDeque<>();
    private final List<Long> polledOffsets = new CopyOnWriteArrayList<>();
    private final AtomicLong nextMessageId = new AtomicLong(1000);
    private volatile boolean failing;

    public RecordingTelegram() {
        super(null, null, null);
    }

    public synchronized void reset() {
        calls.clear();
        updates.clear();
        polledOffsets.clear();
        failing = false;
    }

    /** Every call fails with a TelegramException until reset. */
    public void failAll() {
        failing = true;
    }

    // ---- TelegramClient --------------------------------------------------------------------------------

    @Override
    public synchronized List<Tg.Update> getUpdates(Long offset, int timeoutSeconds) {
        polledOffsets.add(offset == null ? -1L : offset);
        fail();
        List<Tg.Update> next = updates.poll();
        return next == null ? List.of() : next;
    }

    @Override
    public long send(String html, Tg.InlineKeyboardMarkup keyboard) {
        fail();
        long id = nextMessageId.getAndIncrement();
        calls.add(new Sent(id, html, keyboard));
        return id;
    }

    @Override
    public void edit(long messageId, String html, Tg.InlineKeyboardMarkup keyboard) {
        fail();
        calls.add(new Edited(messageId, html, keyboard));
    }

    @Override
    public void answer(String callbackQueryId, String text, boolean alert) {
        fail();
        calls.add(new Answered(callbackQueryId, text, alert));
    }

    private void fail() {
        if (failing) {
            throw new TelegramException("test: Telegram is down");
        }
    }

    // ---- inspection -------------------------------------------------------------------------------------

    public synchronized void queueUpdates(Tg.Update... batch) {
        updates.add(List.of(batch));
    }

    /** Offsets passed to getUpdates, in order; -1 for null. */
    public List<Long> polledOffsets() {
        return List.copyOf(polledOffsets);
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public List<Sent> sent() {
        return of(Sent.class);
    }

    public List<Edited> edited() {
        return of(Edited.class);
    }

    public List<Answered> answered() {
        return of(Answered.class);
    }

    public Sent lastSent() {
        List<Sent> s = sent();
        if (s.isEmpty()) {
            throw new AssertionError("nothing was sent");
        }
        return s.get(s.size() - 1);
    }

    public Edited lastEdited() {
        List<Edited> e = edited();
        if (e.isEmpty()) {
            throw new AssertionError("nothing was edited");
        }
        return e.get(e.size() - 1);
    }

    /** Waits (for async alerts) until a sent message matches. */
    public Sent awaitSent(Predicate<Sent> match, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            for (Sent s : sent()) {
                if (match.test(s)) {
                    return s;
                }
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("no matching message sent within " + timeout + "; sent: " + sent());
    }

    private <T extends Call> List<T> of(Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Call c : calls) {
            if (type.isInstance(c)) {
                out.add(type.cast(c));
            }
        }
        return out;
    }
}
