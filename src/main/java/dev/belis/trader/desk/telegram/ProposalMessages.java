package dev.belis.trader.desk.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import dev.belis.trader.desk.config.DeskConfig;
import dev.belis.trader.desk.proposal.ProposalService;
import dev.belis.trader.desk.proposal.ProposalStatus;
import dev.belis.trader.desk.store.Instrument;
import dev.belis.trader.desk.store.PositionMeta;
import dev.belis.trader.desk.store.Proposal;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.net.URI;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Telegram texts (HTML parse mode) for proposals. Pure: everything comes from the stored proposal, so the same
 * text can be rebuilt when the message is edited after a decision. Every text starts with the mode tag.
 *
 * <pre>
 * [PAPER] #42 BUY AAPL · Apple Inc
 * Entry   100.00 LMT
 * Stop    95.00 (-5.00%)
 * Target  112.00 (+12.00%)
 * Size    99 sh = 9,900.00 USD · 9.90% of equity
 * Risk    495.00 EUR · 0.50% of equity
 * R/R     2.40
 * Last    100.20 · drift -0.20%
 * Thesis
 * Breakout above the 50-day high on volume.
 * Sources example.com, reuters.com
 * ⚠️ (one line per warning)
 * Expires 11:30 ET · by grok-test
 * </pre>
 */
public final class ProposalMessages {

    public static final int MAX_LENGTH = 4096;
    static final int MAX_THESIS = 1500;
    private static final int MAX_SOURCE_TEXT = 40;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter DAY_HHMM = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ROOT);

    private final DeskConfig.Mode mode;

    public ProposalMessages(DeskConfig.Mode mode) {
        this.mode = mode;
    }

    /** "[PAPER]" or "[LIVE]". */
    public String tag() {
        return "[" + mode.name() + "]";
    }

    public static Tg.InlineKeyboardMarkup keyboard(long proposalId) {
        return Tg.InlineKeyboardMarkup.row(
                new Tg.InlineKeyboardButton("✅ Approve", "a:" + proposalId),
                new Tg.InlineKeyboardButton("❌ Reject", "r:" + proposalId));
    }

    // =====================================================================================================
    // Proposal texts
    // =====================================================================================================

    /**
     * The proposal as the owner decides on it.
     *
     * @param position for EXIT and STOP_UPDATE: what the desk knows about the position (entry, stop, target)
     */
    public String proposal(Proposal p, Instrument i, Optional<PositionMeta> position) {
        int thesisLimit = MAX_THESIS;
        String text = render(p, i, position, thesisLimit);
        while (text.length() > MAX_LENGTH && thesisLimit > 100) {
            thesisLimit = Math.max(100, thesisLimit - (text.length() - MAX_LENGTH) - 50);
            text = render(p, i, position, thesisLimit);
        }
        return text;
    }

    /** The proposal text followed by its outcome (approved, rejected with the reasons, expired, ...). */
    public String decided(Proposal p, Instrument i, Optional<PositionMeta> position) {
        return proposal(p, i, position) + "\n\n" + outcome(p);
    }

    /** The proposal text with a note that the approval re-check could not run; the buttons stay. */
    public String unavailable(Proposal p, Instrument i, Optional<PositionMeta> position, Instant at, String why) {
        return proposal(p, i, position) + "\n\n⚠️ IBKR could not be asked at " + time(at, at) + ": " + esc(why)
                + "\nStill pending. Tap Approve again.";
    }

    private String render(Proposal p, Instrument i, Optional<PositionMeta> position, int thesisLimit) {
        String symbol = i == null ? "conid " + p.conid : i.symbol;
        StringBuilder b = new StringBuilder();
        b.append(tag()).append(" <b>#").append(p.id).append(' ').append(switch (p.kind) {
            case ENTRY -> "BUY";
            case EXIT -> "SELL";
            case STOP_UPDATE -> "MOVE STOP";
        }).append(' ').append(esc(symbol)).append("</b>");
        if (i != null && i.name != null && !i.name.isBlank()) {
            b.append(" · ").append(esc(i.name));
        }
        b.append('\n');

        switch (p.kind) {
            case ENTRY -> entry(b, p, i, thesisLimit);
            case EXIT -> exit(b, p, position, thesisLimit);
            case STOP_UPDATE -> stopUpdate(b, p, thesisLimit);
        }

        for (String w : warnings(p)) {
            b.append("\n⚠️ ").append(esc(w));
        }
        b.append("\n\nExpires ").append(p.expiresAt == null ? "n/a" : time(p.expiresAt, p.createdAt))
                .append(" · by ").append(esc(p.proposedBy));
        return b.toString();
    }

    private void entry(StringBuilder b, Proposal p, Instrument i, int thesisLimit) {
        JsonNode rr = p.riskResult;
        String ccy = i == null || i.currency == null ? "" : i.currency.trim();
        String base = text(rr, "baseCurrency");
        BigDecimal equity = decimal(rr, "equity");
        BigDecimal fx = decimal(rr, "fxToBase");
        BigDecimal last = decimal(rr, "lastPrice");
        BigDecimal value = p.quantity == null || p.entryLimit == null ? null : p.quantity.multiply(p.entryLimit);

        List<String> rows = new ArrayList<>();
        rows.add(row("Entry", price(p.entryLimit) + " LMT"));
        rows.add(row("Stop", price(p.stopPrice) + " (" + signedPct(change(p.entryLimit, p.stopPrice)) + ")"));
        rows.add(row("Target", price(p.targetPrice) + " (" + signedPct(change(p.entryLimit, p.targetPrice)) + ")"));
        rows.add(row("Size", qty(p.quantity) + " sh = " + money(value) + " " + ccy + " · "
                + pct(share(value == null || fx == null ? null : value.multiply(fx), equity)) + " of equity"));
        rows.add(row("Risk", money(p.riskEur) + " " + base + " · " + pct(share(p.riskEur, equity)) + " of equity"));
        rows.add(row("R/R", p.rewardRisk == null ? "n/a" : p.rewardRisk.setScale(2, RoundingMode.DOWN).toPlainString()));
        rows.add(row("Last", price(last) + " · drift " + signedPct(change(last, p.entryLimit))));
        pre(b, rows);

        b.append("\n<b>Thesis</b>\n").append(esc(truncate(p.thesis, thesisLimit)));
        List<String> domains = sourceDomains(p.sources);
        b.append("\n<b>Sources</b> ").append(domains.isEmpty() ? "none" : esc(String.join(", ", domains)));
    }

    private void exit(StringBuilder b, Proposal p, Optional<PositionMeta> position, int thesisLimit) {
        BigDecimal held = decimal(p.riskResult, "positionQuantity");
        List<String> rows = new ArrayList<>();
        rows.add(row("Sell", qty(p.quantity) + " sh MKT"));
        rows.add(row("Held", qty(held) + " sh" + (held != null && p.quantity != null
                && p.quantity.compareTo(held) == 0 ? " (all)" : "")));
        position.ifPresent(m -> {
            if (m.entryPrice != null) {
                rows.add(row("Entry", price(m.entryPrice)));
            }
            if (m.currentStop != null) {
                rows.add(row("Stop", price(m.currentStop)));
            }
            if (m.targetPrice != null) {
                rows.add(row("Target", price(m.targetPrice)));
            }
        });
        pre(b, rows);
        b.append("\n<b>Reason</b>\n").append(esc(truncate(p.thesis, thesisLimit)));
    }

    private void stopUpdate(StringBuilder b, Proposal p, int thesisLimit) {
        BigDecimal current = decimal(p.riskResult, "currentStop");
        BigDecimal last = decimal(p.riskResult, "lastPrice");
        List<String> rows = new ArrayList<>();
        rows.add(row("Stop", price(current) + " → " + price(p.stopPrice)
                + " (" + signedPct(change(current, p.stopPrice)) + ")"));
        BigDecimal stopVsLast = change(last, p.stopPrice);
        rows.add(row("Last", price(last) + " · new stop " + pct(stopVsLast == null ? null : stopVsLast.negate())
                + " below"));
        rows.add(row("Shares", qty(p.quantity)));
        pre(b, rows);
        b.append("\n<b>Reason</b>\n").append(esc(truncate(p.thesis, thesisLimit)));
    }

    /** One line saying what happened, from the stored status (and the approval re-check, if any). */
    String outcome(Proposal p) {
        ProposalStatus s = ProposalService.parse(p.status);
        String at = p.decidedAt == null ? "" : " " + time(p.decidedAt, p.decidedAt);
        JsonNode approval = p.riskResult == null ? null : p.riskResult.get("approval");
        if (s == null) {
            return "Status " + esc(p.status);
        }
        return switch (s) {
            case PROPOSED -> "Waiting for your decision.";
            case APPROVED, SUBMITTED, FILLED, CLOSED -> {
                StringBuilder b = new StringBuilder("✅ <b>APPROVED</b>").append(at);
                if (approval != null) {
                    b.append(" · all checks passed again");
                    BigDecimal last = decimal(approval, "lastPrice");
                    if (last != null) {
                        b.append(" (last ").append(price(last)).append(')');
                    }
                }
                BigDecimal proposedQty = decimal(p.riskResult, "quantity");
                if (proposedQty != null && p.quantity != null && p.quantity.compareTo(proposedQty) < 0) {
                    b.append("\nQuantity reduced to ").append(qty(p.quantity)).append(" sh (was ")
                            .append(qty(proposedQty)).append("), risk ").append(money(p.riskEur));
                    String base = text(p.riskResult, "baseCurrency");
                    if (!base.isEmpty()) {
                        b.append(' ').append(esc(base));
                    }
                }
                yield b.toString();
            }
            case REJECTED_RISK -> {
                StringBuilder b = new StringBuilder("⛔ <b>REJECTED</b>").append(at);
                if (approval != null) {
                    b.append(" · the re-check with a fresh quote failed:");
                    approval.path("checks").forEach(c -> {
                        if (!c.path("passed").asBoolean(true)) {
                            b.append("\n• ").append(esc(c.path("name").asText())).append(": ")
                                    .append(esc(c.path("actual").asText())).append(" (limit ")
                                    .append(esc(c.path("limit").asText())).append(')');
                        }
                    });
                }
                yield b.toString();
            }
            case REJECTED_USER -> "❌ <b>REJECTED</b> by you" + at;
            case EXPIRED -> "⌛ <b>EXPIRED</b>" + at + " · not decided in time";
            case CANCELLED -> "🚫 <b>CANCELLED</b>" + at;
            case FAILED -> "❗ <b>FAILED</b>" + at;
        };
    }

    // =====================================================================================================
    // Formatting helpers (also used by the command replies)
    // =====================================================================================================

    /** Escapes text for Telegram's HTML parse mode. */
    public static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** "HH:mm ET", with the weekday and date when {@code t} is on another New York day than {@code ref}. */
    public static String time(Instant t, Instant ref) {
        var nyT = t.atZone(DeskConfig.MARKET_ZONE);
        LocalDate refDay = (ref == null ? t : ref).atZone(DeskConfig.MARKET_ZONE).toLocalDate();
        return (nyT.toLocalDate().equals(refDay) ? HHMM.format(nyT) : DAY_HHMM.format(nyT)) + " ET";
    }

    /** At least 2 decimals, more only when the price has them (e.g. 100.00, 12.345). */
    public static String price(BigDecimal v) {
        if (v == null) {
            return "n/a";
        }
        BigDecimal s = v.stripTrailingZeros();
        return (s.scale() < 2 ? s.setScale(2, RoundingMode.UNNECESSARY) : s).toPlainString();
    }

    /** Grouped, 2 decimals: 12,345.60. */
    public static String money(BigDecimal v) {
        if (v == null) {
            return "n/a";
        }
        DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        f.setRoundingMode(RoundingMode.HALF_UP);
        return f.format(v);
    }

    /** Grouped, up to 4 decimals: 1,000 or 2.5. */
    public static String qty(BigDecimal v) {
        if (v == null) {
            return "n/a";
        }
        DecimalFormat f = new DecimalFormat("#,##0.####", DecimalFormatSymbols.getInstance(Locale.US));
        f.setRoundingMode(RoundingMode.DOWN);
        return f.format(v);
    }

    /** 2 decimals and a percent sign: 9.90%. */
    public static String pct(BigDecimal v) {
        return v == null ? "n/a" : v.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /** Like {@link #pct} with an explicit sign: +12.00%, -5.00%. */
    public static String signedPct(BigDecimal v) {
        if (v == null) {
            return "n/a";
        }
        BigDecimal r = v.setScale(2, RoundingMode.HALF_UP);
        return (r.signum() > 0 ? "+" : "") + r.toPlainString() + "%";
    }

    /** (to - from) / from in percent; null when unknown. */
    static BigDecimal change(BigDecimal from, BigDecimal to) {
        if (from == null || to == null || from.signum() == 0) {
            return null;
        }
        return to.subtract(from).multiply(HUNDRED).divide(from, MC);
    }

    /** part / whole in percent; null when unknown. */
    static BigDecimal share(BigDecimal part, BigDecimal whole) {
        if (part == null || whole == null || whole.signum() <= 0) {
            return null;
        }
        return part.multiply(HUNDRED).divide(whole, MC);
    }

    /** Host names of URL sources without "www."; other citations shortened. Duplicates removed. */
    static List<String> sourceDomains(JsonNode sources) {
        Set<String> out = new LinkedHashSet<>();
        if (sources == null) {
            return List.of();
        }
        sources.forEach(n -> {
            String s = n.asText("").strip();
            if (s.isEmpty()) {
                return;
            }
            String host = host(s);
            if (host == null && !s.contains(" ") && s.contains(".")) {
                host = host("https://" + s);
            }
            out.add(host != null ? host : truncate(s, MAX_SOURCE_TEXT));
        });
        return List.copyOf(out);
    }

    private static String host(String s) {
        try {
            String h = URI.create(s).getHost();
            if (h == null || h.isBlank()) {
                return null;
            }
            h = h.toLowerCase(Locale.ROOT);
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static List<String> warnings(Proposal p) {
        List<String> out = new ArrayList<>();
        if (p.warnings != null) {
            p.warnings.forEach(w -> out.add(w.isTextual() ? w.asText() : w.path("message").asText(w.toString())));
        }
        return out;
    }

    private static void pre(StringBuilder b, List<String> rows) {
        b.append("<pre>").append(esc(String.join("\n", rows))).append("</pre>");
    }

    private static String row(String label, String value) {
        return String.format(Locale.ROOT, "%-7s %s", label, value);
    }

    static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)).stripTrailing() + "…";
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        try {
            return v.isNumber() ? v.decimalValue() : new BigDecimal(v.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }
}
