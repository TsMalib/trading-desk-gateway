package dev.belis.trader.desk.telegram;

import dev.belis.trader.desk.config.DeskClock;
import dev.belis.trader.desk.config.DeskConfig;
import dev.belis.trader.desk.proposal.Decision;
import dev.belis.trader.desk.proposal.FlattenService;
import dev.belis.trader.desk.proposal.ProposalService;
import dev.belis.trader.desk.proposal.ProposalStatus;
import dev.belis.trader.desk.store.AuditActor;
import dev.belis.trader.desk.store.Instrument;
import dev.belis.trader.desk.store.Proposal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles one Telegram update: a button tap (callback query) or a command.
 *
 * <p>Only updates from the owner's chat ({@code desk.telegram.chatId}) sent by the owner
 * ({@code desk.telegram.userId}) are handled; every other update is ignored without an answer.
 *
 * <p>Buttons: {@code a:<id>} approve, {@code r:<id>} reject, {@code f:<token>} confirm a flatten,
 * {@code fx:<token>} cancel it. Every tap is answered (answerCallbackQuery) before any slow work.
 */
@ApplicationScoped
public class TelegramBot {

    private static final Logger LOG = Logger.getLogger(TelegramBot.class);

    /** Commands older than this are ignored (e.g. queued while the service was down). */
    static final Duration MAX_COMMAND_AGE = Duration.ofMinutes(2);
    /** A /flatten must be confirmed within this. */
    public static final Duration FLATTEN_CONFIRM_WINDOW = Duration.ofSeconds(60);

    private static final Pattern DECISION = Pattern.compile("([ar]):(\\d{1,18})");
    private static final Pattern FLATTEN = Pattern.compile("(f|fx):([0-9a-f]{16})");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeskConfig config;
    private final DeskClock clock;
    private final TelegramClient telegram;
    private final ProposalService proposals;
    private final ProposalNotifier notifier;
    private final FlattenService flattener;
    private final Commands commands;
    private final ProposalMessages messages;

    /** The one flatten waiting for confirmation, if any. A new /flatten replaces it. */
    private final AtomicReference<PendingFlatten> pendingFlatten = new AtomicReference<>();

    record PendingFlatten(String token, Instant validUntil, long messageId) {
    }

    public TelegramBot(DeskConfig config, DeskClock clock, TelegramClient telegram, ProposalService proposals,
                       ProposalNotifier notifier, FlattenService flattener, Commands commands) {
        this.config = config;
        this.clock = clock;
        this.telegram = telegram;
        this.proposals = proposals;
        this.notifier = notifier;
        this.flattener = flattener;
        this.commands = commands;
        this.messages = notifier.messages();
    }

    /** Handles one update. Throws only for Telegram or programming errors; the poller logs and moves on. */
    @ActivateRequestContext
    public void handle(Tg.Update u) {
        if (u.callbackQuery() != null) {
            Tg.CallbackQuery q = u.callbackQuery();
            Long chat = q.message() == null || q.message().chat() == null ? null : q.message().chat().id();
            if (!authorized(u.updateId(), chat, q.from())) {
                return;
            }
            onCallback(q);
        } else if (u.message() != null) {
            Tg.Message m = u.message();
            if (!authorized(u.updateId(), m.chat() == null ? null : m.chat().id(), m.from())) {
                return;
            }
            onMessage(m);
        }
    }

    private boolean authorized(long updateId, Long chatId, Tg.User from) {
        boolean ok = chatId != null && chatId == config.telegram().chatId()
                && from != null && from.id() == config.telegram().userId();
        if (!ok) {
            LOG.warnf("Ignoring Telegram update %d from chat %s user %s", updateId, chatId,
                    from == null ? null : from.id());
        }
        return ok;
    }

    // =====================================================================================================
    // Buttons
    // =====================================================================================================

    private void onCallback(Tg.CallbackQuery q) {
        String data = q.data() == null ? "" : q.data();
        Matcher d = DECISION.matcher(data);
        if (d.matches()) {
            onDecision(q, d.group(1).equals("a"), Long.parseLong(d.group(2)));
            return;
        }
        Matcher f = FLATTEN.matcher(data);
        if (f.matches()) {
            onFlattenButton(q, f.group(1).equals("f"), f.group(2));
            return;
        }
        telegram.answer(q.id(), "Unknown button.", false);
    }

    private void onDecision(Tg.CallbackQuery q, boolean approve, long id) {
        Proposal p = proposals.find(id).orElse(null);
        if (p == null) {
            telegram.answer(q.id(), "Proposal #" + id + " does not exist.", false);
            return;
        }
        if (ProposalService.parse(p.status) != ProposalStatus.PROPOSED) {
            telegram.answer(q.id(), "Proposal #" + id + " is already " + p.status + ". Nothing changed.", false);
            return;
        }
        telegram.answer(q.id(), approve ? "Re-checking #" + id + " with a fresh quote…" : "Rejecting #" + id, false);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("via", "telegram");
        details.put("telegramUser", q.from().id());
        Decision decision = approve ? proposals.approve(id, details) : proposals.reject(id, details);
        LOG.infof("Proposal %d: %s by the owner -> %s", id, approve ? "approve" : "reject", decision.outcome());

        long messageId = q.message().messageId();
        Proposal now = proposals.find(id).orElse(p);
        Instrument i = proposals.instrument(now.conid).orElse(null);
        switch (decision.outcome()) {
            case UNAVAILABLE -> telegram.edit(messageId, messages.unavailable(now, i, notifier.position(now),
                    clock.instant(), decision.message()), ProposalMessages.keyboard(id));
            case ALREADY_DECIDED, NOT_FOUND -> {
                // decided in between by something else (expiry, flatten), which updates the message itself
            }
            default -> telegram.edit(messageId, messages.decided(now, i, notifier.position(now)), null);
        }
    }

    private void onFlattenButton(Tg.CallbackQuery q, boolean confirm, String token) {
        long messageId = q.message().messageId();
        PendingFlatten pending = pendingFlatten.get();
        boolean valid = pending != null && pending.token().equals(token)
                && clock.instant().isBefore(pending.validUntil());
        if (!confirm) {
            if (pending != null && pending.token().equals(token)) {
                pendingFlatten.compareAndSet(pending, null);
            }
            telegram.answer(q.id(), "Flatten cancelled.", false);
            telegram.edit(messageId, messages.tag() + " Flatten cancelled. Nothing was done.", null);
            return;
        }
        if (!valid || !pendingFlatten.compareAndSet(pending, null)) {
            telegram.answer(q.id(), "This confirmation is no longer valid. Send /flatten again.", true);
            telegram.edit(messageId, messages.tag() + " Flatten not confirmed in time. Nothing was done.", null);
            return;
        }
        telegram.answer(q.id(), "Flattening…", false);
        telegram.edit(messageId, messages.tag() + " Flattening: cancelling orders and selling positions…", null);

        FlattenService.Result r = flattener.flatten(AuditActor.USER,
                Map.of("via", "telegram", "telegramUser", q.from().id()));
        telegram.edit(messageId, commands.flattenResult(r), null);
        r.cancelledProposals().forEach(ref -> notifier.refresh(Long.parseLong(ref.substring(1))));
    }

    // =====================================================================================================
    // Commands
    // =====================================================================================================

    private void onMessage(Tg.Message m) {
        String text = m.text() == null ? "" : m.text().strip();
        if (!text.startsWith("/")) {
            return;
        }
        if (m.date() != null) {
            Instant sent = Instant.ofEpochSecond(m.date());
            if (sent.plus(MAX_COMMAND_AGE).isBefore(clock.instant())) {
                LOG.warnf("Ignoring command '%s' sent at %s: older than %s", text, sent, MAX_COMMAND_AGE);
                return;
            }
        }
        String command = text.split("\\s+", 2)[0];
        int at = command.indexOf('@');
        if (at > 0) {
            command = command.substring(0, at);
        }
        String reply;
        Tg.InlineKeyboardMarkup keyboard = null;
        switch (command.toLowerCase(Locale.ROOT)) {
            case "/status" -> reply = commands.status();
            case "/positions" -> reply = commands.positions();
            case "/pending" -> reply = commands.pending();
            case "/kill" -> reply = commands.kill();
            case "/resume" -> reply = commands.resume();
            case "/flatten" -> {
                Commands.FlattenPrompt prompt = commands.flattenPrompt();
                reply = prompt.text();
                if (prompt.canConfirm()) {
                    String token = HexFormat.of().formatHex(randomBytes());
                    keyboard = Tg.InlineKeyboardMarkup.row(
                            new Tg.InlineKeyboardButton("🔥 Confirm flatten", "f:" + token),
                            new Tg.InlineKeyboardButton("Cancel", "fx:" + token));
                    long messageId = telegram.send(reply, keyboard);
                    PendingFlatten old = pendingFlatten.getAndSet(
                            new PendingFlatten(token, clock.instant().plus(FLATTEN_CONFIRM_WINDOW), messageId));
                    if (old != null) {
                        telegram.edit(old.messageId(), messages.tag()
                                + " Replaced by a newer /flatten. Nothing was done.", null);
                    }
                    return;
                }
            }
            default -> reply = commands.help();
        }
        telegram.send(reply, keyboard);
    }

    private static byte[] randomBytes() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        return b;
    }
}
