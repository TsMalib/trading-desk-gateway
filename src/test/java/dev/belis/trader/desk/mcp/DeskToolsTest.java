package dev.belis.trader.desk.mcp;

import dev.belis.trader.desk.broker.BracketOrder;
import dev.belis.trader.desk.broker.ConnectionState;
import dev.belis.trader.desk.broker.FakeBroker;
import dev.belis.trader.desk.proposal.ProposalCreated;
import dev.belis.trader.desk.proposal.ProposalService;
import dev.belis.trader.desk.store.AuditActor;
import dev.belis.trader.desk.store.AuditLog;
import dev.belis.trader.desk.store.AuditLogRepository;
import dev.belis.trader.desk.store.Instrument;
import dev.belis.trader.desk.store.InstrumentRepository;
import dev.belis.trader.desk.store.Proposal;
import dev.belis.trader.desk.store.ProposalKind;
import dev.belis.trader.desk.store.ProposalRepository;
import dev.belis.trader.desk.store.Setting;
import dev.belis.trader.desk.store.SettingRepository;
import dev.belis.trader.desk.support.CreatedEvents;
import dev.belis.trader.desk.support.FixedClock;
import dev.belis.trader.desk.support.Market;
import dev.belis.trader.desk.support.TestDb;
import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static dev.belis.trader.desk.support.Market.AAPL;
import static dev.belis.trader.desk.support.Market.bd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The MCP tools against FakeBroker and a real Postgres, called as CDI bean methods. */
@QuarkusTest
class DeskToolsTest {

    static final String THESIS = "Breakout above the 50-day high on rising volume; invalid below 95.";

    @Inject
    DeskTools tools;

    @Inject
    FakeBroker fake;

    @Inject
    ProposalRepository proposals;

    @Inject
    InstrumentRepository instruments;

    @Inject
    AuditLogRepository auditLogs;

    @Inject
    SettingRepository settings;

    @Inject
    TestDb db;

    @Inject
    CreatedEvents events;

    @BeforeEach
    void setUp() {
        db.clean();
        events.clear();
        FixedClock.install();
        Market.open(fake, FixedClock.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        db.clean();
        fake.reset();
    }

    // ------------------------------------------------------------------------------------------- helpers

    static String text(ToolResponse r) {
        return ((TextContent) r.content().get(0)).text();
    }

    ToolResponse proposeAapl() {
        return tools.proposeOrder("AAPL", "100", "95", "112", THESIS, List.of("https://example.com/a"), false,
                "grok-test");
    }

    Proposal proposal(long id) {
        return QuarkusTransaction.requiringNew().call(() -> proposals.findById(id));
    }

    long proposalCount() {
        return QuarkusTransaction.requiringNew().call(() -> proposals.count());
    }

    List<AuditLog> auditEvents(String event) {
        return QuarkusTransaction.requiringNew().call(() -> auditLogs.list("event = ?1 order by id", event));
    }

    /** 10 AAPL bought at about 100.30 through a filled bracket (stop 95, target 120). */
    void holdAaplWithBracket() {
        fake.placeBracket(new BracketOrder(900, AAPL, bd("10"), bd("100.50"), bd("120"), bd("95")));
        assertEquals(0, bd("10").compareTo(fake.positions().get(0).quantity()));
    }

    // =============================================================================================== propose_order

    @Nested
    class ProposeOrder {

        @Test
        void passingEntryIsStoredWithItsChecksAndFiresTheEvent() {
            ToolResponse r = proposeAapl();
            String t = text(r);

            assertFalse(r.isError(), t);
            assertTrue(t.startsWith("Proposal 1 created (ENTRY AAPL, paper account), status PROPOSED"), t);
            assertTrue(t.contains("Quantity: 100 shares"), t);
            assertTrue(t.contains("Risk: 500 USD"), t);
            assertTrue(t.contains("Reward/risk: 2.4"), t);
            assertTrue(t.contains("Warnings: none"), t);

            Proposal p = proposal(1);
            assertEquals("PROPOSED", p.status);
            assertEquals(ProposalKind.ENTRY, p.kind);
            assertEquals(AAPL, p.conid);
            assertEquals(0, bd("100").compareTo(p.quantity));
            assertEquals(0, bd("500.00").compareTo(p.riskEur));
            assertEquals(0, bd("2.40").compareTo(p.rewardRisk));
            assertEquals(0, bd("95").compareTo(p.stopPrice));
            assertEquals(THESIS, p.thesis);
            assertEquals("https://example.com/a", p.sources.get(0).asText());
            assertEquals("grok-test", p.proposedBy);
            assertTrue(p.riskResult.get("passed").asBoolean());
            assertEquals(18, p.riskResult.get("checks").size());
            assertEquals("KILL_SWITCH", p.riskResult.get("checks").get(0).get("name").asText());
            assertEquals(0, p.warnings.size());
            assertNotNull(p.expiresAt);

            Instrument i = QuarkusTransaction.requiringNew().call(() -> instruments.findById(AAPL));
            assertEquals("AAPL", i.symbol);
            assertEquals(Market.AAPL_ISIN, i.isin);
            assertEquals("NASDAQ", i.primaryExch);
            assertEquals("USD", i.currency);

            assertEquals(List.of(new ProposalCreated(1, ProposalKind.ENTRY, AAPL, "AAPL")), events.all());

            AuditLog created = auditEvents(ProposalService.EVENT_CREATED).get(0);
            assertEquals(AuditActor.GROK, created.actor);
            assertEquals(1L, created.proposalId);
            assertEquals("PROPOSED", created.details.get("status").asText());
            assertEquals(1, auditEvents(ProposalService.EVENT_INSTRUMENT_SAVED).size());

            // Nothing was sent to the broker.
            assertTrue(fake.openOrders().isEmpty());
        }

        @Test
        void failingEntryReturnsTheFailedChecksInPlainText() {
            ToolResponse r = tools.proposeOrder("TSLA", "250", "249", "260", THESIS, null, true, "grok-test");
            String t = text(r);

            assertTrue(r.isError());
            assertTrue(t.startsWith("Proposal 1 rejected (ENTRY TSLA). Failed checks:"), t);
            assertTrue(t.contains("- WHITELIST: TSLA (limit AAPL,MSFT)"), t);
            assertTrue(t.contains("- EARNINGS: earnings soon (limit none)"), t);
            assertTrue(t.contains("- STOP_DISTANCE: 0.4% (limit 1% to 15%)"), t);
            assertFalse(t.contains("KILL_SWITCH"), t);

            Proposal p = proposal(1);
            assertEquals("REJECTED_RISK", p.status);
            assertFalse(p.riskResult.get("passed").asBoolean());
            assertNull(p.expiresAt);
            assertTrue(events.all().isEmpty());
            assertTrue(QuarkusTransaction.requiringNew().call(() -> instruments.findById(Market.TSLA)) != null);
        }

        @Test
        void thirtyDayWarningAfterALossSale() {
            fake.setPosition(AAPL, bd("10"), bd("110"));
            fake.marketSell(AAPL, bd("10"), "P0-EXIT"); // at the bid 100.10: a loss

            ToolResponse r = proposeAapl();
            String t = text(r);

            assertFalse(r.isError(), t);
            assertTrue(t.contains("Warnings:\n- Sold at a loss of"), t);
            assertTrue(t.contains("30-day rule"), t);
            Proposal p = proposal(1);
            assertEquals(1, p.warnings.size());
            assertEquals("BUY_AFTER_LOSS_SALE", p.warnings.get(0).get("type").asText());
            assertEquals(Market.AAPL_ISIN, p.warnings.get(0).get("isin").asText());
        }

        @Test
        void killSwitchRejects() {
            QuarkusTransaction.requiringNew().run(() -> {
                Setting s = new Setting();
                s.key = "kill_switch";
                s.value = "on";
                settings.persist(s);
            });
            String t = text(proposeAapl());
            assertTrue(t.contains("- KILL_SWITCH: on (limit off)"), t);
            assertTrue(tools.getAccountSummary().killSwitch());
        }

        @Test
        void invalidInputIsRefusedWithoutAProposal() {
            ToolResponse r = tools.proposeOrder("AAPL", "abc", "95", "112", THESIS, List.of(), false, "grok-test");
            assertTrue(r.isError());
            assertEquals("Not proposed: entryLimit must be a decimal number such as \"187.25\", got 'abc'", text(r));

            r = tools.proposeOrder("AAPL", "100", "-1", "112", THESIS, List.of(), false, "grok-test");
            assertEquals("Not proposed: stop must be a positive price with at most 6 decimals, got -1", text(r));

            r = tools.proposeOrder("AAPL", "100", "95", "112", " ", List.of(), false, "grok-test");
            assertEquals("Not proposed: thesis is required", text(r));

            r = tools.proposeOrder("AAPL", "100", "95", "112", THESIS, List.of(), false, "");
            assertTrue(text(r).startsWith("Not proposed: proposedBy is required"), text(r));

            assertEquals(0, proposalCount());
            List<AuditLog> refused = auditEvents(ProposalService.EVENT_REFUSED);
            assertEquals(4, refused.size());
            assertEquals("AAPL", refused.get(0).details.get("symbol").asText());
            assertEquals("ENTRY", refused.get(0).details.get("kind").asText());
            assertTrue(refused.get(0).details.get("reason").asText().startsWith("entryLimit must be"));
        }

        @Test
        void unknownSymbolIsRefusedAndAudited() {
            ToolResponse r = tools.proposeOrder("ZZZZ", "10", "9", "13", THESIS, List.of(), false, "grok-test");
            assertTrue(r.isError());
            assertTrue(text(r).startsWith("Not proposed: IBKR could not resolve symbol ZZZZ"), text(r));
            assertEquals(0, proposalCount());
            AuditLog refused = auditEvents(ProposalService.EVENT_REFUSED).get(0);
            assertEquals("ZZZZ", refused.details.get("symbol").asText());
            assertEquals(AuditActor.GROK, refused.actor);
        }

        @Test
        void brokerDownIsRefused() {
            fake.setConnectionState(ConnectionState.DISCONNECTED);
            ToolResponse r = proposeAapl();
            assertTrue(r.isError());
            assertEquals("Not proposed: IBKR is not connected right now. Try again later.", text(r));
            assertEquals(0, proposalCount());
        }

        @Test
        void decimalsArePreservedExactly() {
            Market.quote(fake, "AAPL", "100.123456");
            ToolResponse r = tools.proposeOrder("AAPL", "100.123456", "95.000001", "112.654321", THESIS, List.of(),
                    false, "grok-test");
            assertFalse(r.isError(), text(r));
            Proposal p = proposal(1);
            assertEquals("100.123456", p.entryLimit.toPlainString());
            assertEquals("95.000001", p.stopPrice.toPlainString());
            assertEquals("112.654321", p.targetPrice.toPlainString());
        }
    }

    // =============================================================================================== propose_exit

    @Nested
    class ProposeExit {

        @Test
        void exitOfTheWholePosition() {
            holdAaplWithBracket();
            ToolResponse r = tools.proposeExit("aapl", "Thesis broken: guidance cut.", "grok-test", null);
            String t = text(r);

            assertFalse(r.isError(), t);
            assertTrue(t.startsWith("Proposal 1 created (EXIT AAPL, paper account), status PROPOSED"), t);
            assertTrue(t.contains("Quantity: 10 shares"), t);
            assertTrue(t.contains("- Bought 1003.00 USD worth on 2026-11-24"), t);

            Proposal p = proposal(1);
            assertEquals(ProposalKind.EXIT, p.kind);
            assertEquals("PROPOSED", p.status);
            assertEquals(0, bd("10").compareTo(p.quantity));
            assertEquals("Thesis broken: guidance cut.", p.thesis);
            assertEquals("EXIT_AFTER_RECENT_BUY", p.warnings.get(0).get("type").asText());
            assertEquals(List.of(new ProposalCreated(1, ProposalKind.EXIT, AAPL, "AAPL")), events.all());
            // the stop is still the only SELL working: nothing sent
            assertEquals(2, fake.openOrders().size());
        }

        @Test
        void partialExit() {
            holdAaplWithBracket();
            ToolResponse r = tools.proposeExit("AAPL", "Take some off.", "grok-test", "4");
            assertFalse(r.isError(), text(r));
            assertEquals(0, bd("4").compareTo(proposal(1).quantity));
        }

        @Test
        void moreThanThePositionIsRejected() {
            holdAaplWithBracket();
            ToolResponse r = tools.proposeExit("AAPL", "Out.", "grok-test", "11");
            assertTrue(r.isError());
            assertTrue(text(r).contains("- SELL_QUANTITY: 11 (limit <= 10 (position))"), text(r));
            assertEquals("REJECTED_RISK", proposal(1).status);
        }

        @Test
        void noPositionIsRejected() {
            ToolResponse r = tools.proposeExit("MSFT", "Out.", "grok-test", null);
            assertTrue(r.isError());
            assertTrue(text(r).contains("- POSITION: 0 (limit > 0 shares held)"), text(r));
            assertTrue(events.all().isEmpty());
        }

        @Test
        void secondExitWhileOneIsOpenIsRejected() {
            holdAaplWithBracket();
            assertFalse(tools.proposeExit("AAPL", "Out.", "grok-test", null).isError());
            ToolResponse r = tools.proposeExit("AAPL", "Out again.", "grok-test", null);
            assertTrue(r.isError());
            assertTrue(text(r).contains("- NO_OPEN_EXIT: proposal 1 is still open (limit none)"), text(r));
        }
    }

    // =============================================================================================== propose_stop_update

    @Nested
    class ProposeStopUpdate {

        @Test
        void raisingTheStopBelowTheLastPrice() {
            holdAaplWithBracket();
            ToolResponse r = tools.proposeStopUpdate("AAPL", "97.50", "Lock in a higher low.", "grok-test");
            String t = text(r);

            assertFalse(r.isError(), t);
            assertTrue(t.startsWith("Proposal 1 created (STOP_UPDATE AAPL, paper account), status PROPOSED"), t);
            Proposal p = proposal(1);
            assertEquals(ProposalKind.STOP_UPDATE, p.kind);
            assertEquals(0, bd("97.50").compareTo(p.stopPrice));
            assertEquals(0, bd("95").compareTo(p.riskResult.get("currentStop").decimalValue()));
            assertEquals(0, bd("100.20").compareTo(p.riskResult.get("lastPrice").decimalValue()));
            assertEquals(List.of(new ProposalCreated(1, ProposalKind.STOP_UPDATE, AAPL, "AAPL")), events.all());
            // the working stop is untouched
            assertEquals(0, bd("95").compareTo(fake.openOrders().stream()
                    .filter(o -> "P900-STOP".equals(o.orderRef())).findFirst().orElseThrow().auxPrice()));
        }

        @Test
        void notAboveTheCurrentStopIsRejected() {
            holdAaplWithBracket();
            for (String stop : List.of("94", "95")) {
                ToolResponse r = tools.proposeStopUpdate("AAPL", stop, "Wider.", "grok-test");
                assertTrue(r.isError(), stop);
                assertTrue(text(r).contains("- STOP_ABOVE_CURRENT: " + stop + " (limit > 95 (current stop))"), text(r));
            }
        }

        @Test
        void notBelowTheLastPriceIsRejected() {
            holdAaplWithBracket();
            for (String stop : List.of("100.20", "101")) {
                ToolResponse r = tools.proposeStopUpdate("AAPL", stop, "Tight.", "grok-test");
                assertTrue(r.isError(), stop);
                String shown = new BigDecimal(stop).stripTrailingZeros().toPlainString();
                assertTrue(text(r).contains("- STOP_BELOW_LAST: " + shown + " (limit < 100.2 (last price))"), text(r));
                assertFalse(text(r).contains("STOP_ABOVE_CURRENT"), text(r));
            }
        }

        @Test
        void noLastPriceIsRejected() {
            holdAaplWithBracket();
            fake.setQuote("AAPL", null, null, null); // no last price
            ToolResponse r = tools.proposeStopUpdate("AAPL", "97", "Raise.", "grok-test");
            assertTrue(r.isError());
            assertTrue(text(r).contains("- STOP_BELOW_LAST: 97 (limit a live last price (none))"), text(r));
        }

        @Test
        void withoutAPositionOrAStopIsRejected() {
            ToolResponse r = tools.proposeStopUpdate("MSFT", "390", "Raise.", "grok-test");
            assertTrue(r.isError());
            String t = text(r);
            assertTrue(t.contains("- POSITION: 0"), t);
            assertTrue(t.contains("- STOP_ABOVE_CURRENT: 390 (limit a current stop (none found))"), t);
        }
    }

    // =============================================================================================== read tools

    @Test
    void getProposalStatus() {
        tools.proposeOrder("AAPL", "100", "99.5", "112", THESIS, List.of(), false, "grok-test");
        DeskTools.ProposalStatusView v = tools.getProposalStatus(1);
        assertEquals("ENTRY", v.kind());
        assertEquals("REJECTED_RISK", v.status());
        assertEquals("AAPL", v.symbol());
        assertEquals(List.of("STOP_DISTANCE: 0.5% (limit 1% to 15%)"), v.failedChecks());
        assertEquals("a risk check failed; nothing was sent to the broker", v.meaning());
        assertEquals("grok-test", v.proposedBy());

        ToolCallException e = assertThrows(ToolCallException.class, () -> tools.getProposalStatus(42));
        assertEquals("No proposal with id 42", e.getMessage());
    }

    @Test
    void getAccountSummary() {
        DeskTools.AccountView a = tools.getAccountSummary();
        assertEquals("paper", a.mode());
        assertEquals(FakeBroker.ACCOUNT, a.account());
        assertEquals("USD", a.baseCurrency());
        assertEquals(0, bd("100000").compareTo(a.netLiquidation()));
        assertFalse(a.killSwitch());
    }

    @Test
    void getPositionsAndQuote() {
        holdAaplWithBracket();
        DeskTools.PositionsView p = tools.getPositions();
        assertEquals("paper", p.mode());
        assertEquals(1, p.positions().size());
        assertEquals("AAPL", p.positions().get(0).symbol());
        assertEquals(0, BigDecimal.TEN.compareTo(p.positions().get(0).quantity()));
        assertNull(p.positions().get(0).origin());

        assertEquals(0, bd("100.20").compareTo(tools.getQuote(" aapl ").last()));
        assertThrows(ToolCallException.class, () -> tools.getQuote("NOPE"));
    }

    @Test
    void readToolsReportBrokerDown() {
        fake.setConnectionState(ConnectionState.DISCONNECTED);
        ToolCallException e = assertThrows(ToolCallException.class, () -> tools.getPositions());
        assertEquals("The broker is not connected right now. Try again later.", e.getMessage());
    }
}
