package dev.belis.trader.desk.telegram;

import dev.belis.trader.desk.broker.AccountSummary;
import dev.belis.trader.desk.broker.Broker;
import dev.belis.trader.desk.broker.BrokerException;
import dev.belis.trader.desk.broker.BrokerOpenOrder;
import dev.belis.trader.desk.broker.BrokerPosition;
import dev.belis.trader.desk.config.DeskClock;
import dev.belis.trader.desk.config.DeskConfig;
import dev.belis.trader.desk.proposal.FlattenService;
import dev.belis.trader.desk.proposal.KillSwitch;
import dev.belis.trader.desk.proposal.ProposalService;
import dev.belis.trader.desk.proposal.RiskContextBuilder;
import dev.belis.trader.desk.store.AuditActor;
import dev.belis.trader.desk.store.PositionMeta;
import dev.belis.trader.desk.store.PositionOrigin;
import dev.belis.trader.desk.store.Proposal;
import dev.belis.trader.desk.store.Setting;
import dev.belis.trader.desk.store.SettingRepository;
import dev.belis.trader.desk.store.Side;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static dev.belis.trader.desk.telegram.ProposalMessages.esc;
import static dev.belis.trader.desk.telegram.ProposalMessages.money;
import static dev.belis.trader.desk.telegram.ProposalMessages.price;
import static dev.belis.trader.desk.telegram.ProposalMessages.qty;
import static dev.belis.trader.desk.telegram.ProposalMessages.signedPct;

/** Replies to the owner's commands. Every reply starts with the mode tag. Broker trouble is shown, not thrown. */
@ApplicationScoped
public class Commands {

    private static final DateTimeFormatter NOW = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ROOT);

    private final DeskConfig config;
    private final DeskClock clock;
    private final Broker broker;
    private final ProposalService proposals;
    private final KillSwitch killSwitch;
    private final FlattenService flattener;
    private final SettingRepository settings;
    private final ProposalMessages messages;

    public Commands(DeskConfig config, DeskClock clock, Broker broker, ProposalService proposals,
                    KillSwitch killSwitch, FlattenService flattener, SettingRepository settings) {
        this.config = config;
        this.clock = clock;
        this.broker = broker;
        this.proposals = proposals;
        this.killSwitch = killSwitch;
        this.flattener = flattener;
        this.settings = settings;
        this.messages = new ProposalMessages(config.mode());
    }

    public String help() {
        return messages.tag() + " <b>Commands</b>\n"
                + "/status – connection, kill switch, equity, today's P&amp;L\n"
                + "/positions – positions with their stops and targets\n"
                + "/pending – proposals waiting or in flight\n"
                + "/kill – block all new entries\n"
                + "/resume – allow new entries again\n"
                + "/flatten – cancel our orders and sell every position (asks to confirm)";
    }

    public String status() {
        List<String> rows = new ArrayList<>();
        rows.add(row("Mode", config.mode().name()));
        rows.add(row("IBKR", broker.connectionState().name()));
        rows.add(row("Kill", killSwitch.isOn() ? "ON (new entries blocked)" : "off"));
        try {
            AccountSummary a = broker.accountSummary();
            String ccy = " " + a.baseCurrency();
            StringBuilder equity = new StringBuilder(money(a.netLiquidation()) + ccy);
            startOfDayEquity().ifPresent(sod -> {
                BigDecimal pnl = a.netLiquidation().subtract(sod);
                equity.append(" · today ").append(pnl.signum() > 0 ? "+" : "").append(money(pnl))
                        .append(" (").append(signedPct(ProposalMessages.share(pnl, sod))).append(')');
            });
            rows.add(row("Account", a.account()));
            rows.add(row("Equity", equity.toString()));
            rows.add(row("Cash", money(a.cash()) + ccy));
            rows.add(row("Positions", String.valueOf(broker.positions().size())));
            rows.add(row("Orders", broker.openOrders().size() + " working"));
        } catch (BrokerException e) {
            rows.add(row("Account", "IBKR unavailable: " + e.getMessage()));
        }
        long waiting = proposals.inFlight().stream().filter(p -> "PROPOSED".equals(p.status)).count();
        rows.add(row("Proposals", waiting + " waiting for you"));
        rows.add(row("Time", NOW.format(clock.marketNow()) + " ET"));
        return messages.tag() + " <b>Status</b>\n<pre>" + esc(String.join("\n", rows)) + "</pre>";
    }

    public String positions() {
        List<BrokerPosition> positions;
        List<BrokerOpenOrder> orders;
        try {
            positions = broker.positions();
            orders = broker.openOrders();
        } catch (BrokerException e) {
            return messages.tag() + " IBKR unavailable: " + esc(e.getMessage());
        }
        if (positions.isEmpty()) {
            return messages.tag() + " No positions.";
        }
        List<String> rows = new ArrayList<>();
        for (BrokerPosition p : positions) {
            Optional<BigDecimal> stop = orders.stream()
                    .filter(o -> o.conid() == p.conid() && o.action() == Side.SELL && o.auxPrice() != null
                            && o.orderType() != null && o.orderType().toUpperCase(Locale.ROOT).startsWith("STP"))
                    .map(BrokerOpenOrder::auxPrice).max(BigDecimal::compareTo);
            Optional<BigDecimal> target = orders.stream()
                    .filter(o -> o.conid() == p.conid() && o.action() == Side.SELL && o.limitPrice() != null
                            && "LMT".equalsIgnoreCase(o.orderType()))
                    .map(BrokerOpenOrder::limitPrice).min(BigDecimal::compareTo);
            Optional<PositionMeta> meta = proposals.positionMeta(p.conid());
            boolean external = meta.map(m -> m.origin == PositionOrigin.EXTERNAL).orElse(true);
            rows.add(p.symbol() + " " + qty(p.quantity()) + " @ " + price(p.avgCost()) + " " + p.currency()
                    + "\n  stop " + stop.map(ProposalMessages::price).orElse("NONE ⚠️")
                    + " · target " + target.map(ProposalMessages::price).orElse("none")
                    + (external ? " · external" : ""));
        }
        return messages.tag() + " <b>Positions</b> (" + positions.size() + ")\n<pre>"
                + esc(String.join("\n", rows)) + "</pre>";
    }

    public String pending() {
        List<Proposal> open = proposals.inFlight();
        if (open.isEmpty()) {
            return messages.tag() + " No open proposals.";
        }
        StringBuilder b = new StringBuilder(messages.tag()).append(" <b>Open proposals</b> (").append(open.size())
                .append(')');
        for (Proposal p : open) {
            String symbol = proposals.instrument(p.conid).map(i -> i.symbol).orElse("conid " + p.conid);
            b.append("\n#").append(p.id).append(' ').append(switch (p.kind) {
                case ENTRY -> "BUY " + esc(symbol) + " " + qty(p.quantity) + " @ " + price(p.entryLimit);
                case EXIT -> "SELL " + esc(symbol) + " " + qty(p.quantity) + " MKT";
                case STOP_UPDATE -> "STOP " + esc(symbol) + " → " + price(p.stopPrice);
            }).append(" · ").append(p.status);
            if ("PROPOSED".equals(p.status) && p.expiresAt != null) {
                b.append(" · expires ").append(ProposalMessages.time(p.expiresAt, clock.instant()));
            }
        }
        return b.toString();
    }

    public String kill() {
        boolean was = killSwitch.set(true, AuditActor.USER, "telegram /kill");
        return messages.tag() + " 🛑 Kill switch <b>ON</b>" + (was ? " (it already was)" : "") + ".\n"
                + "New entries are refused at proposal and at approval. Exits and stop moves still work. "
                + "Working orders and positions are untouched: /flatten closes everything.";
    }

    public String resume() {
        boolean was = killSwitch.set(false, AuditActor.USER, "telegram /resume");
        return messages.tag() + " ▶️ Kill switch <b>off</b>" + (was ? "" : " (it already was)") + ".\n"
                + "New entries are allowed again; every risk check still applies.";
    }

    /** @param canConfirm false when there is nothing to flatten or IBKR cannot be asked */
    public record FlattenPrompt(String text, boolean canConfirm) {
    }

    public FlattenPrompt flattenPrompt() {
        FlattenService.Preview pv;
        try {
            pv = flattener.preview();
        } catch (BrokerException e) {
            return new FlattenPrompt(messages.tag() + " Cannot flatten: IBKR unavailable: " + esc(e.getMessage()),
                    false);
        }
        if (pv.positions().isEmpty() && pv.ourOrders().isEmpty()) {
            return new FlattenPrompt(messages.tag() + " Nothing to flatten: no positions and no working orders "
                    + "of ours.", false);
        }
        StringBuilder b = new StringBuilder(messages.tag()).append(" 🔥 <b>Flatten?</b>\n")
                .append("This turns the kill switch on, cancels open proposals and ")
                .append(pv.ourOrders().size()).append(" working order(s) of ours, then sells at market:");
        for (BrokerPosition p : pv.positions()) {
            b.append("\n• ").append(esc(p.symbol())).append(' ').append(qty(p.quantity())).append(" sh");
        }
        if (pv.positions().isEmpty()) {
            b.append("\n• (no positions)");
        }
        if (!pv.externalOrders().isEmpty()) {
            b.append("\n⚠️ ").append(pv.externalOrders().size()).append(" external order(s) are not touched; ")
                    .append("a position with a working external SELL order is not sold.");
        }
        b.append("\n\nConfirm within ").append(TelegramBot.FLATTEN_CONFIRM_WINDOW.toSeconds()).append(" seconds.");
        return new FlattenPrompt(b.toString(), true);
    }

    public String flattenResult(FlattenService.Result r) {
        StringBuilder b = new StringBuilder(messages.tag()).append(" <b>Flatten done</b>");
        if (r.error() != null) {
            b.append(" (stopped early: ").append(esc(r.error())).append(')');
        }
        b.append("\nKill switch ON.");
        b.append("\nProposals cancelled: ").append(r.cancelledProposals().isEmpty() ? "none"
                : esc(String.join(", ", r.cancelledProposals())));
        b.append("\nOrders cancelled: ").append(r.cancelledOrders().isEmpty() ? "none"
                : esc(String.join(", ", r.cancelledOrders())));
        r.sold().forEach(s -> b.append("\n✅ ").append(esc(s)));
        r.notSold().forEach(s -> b.append("\n❗ ").append(esc(s)));
        if (r.sold().isEmpty() && r.notSold().isEmpty()) {
            b.append("\nNo positions to sell.");
        }
        return b.toString();
    }

    private Optional<BigDecimal> startOfDayEquity() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Setting date = settings.findById(RiskContextBuilder.START_OF_DAY_DATE);
            Setting value = settings.findById(RiskContextBuilder.START_OF_DAY_EQUITY);
            if (date == null || value == null || !clock.marketToday().toString().equals(date.value)) {
                return Optional.<BigDecimal>empty();
            }
            try {
                return Optional.of(new BigDecimal(value.value.trim()));
            } catch (NumberFormatException e) {
                return Optional.<BigDecimal>empty();
            }
        });
    }

    private static String row(String label, String value) {
        return String.format(Locale.ROOT, "%-9s %s", label, value);
    }
}
