package dev.belis.trader.desk.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.belis.trader.desk.broker.AccountSummary;
import dev.belis.trader.desk.broker.Broker;
import dev.belis.trader.desk.broker.BrokerException;
import dev.belis.trader.desk.broker.BrokerTimeoutException;
import dev.belis.trader.desk.broker.BrokerUnavailableException;
import dev.belis.trader.desk.broker.Quote;
import dev.belis.trader.desk.config.DeskConfig;
import dev.belis.trader.desk.proposal.ProposalCheck;
import dev.belis.trader.desk.proposal.ProposalOutcome;
import dev.belis.trader.desk.proposal.ProposalRefusedException;
import dev.belis.trader.desk.proposal.ProposalRequests;
import dev.belis.trader.desk.proposal.ProposalService;
import dev.belis.trader.desk.proposal.RiskContextBuilder;
import dev.belis.trader.desk.store.PositionMeta;
import dev.belis.trader.desk.store.Proposal;
import dev.belis.trader.desk.store.ProposalKind;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.inject.Singleton;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The seven MCP tools for research bots. They read, and they propose. None of them places, cancels or modifies
 * an order: a proposal only becomes an order after the owner approves it in Telegram and the risk checks pass
 * again.
 *
 * <p>Prices and quantities are taken as strings so they never pass through a binary floating point type in this
 * service; JSON numbers are accepted too.
 */
@Singleton
public class DeskTools {

    private static final String PROPOSAL_FLOW = " Nothing is sent to the broker: the owner must approve the proposal "
            + "in Telegram, and the risk checks run again with a fresh quote before any order is placed.";

    private final Broker broker;
    private final DeskConfig config;
    private final ProposalService proposals;
    private final RiskContextBuilder contexts;

    public DeskTools(Broker broker, DeskConfig config, ProposalService proposals, RiskContextBuilder contexts) {
        this.broker = broker;
        this.config = config;
        this.proposals = proposals;
        this.contexts = contexts;
    }

    // =====================================================================================================
    // Read
    // =====================================================================================================

    public record AccountView(String mode, String account, String baseCurrency, BigDecimal netLiquidation,
                              BigDecimal cash, boolean killSwitch) {
    }

    @Tool(name = "get_account_summary", description = """
            Returns the brokerage account: trading mode (paper or live), account id, base currency, \
            net liquidation value and cash (both in the base currency), and whether the kill switch is on \
            (when it is on, new entries are rejected). Takes no arguments.""")
    public AccountView getAccountSummary() {
        AccountSummary s = read(broker::accountSummary);
        return new AccountView(mode(), s.account(), s.baseCurrency(), s.netLiquidation(), s.cash(),
                contexts.killSwitch());
    }

    public record PositionView(String symbol, long conid, BigDecimal quantity, BigDecimal avgCost, String currency,
                               String origin, Long proposalId, BigDecimal entryPrice, BigDecimal currentStop,
                               BigDecimal targetPrice) {
    }

    public record PositionsView(String mode, List<PositionView> positions) {
    }

    @Tool(name = "get_positions", description = """
            Lists the open stock positions as the broker reports them: symbol, quantity, average cost per share \
            (contract currency, commissions included) and, when the gateway knows them, the origin \
            (GATEWAY = opened through an approved proposal, EXTERNAL = opened outside this system), the proposal \
            id, entry price, current stop and target. Use it before propose_exit or propose_stop_update. \
            Takes no arguments.""")
    public PositionsView getPositions() {
        List<PositionView> out = new ArrayList<>();
        read(broker::positions).forEach(p -> {
            PositionMeta m = proposals.positionMeta(p.conid()).orElse(null);
            out.add(new PositionView(p.symbol(), p.conid(), p.quantity(), p.avgCost(), p.currency(),
                    m == null ? null : m.origin.name(), m == null ? null : m.proposalId,
                    m == null ? null : m.entryPrice, m == null ? null : m.currentStop,
                    m == null ? null : m.targetPrice));
        });
        return new PositionsView(mode(), out);
    }

    @Tool(name = "get_quote", description = """
            Returns a snapshot quote for a US stock: last, bid, ask and previous close (USD; any may be null when \
            the broker has none), the time of the snapshot, and delayed=true when the broker served delayed data. \
            Proposals are checked against a live quote, so use this to set a realistic entry limit.""")
    public Quote getQuote(@ToolArg(description = "US stock ticker, e.g. AAPL") String symbol) {
        String s = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) {
            throw new ToolCallException("symbol is required");
        }
        return read(() -> broker.quote(s));
    }

    // =====================================================================================================
    // Propose
    // =====================================================================================================

    @Tool(name = "propose_order", description = """
            Proposes a new long stock position (a BUY entry). This does NOT place an order. The gateway sizes the \
            position itself from the account's risk limits and runs every risk check (kill switch, daily loss, \
            whitelist, earnings, price order stop < entry < target, stop distance, minimum reward/risk, live \
            quote, drift of the entry limit from the last price, entry time window, cash, position size, \
            duplicate symbol, max open positions, max per industry, max entries per day). If all pass, the \
            proposal is sent to the owner for approval and you get the proposal id, the computed quantity, the \
            money at risk and any warnings (e.g. the 30-day rule). If any fail, the proposal is rejected and you \
            get the failed checks with their values; you may fix the inputs and propose again. If approved, the \
            gateway places a bracket: limit BUY at entryLimit (DAY), limit SELL at target and stop SELL at stop \
            (both GTC). Check later with get_proposal_status.""")
    public ToolResponse proposeOrder(
            @ToolArg(description = "US stock ticker, e.g. AAPL. Must be on the owner's whitelist.") String symbol,
            @ToolArg(description = "Limit price for the BUY, in USD, as a decimal string, e.g. \"187.25\". "
                    + "Must be close to the current last price.") String entryLimit,
            @ToolArg(description = "Protective stop price in USD as a decimal string, below entryLimit, "
                    + "e.g. \"180.50\".") String stop,
            @ToolArg(description = "Profit target price in USD as a decimal string, above entryLimit, "
                    + "e.g. \"205.00\".") String target,
            @ToolArg(description = "Why this trade, in a few sentences: the setup, the catalyst, what would "
                    + "prove it wrong. Shown to the owner.") String thesis,
            @ToolArg(description = "URLs or citations backing the thesis. May be empty.", required = false)
            List<String> sources,
            @ToolArg(description = "true if the company reports earnings within the next few trading days "
                    + "(the entry is then rejected); false otherwise. Check before answering.") boolean earningsSoon,
            @ToolArg(description = "Name of the bot or model making the proposal, e.g. \"grok-research\".")
            String proposedBy) {
        return propose(ProposalKind.ENTRY, symbol, proposedBy, () -> proposals.proposeEntry(new ProposalRequests.Entry(symbol,
                ToolArgs.decimal("entryLimit", entryLimit), ToolArgs.decimal("stop", stop),
                ToolArgs.decimal("target", target), thesis, sources, earningsSoon, proposedBy)));
    }

    @Tool(name = "propose_exit", description = """
            Proposes selling an open position (all of it by default) at market. This does NOT place an order. \
            Rejected when there is no position, the quantity exceeds the position the broker reports, or an exit \
            for the same stock is already open. Returns the proposal id and any warnings (e.g. the 30-day rule \
            when the stock was bought within the last 30 days).""")
    public ToolResponse proposeExit(
            @ToolArg(description = "Ticker of the open position, e.g. AAPL") String symbol,
            @ToolArg(description = "Why exit now. Shown to the owner.") String reason,
            @ToolArg(description = "Name of the bot or model making the proposal.") String proposedBy,
            @ToolArg(description = "Shares to sell as a decimal string, e.g. \"10\". Omit to sell the whole "
                    + "position.", required = false) String quantity) {
        return propose(ProposalKind.EXIT, symbol, proposedBy, () -> proposals.proposeExit(new ProposalRequests.Exit(symbol,
                quantity == null || quantity.isBlank() ? null : ToolArgs.decimal("quantity", quantity),
                reason, proposedBy)));
    }

    @Tool(name = "propose_stop_update", description = """
            Proposes moving the protective stop of an open position UP (stops never move down). This does NOT \
            modify an order. Rejected unless newStop is above the current stop and below the current last price \
            (live quote), and no other stop update for the stock is open. Use get_positions for the current stop \
            and get_quote for the last price.""")
    public ToolResponse proposeStopUpdate(
            @ToolArg(description = "Ticker of the open position, e.g. AAPL") String symbol,
            @ToolArg(description = "New stop price in USD as a decimal string, e.g. \"190.00\".") String newStop,
            @ToolArg(description = "Why move the stop now. Shown to the owner.") String reason,
            @ToolArg(description = "Name of the bot or model making the proposal.") String proposedBy) {
        return propose(ProposalKind.STOP_UPDATE, symbol, proposedBy, () -> proposals.proposeStopUpdate(new ProposalRequests.StopUpdate(symbol,
                ToolArgs.decimal("newStop", newStop), reason, proposedBy)));
    }

    // =====================================================================================================
    // Status
    // =====================================================================================================

    public record ProposalStatusView(long id, String kind, String status, String symbol, BigDecimal entryLimit,
                                     BigDecimal stopPrice, BigDecimal targetPrice, BigDecimal quantity,
                                     BigDecimal riskBase, BigDecimal rewardRisk, List<String> failedChecks,
                                     List<String> warnings, String proposedBy, Instant createdAt,
                                     Instant expiresAt, Instant decidedAt, String meaning) {
    }

    @Tool(name = "get_proposal_status", description = """
            Returns a proposal by id: kind (ENTRY, EXIT, STOP_UPDATE), status, prices, quantity, failed checks, \
            warnings and timestamps. Statuses: PROPOSED (waiting for the owner), APPROVED, SUBMITTED (order at \
            the broker), FILLED, CLOSED, REJECTED_RISK (a check failed), REJECTED_USER (the owner declined), \
            EXPIRED (not decided in time), CANCELLED, FAILED (the broker refused or an error occurred).""")
    public ProposalStatusView getProposalStatus(
            @ToolArg(description = "Proposal id returned by a propose_* tool") long proposalId) {
        Proposal p = proposals.find(proposalId)
                .orElseThrow(() -> new ToolCallException("No proposal with id " + proposalId));
        String symbol = proposals.instrument(p.conid).map(i -> i.symbol).orElse(null);
        List<String> failed = new ArrayList<>();
        JsonNode checks = p.riskResult == null ? null : p.riskResult.get("checks");
        if (checks != null) {
            checks.forEach(c -> {
                if (!c.path("passed").asBoolean(false)) {
                    failed.add(c.path("name").asText() + ": " + c.path("actual").asText()
                            + " (limit " + c.path("limit").asText() + ")");
                }
            });
        }
        List<String> warnings = new ArrayList<>();
        p.warnings.forEach(w -> warnings.add(w.path("message").asText(w.toString())));
        return new ProposalStatusView(p.id, p.kind.name(), p.status, symbol, p.entryLimit, p.stopPrice,
                p.targetPrice, p.quantity, p.riskEur, p.rewardRisk, failed, warnings, p.proposedBy, p.createdAt,
                p.expiresAt, p.decidedAt, meaning(p.status));
    }

    // =====================================================================================================

    /** Every refusal, whether from argument parsing here or from the service, is audited once. */
    private ToolResponse propose(ProposalKind kind, String symbol, String proposedBy, Supplier<ProposalOutcome> call) {
        try {
            ProposalOutcome o = call.get();
            return o.accepted() ? ToolResponse.success(accepted(o)) : ToolResponse.error(rejected(o));
        } catch (ToolArgs.BadArgument e) {
            proposals.recordRefusal(kind, symbol, proposedBy, e.getMessage());
            return ToolResponse.error("Not proposed: " + e.getMessage());
        } catch (ProposalRefusedException e) {
            return ToolResponse.error("Not proposed: " + e.getMessage()); // audited by the service
        }
    }

    private String accepted(ProposalOutcome o) {
        StringBuilder b = new StringBuilder();
        b.append("Proposal ").append(o.proposalId()).append(" created (").append(o.kind()).append(' ')
                .append(o.symbol()).append(", ").append(mode()).append(" account), status PROPOSED: waiting for ")
                .append("the owner's approval until ").append(o.expiresAt()).append(".\n");
        if (o.quantity() != null) {
            b.append("Quantity: ").append(plain(o.quantity())).append(" shares\n");
        }
        if (o.riskBase() != null) {
            b.append("Risk: ").append(plain(o.riskBase())).append(' ').append(o.baseCurrency()).append('\n');
        }
        if (o.rewardRisk() != null) {
            b.append("Reward/risk: ").append(plain(o.rewardRisk())).append('\n');
        }
        appendWarnings(b, o);
        b.append(PROPOSAL_FLOW.strip());
        return b.toString();
    }

    private static String rejected(ProposalOutcome o) {
        StringBuilder b = new StringBuilder();
        b.append("Proposal ").append(o.proposalId()).append(" rejected (").append(o.kind()).append(' ')
                .append(o.symbol()).append("). Failed checks:\n");
        for (ProposalCheck c : o.failures()) {
            b.append("- ").append(c.describe()).append('\n');
        }
        appendWarnings(b, o);
        return b.toString().strip();
    }

    private static void appendWarnings(StringBuilder b, ProposalOutcome o) {
        if (o.warnings().isEmpty()) {
            b.append("Warnings: none\n");
        } else {
            b.append("Warnings:\n");
            o.warnings().forEach(w -> b.append("- ").append(w).append('\n'));
        }
    }

    private static String meaning(String status) {
        return switch (status) {
            case "PROPOSED" -> "waiting for the owner's decision in Telegram";
            case "APPROVED" -> "approved by the owner; the order is being placed";
            case "SUBMITTED" -> "order is working at the broker";
            case "FILLED" -> "order filled";
            case "CLOSED" -> "done: position closed or stop moved";
            case "REJECTED_RISK" -> "a risk check failed; nothing was sent to the broker";
            case "REJECTED_USER" -> "the owner declined";
            case "EXPIRED" -> "the owner did not decide in time";
            case "CANCELLED" -> "cancelled";
            case "FAILED" -> "the broker refused the order or an error occurred";
            default -> "unknown status";
        };
    }

    private String mode() {
        return config.mode().name().toLowerCase(Locale.ROOT);
    }

    private static <T> T read(Supplier<T> call) {
        try {
            return call.get();
        } catch (BrokerUnavailableException e) {
            throw new ToolCallException("The broker is not connected right now. Try again later.");
        } catch (BrokerTimeoutException e) {
            throw new ToolCallException("The broker did not answer in time. Try again later.");
        } catch (BrokerException e) {
            throw new ToolCallException("Broker error: " + e.getMessage());
        }
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
