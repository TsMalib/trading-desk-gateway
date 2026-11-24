package dev.belis.trader.desk.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import dev.belis.trader.desk.config.DeskConfig;
import dev.belis.trader.desk.store.AuditWriter;
import dev.belis.trader.desk.store.Instrument;
import dev.belis.trader.desk.store.PositionMeta;
import dev.belis.trader.desk.store.Proposal;
import dev.belis.trader.desk.store.ProposalKind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProposalMessagesTest {

    static final Instant CREATED = Instant.parse("2026-11-24T16:00:00Z"); // 11:00 New York
    static final ProposalMessages PAPER = new ProposalMessages(DeskConfig.Mode.PAPER);

    static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    static JsonNode json(Object o) {
        return AuditWriter.toJson(o);
    }

    static Instrument aapl() {
        Instrument i = new Instrument();
        i.conid = 265598L;
        i.symbol = "AAPL";
        i.name = "Apple Inc";
        i.currency = "USD";
        return i;
    }

    static Proposal proposal(long id, ProposalKind kind) {
        Proposal p = new Proposal();
        p.id = id;
        p.conid = 265598L;
        p.kind = kind;
        p.status = "PROPOSED";
        p.proposedBy = "grok-test";
        p.createdAt = CREATED;
        p.expiresAt = CREATED.plusSeconds(30 * 60);
        return p;
    }

    static Proposal entry() {
        Proposal p = proposal(42, ProposalKind.ENTRY);
        p.entryLimit = bd("100");
        p.stopPrice = bd("95");
        p.targetPrice = bd("112");
        p.quantity = bd("100");
        p.riskEur = bd("450.00");
        p.rewardRisk = bd("2.40");
        p.thesis = "Breakout above the 50-day high on volume & rising <RS>.";
        p.sources = json(List.of("https://www.example.com/a", "https://reuters.com/x?y=1", "http://example.com/b",
                "SEC 10-Q"));
        Map<String, Object> rr = new LinkedHashMap<>();
        rr.put("quantity", 100);
        rr.put("riskBase", bd("450.00"));
        rr.put("baseCurrency", "EUR");
        rr.put("equity", bd("100000.00"));
        rr.put("fxToBase", bd("0.9"));
        rr.put("lastPrice", bd("100.20"));
        p.riskResult = json(rr);
        p.warnings = json(List.of(Map.of("type", "BUY_AFTER_LOSS_SALE",
                "message", "30-day rule: AAPL was sold at a loss on 2026-11-10.")));
        return p;
    }

    @Test
    void entryLayout() {
        assertEquals("""
                [PAPER] <b>#42 BUY AAPL</b> · Apple Inc
                <pre>Entry   100.00 LMT
                Stop    95.00 (-5.00%)
                Target  112.00 (+12.00%)
                Size    100 sh = 10,000.00 USD · 9.00% of equity
                Risk    450.00 EUR · 0.45% of equity
                R/R     2.40
                Last    100.20 · drift -0.20%</pre>
                <b>Thesis</b>
                Breakout above the 50-day high on volume &amp; rising &lt;RS&gt;.
                <b>Sources</b> example.com, reuters.com, SEC 10-Q
                ⚠️ 30-day rule: AAPL was sold at a loss on 2026-11-10.

                Expires 11:30 ET · by grok-test""", PAPER.proposal(entry(), aapl(), Optional.empty()));
    }

    @Test
    void liveModeTag() {
        String text = new ProposalMessages(DeskConfig.Mode.LIVE).proposal(entry(), aapl(), Optional.empty());
        assertTrue(text.startsWith("[LIVE] <b>#42 BUY AAPL</b>"), text);
    }

    @Test
    void everyWarningGetsItsOwnLine() {
        Proposal p = entry();
        p.warnings = json(List.of(Map.of("message", "first"), Map.of("message", "second")));
        String text = PAPER.proposal(p, aapl(), Optional.empty());
        assertTrue(text.contains("\n⚠️ first\n⚠️ second\n"), text);
    }

    @Test
    void unknownValuesShowAsNa() {
        Proposal p = entry();
        p.riskResult = json(Map.of());
        p.sources = json(List.of());
        String text = PAPER.proposal(p, aapl(), Optional.empty());
        assertTrue(text.contains("Size    100 sh = 10,000.00 USD · n/a of equity"), text);
        assertTrue(text.contains("Last    n/a · drift n/a"), text);
        assertTrue(text.contains("<b>Sources</b> none"), text);
    }

    @Test
    void longThesisIsCutToFitTelegram() {
        Proposal p = entry();
        p.thesis = "x".repeat(4000);
        String text = PAPER.proposal(p, aapl(), Optional.empty());
        assertTrue(text.length() <= ProposalMessages.MAX_LENGTH);
        assertTrue(text.contains("x".repeat(ProposalMessages.MAX_THESIS - 1) + "…"), "thesis cut with an ellipsis");
    }

    @Test
    void exitLayout() {
        Proposal p = proposal(43, ProposalKind.EXIT);
        p.quantity = bd("10");
        p.thesis = "Thesis broken: guidance cut.";
        p.riskResult = json(Map.of("positionQuantity", 10));
        PositionMeta m = new PositionMeta();
        m.entryPrice = bd("100");
        m.currentStop = bd("95");
        m.targetPrice = bd("112.5");
        assertEquals("""
                [PAPER] <b>#43 SELL AAPL</b> · Apple Inc
                <pre>Sell    10 sh MKT
                Held    10 sh (all)
                Entry   100.00
                Stop    95.00
                Target  112.50</pre>
                <b>Reason</b>
                Thesis broken: guidance cut.

                Expires 11:30 ET · by grok-test""", PAPER.proposal(p, aapl(), Optional.of(m)));
    }

    @Test
    void stopUpdateLayout() {
        Proposal p = proposal(44, ProposalKind.STOP_UPDATE);
        p.stopPrice = bd("99");
        p.quantity = bd("10");
        p.thesis = "Lock in break-even.";
        p.riskResult = json(Map.of("currentStop", bd("95"), "lastPrice", bd("101.20"), "positionQuantity", 10));
        assertEquals("""
                [PAPER] <b>#44 MOVE STOP AAPL</b> · Apple Inc
                <pre>Stop    95.00 → 99.00 (+4.21%)
                Last    101.20 · new stop 2.17% below
                Shares  10</pre>
                <b>Reason</b>
                Lock in break-even.

                Expires 11:30 ET · by grok-test""", PAPER.proposal(p, aapl(), Optional.empty()));
    }

    @Test
    void rejectedAtApprovalListsTheFailedChecks() {
        Proposal p = entry();
        p.status = "REJECTED_RISK";
        p.decidedAt = CREATED.plusSeconds(5 * 60);
        Map<String, Object> rr = new LinkedHashMap<>();
        rr.put("quantity", 100);
        rr.put("approval", Map.of("passed", false, "checks", List.of(
                Map.of("name", "KILL_SWITCH", "passed", true, "actual", "off", "limit", "off"),
                Map.of("name", "DRIFT", "passed", false, "actual", "1.96%", "limit", "<= 1.0%"))));
        p.riskResult = json(rr);
        assertEquals("⛔ <b>REJECTED</b> 11:05 ET · the re-check with a fresh quote failed:\n"
                + "• DRIFT: 1.96% (limit &lt;= 1.0%)", PAPER.outcome(p));
    }

    @Test
    void approvedShowsAReducedQuantity() {
        Proposal p = entry();
        p.status = "APPROVED";
        p.decidedAt = CREATED.plusSeconds(60);
        p.quantity = bd("99");
        p.riskEur = bd("495.00");
        p.riskResult = json(Map.of("quantity", 100,
                "approval", Map.of("passed", true, "lastPrice", bd("100.25"), "checks", List.of())));
        assertEquals("✅ <b>APPROVED</b> 11:01 ET · all checks passed again (last 100.25)\n"
                + "Quantity reduced to 99 sh (was 100), risk 495.00", PAPER.outcome(p));
    }

    @Test
    void otherOutcomes() {
        Proposal p = entry();
        p.decidedAt = CREATED.plusSeconds(30 * 60);
        p.status = "REJECTED_USER";
        assertEquals("❌ <b>REJECTED</b> by you 11:30 ET", PAPER.outcome(p));
        p.status = "EXPIRED";
        assertEquals("⌛ <b>EXPIRED</b> 11:30 ET · not decided in time", PAPER.outcome(p));
        p.status = "CANCELLED";
        assertEquals("🚫 <b>CANCELLED</b> 11:30 ET", PAPER.outcome(p));
    }

    @Test
    void decidedTextIsTheProposalPlusTheOutcome() {
        Proposal p = entry();
        p.status = "REJECTED_USER";
        p.decidedAt = CREATED.plusSeconds(60);
        String text = PAPER.decided(p, aapl(), Optional.empty());
        assertEquals(PAPER.proposal(p, aapl(), Optional.empty()) + "\n\n❌ <b>REJECTED</b> by you 11:01 ET", text);
    }

    @Test
    void keyboardCallbackData() {
        Tg.InlineKeyboardMarkup k = ProposalMessages.keyboard(42);
        assertEquals(1, k.inlineKeyboard().size());
        assertEquals("a:42", k.inlineKeyboard().get(0).get(0).callbackData());
        assertEquals("r:42", k.inlineKeyboard().get(0).get(1).callbackData());
    }

    @Test
    void sourceDomains() {
        assertEquals(List.of("example.com", "sec.gov", "a long citation without any link in it…"),
                ProposalMessages.sourceDomains(json(List.of("https://WWW.Example.com/x", "example.com/y",
                        "sec.gov/edgar", "a long citation without any link in it at all", " "))));
    }

    @Test
    void formatting() {
        assertEquals("100.00", ProposalMessages.price(bd("100")));
        assertEquals("12.345", ProposalMessages.price(bd("12.3450")));
        assertEquals("12,345.60", ProposalMessages.money(bd("12345.6")));
        assertEquals("1,000", ProposalMessages.qty(bd("1000.0000")));
        assertEquals("+12.00%", ProposalMessages.signedPct(bd("12")));
        assertEquals("a &lt;b&gt; &amp; c", ProposalMessages.esc("a <b> & c"));
        assertEquals("Wed 25 Nov 09:50 ET", ProposalMessages.time(Instant.parse("2026-11-25T14:50:00Z"), CREATED));
    }
}
