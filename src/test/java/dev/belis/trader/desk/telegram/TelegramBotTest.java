package dev.belis.trader.desk.telegram;

import dev.belis.trader.desk.broker.BracketOrder;
import dev.belis.trader.desk.broker.ConnectionState;
import dev.belis.trader.desk.broker.FakeBroker;
import dev.belis.trader.desk.proposal.KillSwitch;
import dev.belis.trader.desk.proposal.ProposalOutcome;
import dev.belis.trader.desk.proposal.ProposalRequests;
import dev.belis.trader.desk.proposal.ProposalService;
import dev.belis.trader.desk.store.AuditActor;
import dev.belis.trader.desk.store.AuditLog;
import dev.belis.trader.desk.store.AuditLogRepository;
import dev.belis.trader.desk.store.BrokerOrder;
import dev.belis.trader.desk.store.BrokerOrderRepository;
import dev.belis.trader.desk.store.Proposal;
import dev.belis.trader.desk.store.ProposalKind;
import dev.belis.trader.desk.store.ProposalRepository;
import dev.belis.trader.desk.store.Side;
import dev.belis.trader.desk.support.DecisionEvents;
import dev.belis.trader.desk.support.FixedClock;
import dev.belis.trader.desk.support.Market;
import dev.belis.trader.desk.support.RecordingTelegram;
import dev.belis.trader.desk.support.RecordingTelegram.Answered;
import dev.belis.trader.desk.support.RecordingTelegram.Edited;
import dev.belis.trader.desk.support.RecordingTelegram.Sent;
import dev.belis.trader.desk.support.TestDb;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static dev.belis.trader.desk.support.Market.AAPL;
import static dev.belis.trader.desk.support.Market.MSFT;
import static dev.belis.trader.desk.support.Market.TSLA;
import static dev.belis.trader.desk.support.Market.bd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class TelegramBotTest {

    static final long CHAT = 1;
    static final long OWNER = 2;
    static final long STRANGER = 99;

    @Inject
    TelegramBot bot;

    @Inject
    TelegramPoller poller;

    @Inject
    AlertService alerts;

    @Inject
    RecordingTelegram telegram;

    @Inject
    ProposalService service;

    @Inject
    KillSwitch killSwitch;

    @Inject
    FakeBroker fake;

    @Inject
    ProposalRepository proposals;

    @Inject
    BrokerOrderRepository brokerOrders;

    @Inject
    AuditLogRepository auditLogs;

    @Inject
    DecisionEvents events;

    @Inject
    TestDb db;

    @Inject
    dev.belis.trader.desk.proposal.OrderExecutor orderExecutor;

    FixedClock clock;
    final AtomicLong updateIds = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        db.clean();
        telegram.reset();
        events.clear();
        alerts.resetDedupe();
        clock = FixedClock.install();
        Market.open(fake, FixedClock.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        db.clean();
        fake.reset();
        telegram.reset();
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    ProposalOutcome proposeAapl() {
        return service.proposeEntry(new ProposalRequests.Entry("AAPL", bd("100"), bd("95"), bd("112"),
                "Breakout above the 50-day high on volume.", List.of("https://www.example.com/aapl"), false,
                "grok-test"));
    }

    Proposal load(long id) {
        return QuarkusTransaction.requiringNew().call(() -> proposals.findById(id));
    }

    List<AuditLog> audit(String event) {
        return QuarkusTransaction.requiringNew().call(() -> auditLogs.list("event = ?1 order by id", event));
    }

    @Inject
    dev.belis.trader.desk.store.PositionMetaRepository positionMetas;

    /** Marks the position in {@code symbol} as bought by this service (position_meta origin GATEWAY). */
    void gateway(String symbol) {
        var c = fake.contract(symbol);
        QuarkusTransaction.requiringNew().run(() -> {
            service.saveInstrument(c);
            var m = new dev.belis.trader.desk.store.PositionMeta();
            m.conid = c.conid();
            m.origin = dev.belis.trader.desk.store.PositionOrigin.GATEWAY;
            m.openedAt = clock.instant();
            positionMetas.persist(m);
        });
    }

    Tg.Update tap(String data, long messageId) {
        return tapAs(data, messageId, CHAT, OWNER);
    }

    Tg.Update tapAs(String data, long messageId, long chat, long user) {
        long id = updateIds.incrementAndGet();
        return new Tg.Update(id, null, new Tg.CallbackQuery("cb" + id, new Tg.User(user, false, "u"),
                new Tg.Message(messageId, null, new Tg.Chat(chat, "private"), 0L, "…"), data));
    }

    Tg.Update command(String text) {
        return commandAs(text, CHAT, OWNER, clock.instant());
    }

    Tg.Update commandAs(String text, long chat, long user, Instant sent) {
        long id = updateIds.incrementAndGet();
        return new Tg.Update(id, new Tg.Message(id, new Tg.User(user, false, "u"), new Tg.Chat(chat, "private"),
                sent.getEpochSecond(), text), null);
    }

    String reply(String command) {
        int before = telegram.sent().size();
        bot.handle(command(command));
        assertEquals(before + 1, telegram.sent().size(), "one reply to " + command);
        return telegram.lastSent().text();
    }

    static String callbackData(Tg.InlineKeyboardMarkup k, int button) {
        return k.inlineKeyboard().get(0).get(button).callbackData();
    }

    // =====================================================================================================

    @Nested
    class ProposalMessage {

        @Test
        void newProposalIsSentWithButtonsAndLinked() {
            long id = proposeAapl().proposalId();

            Sent s = telegram.lastSent();
            assertEquals("[PAPER] <b>#" + id + " BUY AAPL</b> · AAPL Inc\n"
                    + "<pre>Entry   100.00 LMT\n"
                    + "Stop    95.00 (-5.00%)\n"
                    + "Target  112.00 (+12.00%)\n"
                    + "Size    100 sh = 10,000.00 USD · 10.00% of equity\n"
                    + "Risk    500.00 USD · 0.50% of equity\n"
                    + "R/R     2.40\n"
                    + "Last    100.20 · drift -0.20%</pre>\n"
                    + "<b>Thesis</b>\n"
                    + "Breakout above the 50-day high on volume.\n"
                    + "<b>Sources</b> example.com\n"
                    + "\n"
                    + "Expires 11:30 ET · by grok-test", s.text());
            assertEquals("a:" + id, callbackData(s.keyboard(), 0));
            assertEquals("r:" + id, callbackData(s.keyboard(), 1));

            assertEquals(s.messageId(), load(id).telegramMsgId);
            AuditLog linked = audit(ProposalService.EVENT_TELEGRAM_SENT).get(0);
            assertEquals(id, linked.proposalId);
            assertEquals(s.messageId(), linked.details.get("messageId").asLong());
        }

        @Test
        void rejectedProposalsAreNotSent() {
            killSwitch.set(true, AuditActor.USER, "test");
            assertFalse(proposeAapl().accepted());
            assertTrue(telegram.sent().isEmpty());
        }

        @Test
        void telegramDownDoesNotFailTheProposal() {
            telegram.failAll();
            ProposalOutcome o = proposeAapl();
            assertTrue(o.accepted());
            assertNull(load(o.proposalId()).telegramMsgId);
        }

        @Test
        void exitAndStopUpdateGetTheirOwnLayouts() {
            fake.placeBracket(new BracketOrder(900, AAPL, bd("10"), bd("100.50"), bd("120"), bd("90")));
            long exit = service.proposeExit(new ProposalRequests.Exit("AAPL", null, "Thesis broken.", "grok-test"))
                    .proposalId();
            assertTrue(telegram.lastSent().text().startsWith("[PAPER] <b>#" + exit + " SELL AAPL</b>"),
                    telegram.lastSent().text());
            assertTrue(telegram.lastSent().text().contains("Sell    10 sh MKT\nHeld    10 sh (all)"));
            assertEquals("a:" + exit, callbackData(telegram.lastSent().keyboard(), 0));

            long stop = service.proposeStopUpdate(new ProposalRequests.StopUpdate("AAPL", bd("95"), "Trail.",
                    "grok-test")).proposalId();
            String text = telegram.lastSent().text();
            assertTrue(text.startsWith("[PAPER] <b>#" + stop + " MOVE STOP AAPL</b>"), text);
            assertTrue(text.contains("Stop    90.00 → 95.00 (+5.56%)"), text);
        }

        @Test
        void entryExpiresAtTheEndOfTheEntryWindow() {
            Instant at = Instant.parse("2026-11-24T20:15:00Z"); // 15:15 New York, close 16:00, buffer 30 min
            clock.set(at);
            Market.open(fake, at);
            ProposalOutcome o = proposeAapl();
            assertTrue(o.accepted(), o.failures().toString());
            assertEquals(Instant.parse("2026-11-24T20:30:00Z"), o.expiresAt());
            assertTrue(telegram.lastSent().text().endsWith("Expires 15:30 ET · by grok-test"));
        }

        @Test
        void expiryJobRewritesTheMessage() {
            ProposalOutcome o = proposeAapl();
            long messageId = telegram.lastSent().messageId();
            clock.set(o.expiresAt());

            assertEquals(1, service.expireDue());

            assertEquals("EXPIRED", load(o.proposalId()).status);
            assertEquals(1, events.expired().size());
            Edited e = telegram.lastEdited();
            assertEquals(messageId, e.messageId());
            assertNull(e.keyboard(), "buttons removed");
            assertTrue(e.text().endsWith("\n\n⌛ <b>EXPIRED</b> 11:30 ET · not decided in time"), e.text());
        }
    }

    // =====================================================================================================

    @Nested
    class Buttons {

        @Test
        void approveAnswersFirstThenEditsAndFiresApproved() {
            long id = proposeAapl().proposalId();
            long messageId = telegram.lastSent().messageId();
            telegram.reset();

            bot.handle(tap("a:" + id, messageId));

            List<RecordingTelegram.Call> calls = telegram.calls();
            assertEquals(2, calls.size(), calls.toString());
            Answered a = assertInstanceOf(Answered.class, calls.get(0));
            assertEquals("Re-checking #" + id + " with a fresh quote…", a.text());
            Edited e = assertInstanceOf(Edited.class, calls.get(1));
            assertEquals(messageId, e.messageId());
            assertNull(e.keyboard());
            assertTrue(e.text().contains("\n\n✅ <b>APPROVED</b> 11:00 ET · all checks passed again (last 100.20)"),
                    e.text());

            Proposal p = load(id);
            assertEquals("APPROVED", p.status);
            assertTrue(p.riskResult.path("approval").path("passed").asBoolean());
            assertEquals(1, events.approved().size());
            assertEquals(id, events.approved().get(0).proposalId());

            AuditLog approved = audit(ProposalService.EVENT_STATUS_CHANGED).get(0);
            assertEquals(AuditActor.USER, approved.actor);
            assertEquals("APPROVED", approved.details.get("to").asText());
            assertEquals(OWNER, approved.details.get("telegramUser").asLong());
        }

        @Test
        void approveRejectedByAFreshQuoteShowsTheReasons() {
            long id = proposeAapl().proposalId();
            long messageId = telegram.lastSent().messageId();
            Market.quote(fake, "AAPL", "102.00"); // drift 1.96% > 1.0%

            bot.handle(tap("a:" + id, messageId));

            assertEquals("REJECTED_RISK", load(id).status);
            assertTrue(events.approved().isEmpty());
            Edited e = telegram.lastEdited();
            assertNull(e.keyboard());
            assertTrue(e.text().contains("⛔ <b>REJECTED</b> 11:00 ET · the re-check with a fresh quote failed:\n"
                    + "• DRIFT: 1.9608% (limit &lt;= 1.0%)"), e.text());
            List<AuditLog> changes = audit(ProposalService.EVENT_STATUS_CHANGED);
            assertEquals(List.of("APPROVED", "REJECTED_RISK"), changes.stream().map(c -> c.details.get("to").asText()).toList());
            assertEquals(List.of(AuditActor.USER, AuditActor.SYSTEM), changes.stream().map(c -> c.actor).toList());
        }

        @Test
        void reject() {
            long id = proposeAapl().proposalId();
            long messageId = telegram.lastSent().messageId();
            telegram.reset();

            bot.handle(tap("r:" + id, messageId));

            assertEquals("REJECTED_USER", load(id).status);
            assertInstanceOf(Answered.class, telegram.calls().get(0));
            assertTrue(telegram.lastEdited().text().endsWith("\n\n❌ <b>REJECTED</b> by you 11:00 ET"));
            assertNull(telegram.lastEdited().keyboard());
        }

        @Test
        void secondTapOnlySaysItIsDecided() {
            long id = proposeAapl().proposalId();
            long messageId = telegram.lastSent().messageId();
            bot.handle(tap("r:" + id, messageId));
            telegram.reset();
            int auditRows = audit(ProposalService.EVENT_STATUS_CHANGED).size();

            bot.handle(tap("a:" + id, messageId));

            assertEquals(1, telegram.calls().size(), telegram.calls().toString());
            assertEquals("Proposal #" + id + " is already REJECTED_USER. Nothing changed.",
                    telegram.answered().get(0).text());
            assertEquals("REJECTED_USER", load(id).status);
            assertEquals(auditRows, audit(ProposalService.EVENT_STATUS_CHANGED).size());
            assertTrue(events.approved().isEmpty());
        }

        @Test
        void ibkrDownKeepsItPendingWithTheButtons() {
            long id = proposeAapl().proposalId();
            long messageId = telegram.lastSent().messageId();
            fake.setConnectionState(ConnectionState.DISCONNECTED);

            bot.handle(tap("a:" + id, messageId));

            assertEquals("PROPOSED", load(id).status);
            Edited e = telegram.lastEdited();
            assertNotNull(e.keyboard(), "buttons stay");
            assertTrue(e.text().contains("⚠️ IBKR could not be asked at 11:00 ET: IBKR is not connected right now."),
                    e.text());
            assertEquals(1, audit(ProposalService.EVENT_APPROVAL_UNAVAILABLE).size());

            fake.setConnectionState(ConnectionState.CONNECTED);
            bot.handle(tap("a:" + id, messageId));
            assertEquals("APPROVED", load(id).status);
        }

        @Test
        void approveAfterExpiryExpires() {
            ProposalOutcome o = proposeAapl();
            long messageId = telegram.lastSent().messageId();
            clock.set(o.expiresAt().plusSeconds(1));

            bot.handle(tap("a:" + o.proposalId(), messageId));

            assertEquals("EXPIRED", load(o.proposalId()).status);
            assertTrue(events.approved().isEmpty());
            assertTrue(telegram.lastEdited().text().contains("⌛ <b>EXPIRED</b>"), telegram.lastEdited().text());
        }

        @Test
        void unknownProposalAndUnknownButton() {
            bot.handle(tap("a:424242", 5));
            assertEquals("Proposal #424242 does not exist.", telegram.answered().get(0).text());
            bot.handle(tap("zz", 5));
            assertEquals("Unknown button.", telegram.answered().get(1).text());
            assertTrue(telegram.edited().isEmpty());
        }
    }

    // =====================================================================================================

    @Nested
    class Authorization {

        @Test
        void tapsFromAnotherUserOrChatAreIgnored() {
            long id = proposeAapl().proposalId();
            long messageId = telegram.lastSent().messageId();
            telegram.reset();

            bot.handle(tapAs("a:" + id, messageId, CHAT, STRANGER));
            bot.handle(tapAs("a:" + id, messageId, STRANGER, OWNER));
            bot.handle(tapAs("r:" + id, messageId, STRANGER, STRANGER));

            assertTrue(telegram.calls().isEmpty(), "not even answered");
            assertEquals("PROPOSED", load(id).status);
        }

        @Test
        void commandsFromAnotherUserOrChatAreIgnored() {
            bot.handle(commandAs("/kill", CHAT, STRANGER, clock.instant()));
            bot.handle(commandAs("/kill", STRANGER, OWNER, clock.instant()));
            assertTrue(telegram.calls().isEmpty());
            assertFalse(killSwitch.isOn());
        }

        @Test
        void oldCommandsAreIgnored() {
            bot.handle(commandAs("/kill", CHAT, OWNER, clock.instant().minus(Duration.ofMinutes(10))));
            assertTrue(telegram.calls().isEmpty());
            assertFalse(killSwitch.isOn());
        }
    }

    // =====================================================================================================

    @Nested
    class CommandReplies {

        @Test
        void status() {
            String text = reply("/status");
            assertTrue(text.startsWith("[PAPER] <b>Status</b>\n<pre>"), text);
            assertTrue(text.contains("Mode      PAPER"), text);
            assertTrue(text.contains("IBKR      CONNECTED"), text);
            assertTrue(text.contains("Kill      off"), text);
            assertTrue(text.contains("Equity    100,000.00 USD"), text);
            assertTrue(text.contains("Time      Tue 24 Nov 11:00 ET"), text);
        }

        @Test
        void statusWithIbkrDown() {
            fake.setConnectionState(ConnectionState.DISCONNECTED);
            String text = reply("/status");
            assertTrue(text.contains("IBKR      DISCONNECTED"), text);
            assertTrue(text.contains("Account   IBKR unavailable"), text);
        }

        @Test
        void killAndResume() {
            String text = reply("/kill");
            assertTrue(text.startsWith("[PAPER] 🛑 Kill switch <b>ON</b>."), text);
            assertTrue(killSwitch.isOn());
            AuditLog on = audit(KillSwitch.EVENT_ON).get(0);
            assertEquals(AuditActor.USER, on.actor);

            ProposalOutcome o = proposeAapl();
            assertFalse(o.accepted());
            assertEquals("KILL_SWITCH", o.failures().get(0).name());

            text = reply("/resume");
            assertTrue(text.startsWith("[PAPER] ▶️ Kill switch <b>off</b>."), text);
            assertFalse(killSwitch.isOn());
            assertEquals(1, audit(KillSwitch.EVENT_OFF).size());
            assertTrue(proposeAapl().accepted());
        }

        @Test
        void pending() {
            assertEquals("[PAPER] No open proposals.", reply("/pending"));
            long id = proposeAapl().proposalId();
            assertEquals("[PAPER] <b>Open proposals</b> (1)\n#" + id
                    + " BUY AAPL 100 @ 100.00 · PROPOSED · expires 11:30 ET", reply("/pending"));
        }

        @Test
        void positions() {
            assertEquals("[PAPER] No positions.", reply("/positions"));
            fake.setPosition(MSFT, bd("10"), bd("395.5"));
            fake.placeBracket(new BracketOrder(900, AAPL, bd("10"), bd("100.50"), bd("120"), bd("90")));
            String text = reply("/positions");
            assertTrue(text.startsWith("[PAPER] <b>Positions</b> (2)\n<pre>"), text);
            assertTrue(text.contains("AAPL 10 @ "), text);
            assertTrue(text.contains("stop 90.00 · target 120.00"), text);
            assertTrue(text.contains("MSFT 10 @ 395.50 USD\n  stop NONE ⚠️ · target none · external"), text);
        }

        @Test
        void commandWithBotNameAndUnknownCommand() {
            assertTrue(reply("/status@DeskBot").contains("<b>Status</b>"));
            assertTrue(reply("/nope").startsWith("[PAPER] <b>Commands</b>"));
        }
    }

    // =====================================================================================================

    @Nested
    class Flatten {

        void book() {
            fake.placeBracket(new BracketOrder(900, AAPL, bd("10"), bd("100.50"), bd("120"), bd("90"))); // fills
            fake.placeBracket(new BracketOrder(901, MSFT, bd("5"), bd("390"), bd("450"), bd("370")));    // working
            fake.setPosition(TSLA, bd("3"), bd("240"));                                                  // external
            gateway("AAPL");
        }

        Sent prompt() {
            bot.handle(command("/flatten"));
            return telegram.lastSent();
        }

        @Test
        void confirmCancelsAndSellsEveryGatewayPosition() {
            long proposalId = proposeAapl().proposalId();
            book();

            Sent prompt = prompt();
            assertTrue(prompt.text().startsWith("[PAPER] 🔥 <b>Flatten?</b>"), prompt.text());
            assertTrue(prompt.text().contains("• AAPL 10 sh\nNot sold (EXTERNAL, never touched): TSLA 3"),
                    prompt.text());
            String confirm = callbackData(prompt.keyboard(), 0);
            assertTrue(confirm.startsWith("f:"), confirm);

            bot.handle(tap(confirm, prompt.messageId()));

            assertEquals("Flattening…", telegram.answered().get(0).text());
            assertEquals(List.of(TSLA), fake.positions().stream().map(p -> p.conid()).toList(),
                    "the EXTERNAL position is never touched");
            assertTrue(fake.openOrders().isEmpty(), fake.openOrders().toString());
            assertTrue(killSwitch.isOn());
            assertEquals("CANCELLED", load(proposalId).status);

            String result = telegram.edited().stream().filter(e -> e.messageId() == prompt.messageId())
                    .reduce((a, b) -> b).orElseThrow().text();
            assertTrue(result.startsWith("[PAPER] <b>Flatten done</b>\nKill switch ON."), result);
            assertTrue(result.contains("Proposals cancelled: #" + proposalId), result);
            assertTrue(result.contains("P901-ENTRY"), result);
            assertTrue(result.contains("✅ AAPL: sold 10 at market (P"), result);
            assertTrue(result.contains("❗ TSLA: 3 not sold, not bought by this service (EXTERNAL, never touched)"),
                    result);

            // the cancelled proposal's own message lost its buttons
            assertTrue(telegram.edited().stream().anyMatch(e -> e.text().contains("🚫 <b>CANCELLED</b>")));

            // the order executor applies the market fills: sold, so CLOSED
            orderExecutor.awaitIdle(java.time.Duration.ofSeconds(10));
            List<Proposal> exits = QuarkusTransaction.requiringNew().call(() ->
                    proposals.list("kind = ?1 order by id", ProposalKind.EXIT));
            assertEquals(1, exits.size());
            exits.forEach(p -> assertEquals("CLOSED", p.status));
            List<BrokerOrder> orders = QuarkusTransaction.requiringNew().call(() -> brokerOrders.listAll());
            assertEquals(exits.stream().map(p -> "P" + p.id + "-EXIT").toList(),
                    orders.stream().map(o -> o.orderRef).sorted().toList());
            assertEquals(1, audit("FLATTEN_DONE").size());
        }

        @Test
        void aPositionWithAnExternalSellOrderIsNotSold() {
            fake.setPosition(TSLA, bd("3"), bd("240"));
            fake.setPosition(AAPL, bd("10"), bd("100"));
            gateway("TSLA");
            gateway("AAPL");
            // a manual stop entered in TWS: not ours to cancel, and selling on top of it could leave us short
            fake.addExternalOrder(AAPL, Side.SELL, "STP", bd("10"), null, bd("90"));
            Sent prompt = prompt();
            assertTrue(prompt.text().contains("⚠️ 1 external order(s) are not touched"), prompt.text());

            bot.handle(tap(callbackData(prompt.keyboard(), 0), prompt.messageId()));

            assertEquals(List.of(AAPL), fake.positions().stream().map(p -> p.conid()).toList());
            assertEquals(1, fake.openOrders().size(), "the external stop is still there");
            String result = telegram.lastEdited().text();
            assertTrue(result.contains("✅ TSLA: sold 3 at market"), result);
            assertTrue(result.contains("❗ AAPL: 10 not sold, SELL order(s) still working: external #"), result);
        }

        @Test
        void confirmationExpiresAfter60Seconds() {
            book();
            Sent prompt = prompt();
            clock.set(clock.instant().plusSeconds(61));

            bot.handle(tap(callbackData(prompt.keyboard(), 0), prompt.messageId()));

            Answered a = telegram.answered().get(0);
            assertEquals("This confirmation is no longer valid. Send /flatten again.", a.text());
            assertTrue(a.alert());
            assertEquals(2, fake.positions().size());
            assertFalse(killSwitch.isOn());
            assertTrue(telegram.lastEdited().text().contains("not confirmed in time. Nothing was done."));
        }

        @Test
        void cancelButtonDoesNothing() {
            book();
            Sent prompt = prompt();
            bot.handle(tap(callbackData(prompt.keyboard(), 1), prompt.messageId()));
            assertEquals("Flatten cancelled.", telegram.answered().get(0).text());
            assertEquals(2, fake.positions().size());
            assertFalse(killSwitch.isOn());

            // the old token is gone
            bot.handle(tap(callbackData(prompt.keyboard(), 0), prompt.messageId()));
            assertEquals(2, fake.positions().size());
        }

        @Test
        void aNewerFlattenReplacesTheOlderOne() {
            book();
            Sent first = prompt();
            Sent second = prompt();
            assertTrue(telegram.lastEdited().text().contains("Replaced by a newer /flatten"));

            bot.handle(tap(callbackData(first.keyboard(), 0), first.messageId()));
            assertEquals(2, fake.positions().size());

            bot.handle(tap(callbackData(second.keyboard(), 0), second.messageId()));
            assertEquals(List.of(TSLA), fake.positions().stream().map(p -> p.conid()).toList(),
                    "only the EXTERNAL position is left");
        }

        @Test
        void nothingToFlatten() {
            Sent s = prompt();
            assertEquals("[PAPER] Nothing to flatten: no positions bought by this service and no working orders of ours.", s.text());
            assertNull(s.keyboard());
        }
    }

    // =====================================================================================================

    @Nested
    class Polling {

        @Test
        void offsetMovesPastEveryUpdate() {
            telegram.queueUpdates(
                    commandAs("/kill", CHAT, STRANGER, clock.instant()),
                    commandAs("/status", CHAT, OWNER, clock.instant()));
            long last = updateIds.get();

            assertEquals(2, poller.pollOnce());
            assertEquals(last + 1, poller.offset());
            assertEquals(0, poller.pollOnce());

            assertEquals(List.of(-1L, last + 1), telegram.polledOffsets());
            assertEquals(1, telegram.sent().size(), "only the owner's /status is answered");
            assertFalse(killSwitch.isOn());
        }

        @Test
        void aFailingGetUpdatesKeepsTheOffset() {
            telegram.queueUpdates(command("/status"));
            poller.pollOnce();
            Long offset = poller.offset();

            telegram.failAll();
            assertThrows(TelegramException.class, () -> poller.pollOnce());
            assertEquals(offset, poller.offset());
        }
    }

    // =====================================================================================================

    @Nested
    class Alerts {

        @Test
        void alertIsTaggedEscapedAndDeduplicated() {
            alerts.warning("IBKR <down>").join();
            alerts.warning("IBKR <down>").join();
            alerts.critical("IBKR <down>").join();

            List<Sent> sent = telegram.sent();
            assertEquals(2, sent.size(), sent.toString());
            assertEquals("[PAPER] ⚠️ IBKR &lt;down&gt;", sent.get(0).text());
            assertNull(sent.get(0).keyboard());
            assertEquals("[PAPER] 🚨 IBKR &lt;down&gt;", sent.get(1).text());

            clock.set(clock.instant().plus(AlertService.DEDUPE_WINDOW));
            alerts.warning("IBKR <down>").join();
            assertEquals(3, telegram.sent().size());
        }

        @Test
        void alertNeverThrows() {
            telegram.failAll();
            alerts.info("hello").join();
            assertTrue(telegram.sent().isEmpty());
        }

        @Test
        void reconnectRaisesAnAlert() {
            fake.simulateReconnect();
            telegram.awaitSent(s -> s.text().startsWith("[PAPER] ⚠️ IBKR connection restored"), Duration.ofSeconds(5));
        }
    }
}
