package dev.belis.trader.desk.proposal;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.belis.trader.desk.broker.Broker;
import dev.belis.trader.desk.broker.BrokerException;
import dev.belis.trader.desk.broker.BrokerOpenOrder;
import dev.belis.trader.desk.broker.BrokerPosition;
import dev.belis.trader.desk.broker.BrokerTimeoutException;
import dev.belis.trader.desk.broker.BrokerUnavailableException;
import dev.belis.trader.desk.broker.ContractInfo;
import dev.belis.trader.desk.broker.OrderRefs;
import dev.belis.trader.desk.broker.Quote;
import dev.belis.trader.desk.config.DeskClock;
import dev.belis.trader.desk.config.DeskConfig;
import dev.belis.trader.desk.risk.EntryProposal;
import dev.belis.trader.desk.risk.RiskContext;
import dev.belis.trader.desk.risk.RiskEngine;
import dev.belis.trader.desk.risk.RiskLimits;
import dev.belis.trader.desk.risk.RiskResult;
import dev.belis.trader.desk.risk.ThirtyDayRule;
import dev.belis.trader.desk.risk.ThirtyDayWarning;
import dev.belis.trader.desk.store.AuditActor;
import dev.belis.trader.desk.store.AuditWriter;
import dev.belis.trader.desk.store.Instrument;
import dev.belis.trader.desk.store.InstrumentRepository;
import dev.belis.trader.desk.store.OrderRole;
import dev.belis.trader.desk.store.PositionMeta;
import dev.belis.trader.desk.store.PositionMetaRepository;
import dev.belis.trader.desk.store.Proposal;
import dev.belis.trader.desk.store.ProposalKind;
import dev.belis.trader.desk.store.ProposalRepository;
import dev.belis.trader.desk.store.Side;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.persistence.LockModeType;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Creates proposals and moves them through the {@link ProposalStatus} state machine.
 *
 * <p>Creating a proposal never sends anything to IBKR: it only reads the broker, runs the checks and stores the
 * proposal with its checks and warnings. Proposals are created one at a time (a lock), so two proposals cannot
 * both pass a limit that only one of them fits in. Broker calls run outside database transactions.
 *
 * <p>Every status change, and every proposal creation or refusal, writes an audit_log row in the same transaction.
 */
@ApplicationScoped
public class ProposalService {

    private static final Logger LOG = Logger.getLogger(ProposalService.class);

    public static final String EVENT_CREATED = "PROPOSAL_CREATED";
    public static final String EVENT_REFUSED = "PROPOSAL_REFUSED";
    public static final String EVENT_STATUS_CHANGED = "STATUS_CHANGED";
    public static final String EVENT_INSTRUMENT_SAVED = "INSTRUMENT_SAVED";
    public static final String EVENT_APPROVAL_UNAVAILABLE = "APPROVAL_RECHECK_UNAVAILABLE";
    public static final String EVENT_QUANTITY_REDUCED = "QUANTITY_REDUCED";
    public static final String EVENT_TELEGRAM_SENT = "TELEGRAM_SENT";

    /** Proposals arrive through MCP, from the research bots. */
    private static final AuditActor PROPOSER = AuditActor.GROK;

    private static final Pattern SYMBOL = Pattern.compile("[A-Z][A-Z0-9. ]{0,11}");
    private static final int MAX_TEXT = 4000;
    private static final int MAX_SOURCES = 20;
    private static final int MAX_SOURCE_LENGTH = 1000;
    private static final int MAX_PROPOSED_BY = 100;

    private final Broker broker;
    private final DeskConfig config;
    private final DeskClock clock;
    private final RiskContextBuilder contexts;
    private final ProposalRepository proposals;
    private final InstrumentRepository instruments;
    private final PositionMetaRepository positionMetas;
    private final AuditWriter audit;
    private final Event<ProposalCreated> created;
    private final Event<ProposalApproved> approved;
    private final Event<ProposalExpired> expired;
    private final RiskEngine engine;
    private final ThirtyDayRule thirtyDayRule = new ThirtyDayRule();
    private final ReentrantLock proposeLock = new ReentrantLock(true);

    public ProposalService(Broker broker, DeskConfig config, DeskClock clock, RiskContextBuilder contexts,
                           ProposalRepository proposals, InstrumentRepository instruments,
                           PositionMetaRepository positionMetas, AuditWriter audit, Event<ProposalCreated> created,
                           Event<ProposalApproved> approved, Event<ProposalExpired> expired) {
        this.broker = broker;
        this.config = config;
        this.clock = clock;
        this.contexts = contexts;
        this.proposals = proposals;
        this.instruments = instruments;
        this.positionMetas = positionMetas;
        this.audit = audit;
        this.created = created;
        this.approved = approved;
        this.expired = expired;
        this.engine = new RiskEngine(RiskLimits.from(config.risk()));
    }

    // =====================================================================================================
    // Create
    // =====================================================================================================

    /**
     * Runs the risk engine and the 30-day rule and stores an ENTRY proposal: PROPOSED when every check passes
     * (then fires {@link ProposalCreated}), REJECTED_RISK otherwise.
     *
     * @throws ProposalRefusedException when the input is invalid, the symbol is unknown or IBKR is unreachable
     */
    public ProposalOutcome proposeEntry(ProposalRequests.Entry r) {
        return refusalAudited(ProposalKind.ENTRY, r.symbol(), r.proposedBy(), () -> {
            String symbol = symbol(r.symbol());
            String proposedBy = proposedBy(r.proposedBy());
            BigDecimal entry = price("entryLimit", r.entryLimit());
            BigDecimal stop = price("stop", r.stop());
            BigDecimal target = price("target", r.target());
            String thesis = text("thesis", r.thesis());
            List<String> sources = sources(r.sources());

            return locked(() -> {
                ContractInfo c = contract(symbol);
                RiskContext ctx = broker(() -> contexts.forEntry(c, r.earningsSoon()));
                RiskResult risk = engine.evaluate(new EntryProposal(null, c.conid(), c.symbol(), c.industry(),
                        c.currency(), entry, stop, target), ctx);
                List<ThirtyDayWarning> warnings = thirtyDayRule.forBuy(c.isin(), ctx);
                List<ProposalCheck> checks = risk.checks().stream().map(ProposalCheck::of).toList();

                Map<String, Object> riskResult = new LinkedHashMap<>();
                riskResult.put("passed", risk.passed());
                riskResult.put("quantity", risk.quantity());
                riskResult.put("riskBase", risk.riskEur());
                riskResult.put("baseCurrency", ctx.baseCurrency());
                riskResult.put("rewardRisk", risk.rewardRisk());
                riskResult.put("equity", ctx.equity());
                riskResult.put("fxToBase", ctx.fxToBase());
                riskResult.put("lastPrice", ctx.lastQuote().map(Quote::last).orElse(null));
                riskResult.put("earningsSoon", r.earningsSoon());
                riskResult.put("checks", checks);

                return store(c, ProposalKind.ENTRY, checks, riskResult, warnings, p -> {
                    p.entryLimit = entry;
                    p.stopPrice = stop;
                    p.targetPrice = target;
                    p.quantity = risk.quantity();
                    p.riskEur = risk.riskEur();
                    p.rewardRisk = risk.rewardRisk();
                    p.thesis = thesis;
                    p.sources = toArray(sources);
                    p.proposedBy = proposedBy;
                }, ctx.baseCurrency());
            });
        });
    }

    /**
     * Stores an EXIT proposal: a market SELL of {@code quantity} (default: the whole position IBKR reports).
     * Rejected (REJECTED_RISK) when there is no position, the quantity exceeds it, or another exit for the same
     * instrument is in flight. Exits are allowed while the kill switch is on: they reduce risk.
     */
    public ProposalOutcome proposeExit(ProposalRequests.Exit r) {
        return refusalAudited(ProposalKind.EXIT, r.symbol(), r.proposedBy(), () -> {
            String symbol = symbol(r.symbol());
            String proposedBy = proposedBy(r.proposedBy());
            BigDecimal requested = r.quantity() == null ? null : quantity(r.quantity());
            String reason = text("reason", r.reason());

            return locked(() -> {
                ContractInfo c = contract(symbol);
                Instant now = clock.instant();
                Map<String, Object> riskResult = new LinkedHashMap<>();
                BigDecimal held = broker(() -> heldQuantity(c.conid()));
                BigDecimal qty = requested == null ? held : requested;
                List<ProposalCheck> checks = exitChecks(c.conid(), qty, held, now, null, riskResult);
                List<ThirtyDayWarning> warnings = thirtyDayRule.forExit(c.isin(), now,
                        broker(() -> contexts.recentExecutions(c, now)));

                return store(c, ProposalKind.EXIT, checks, riskResult, warnings, p -> {
                    p.quantity = qty;
                    p.thesis = reason;
                    p.proposedBy = proposedBy;
                }, null);
            });
        });
    }

    /**
     * Stores a STOP_UPDATE proposal that moves the working stop of a position to {@code newStop}. Rejected
     * (REJECTED_RISK) unless the new stop is strictly above the current stop (stops only move up) and strictly
     * below the last price of a live quote, and no other stop update for the instrument is in flight.
     *
     * <p>The current stop is the highest working STP SELL order IBKR reports for the instrument, falling back to
     * position_meta.current_stop when IBKR shows none.
     */
    public ProposalOutcome proposeStopUpdate(ProposalRequests.StopUpdate r) {
        return refusalAudited(ProposalKind.STOP_UPDATE, r.symbol(), r.proposedBy(), () -> {
            String symbol = symbol(r.symbol());
            String proposedBy = proposedBy(r.proposedBy());
            BigDecimal newStop = price("newStop", r.newStop());
            String reason = text("reason", r.reason());

            return locked(() -> {
                ContractInfo c = contract(symbol);
                Instant now = clock.instant();
                Map<String, Object> riskResult = new LinkedHashMap<>();
                List<ProposalCheck> checks = stopChecks(c, newStop, now, null, riskResult);
                BigDecimal held = (BigDecimal) riskResult.get("positionQuantity");

                return store(c, ProposalKind.STOP_UPDATE, checks, riskResult, List.of(), p -> {
                    p.stopPrice = newStop;
                    p.quantity = held;
                    p.thesis = reason;
                    p.proposedBy = proposedBy;
                }, null);
            });
        });
    }

    // =====================================================================================================
    // State machine
    // =====================================================================================================

    /**
     * Moves proposal {@code id} to {@code to}, under a row lock, and writes an audit row (event STATUS_CHANGED,
     * details from/to/kind plus {@code details}). Leaving PROPOSED sets decided_at. Joins the caller's
     * transaction or runs in its own.
     *
     * @throws IllegalTransitionException when the state machine does not allow it, or when approving a
     *                                    proposal past its expires_at
     * @throws NoSuchElementException     when there is no such proposal
     */
    public Proposal transition(long id, ProposalStatus to, AuditActor actor, Map<String, ?> details) {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(actor, "actor");
        return QuarkusTransaction.joiningExisting().call(() -> {
            Proposal p = proposals.findById(id, LockModeType.PESSIMISTIC_WRITE);
            if (p == null) {
                throw new NoSuchElementException("no proposal " + id);
            }
            ProposalStatus from = parse(p.status);
            if (from == null || !from.canTransitionTo(to, p.kind)) {
                throw new IllegalTransitionException(id, p.kind, p.status, to);
            }
            Instant now = clock.instant();
            if (to == ProposalStatus.APPROVED && p.expiresAt != null && !now.isBefore(p.expiresAt)) {
                throw new IllegalTransitionException(id, p.kind, p.status, to, "expired at " + p.expiresAt + ":");
            }
            p.status = to.name();
            if (from == ProposalStatus.PROPOSED) {
                p.decidedAt = now;
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("from", from.name());
            d.put("to", to.name());
            d.put("kind", p.kind.name());
            if (details != null) {
                details.forEach((k, v) -> d.putIfAbsent(k, v));
            }
            audit.write(actor, EVENT_STATUS_CHANGED, id, d);
            return p;
        });
    }

    // =====================================================================================================
    // Owner decisions
    // =====================================================================================================

    /**
     * The owner approved proposal {@code id}. Re-runs every check of its kind with fresh data from IBKR (a fresh
     * quote, positions, account, open orders), then in one transaction moves it PROPOSED -> APPROVED (actor USER)
     * and, when a check failed, APPROVED -> REJECTED_RISK (actor SYSTEM). The re-check is stored in
     * risk_result.approval. {@link ProposalApproved} is fired after commit when every check passed.
     *
     * <p>An ENTRY is sent with the lower of the approved quantity and the re-computed one, never more than the
     * owner saw. Nothing changes when the proposal already left PROPOSED (ALREADY_DECIDED) or IBKR cannot be
     * asked (UNAVAILABLE, still PROPOSED: the owner may tap again). A proposal past its expiry becomes EXPIRED.
     *
     * @param details added to the audit row of the owner's approval (e.g. the Telegram user id)
     */
    public Decision approve(long id, Map<String, ?> details) {
        Proposal p = find(id).orElse(null);
        if (p == null) {
            return Decision.of(id, Decision.Outcome.NOT_FOUND, null);
        }
        if (parse(p.status) != ProposalStatus.PROPOSED) {
            return Decision.of(id, Decision.Outcome.ALREADY_DECIDED, p.status);
        }
        if (isPastExpiry(p)) {
            return expireNow(id);
        }

        Decision d = locked(() -> {
            Recheck r;
            try {
                r = recheck(p);
            } catch (ProposalRefusedException e) {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("reason", e.getMessage());
                if (details != null) {
                    details.forEach(a::putIfAbsent);
                }
                QuarkusTransaction.requiringNew().run(() ->
                        audit.write(AuditActor.USER, EVENT_APPROVAL_UNAVAILABLE, id, a));
                return new Decision(id, Decision.Outcome.UNAVAILABLE, ProposalStatus.PROPOSED.name(), List.of(),
                        null, e.getMessage());
            }
            try {
                return QuarkusTransaction.requiringNew().call(() -> decide(id, r, details));
            } catch (IllegalTransitionException e) {
                Proposal now = find(id).orElseThrow();
                if (parse(now.status) == ProposalStatus.PROPOSED && isPastExpiry(now)) {
                    return expireNow(id);
                }
                return Decision.of(id, Decision.Outcome.ALREADY_DECIDED, now.status);
            }
        });

        if (d.outcome() == Decision.Outcome.APPROVED) {
            try {
                Instrument i = instrument(p.conid).orElseThrow();
                approved.fire(new ProposalApproved(id, p.kind, i.conid, i.symbol));
            } catch (RuntimeException e) {
                LOG.errorf(e, "ProposalApproved observer failed for proposal %d", id);
            }
        }
        return d;
    }

    /**
     * The owner rejected proposal {@code id}: PROPOSED -> REJECTED_USER. Returns ALREADY_DECIDED, changing
     * nothing, when it already left PROPOSED.
     */
    public Decision reject(long id, Map<String, ?> details) {
        try {
            Proposal p = transition(id, ProposalStatus.REJECTED_USER, AuditActor.USER, details);
            return Decision.of(id, Decision.Outcome.REJECTED_USER, p.status);
        } catch (NoSuchElementException e) {
            return Decision.of(id, Decision.Outcome.NOT_FOUND, null);
        } catch (IllegalTransitionException e) {
            return Decision.of(id, Decision.Outcome.ALREADY_DECIDED, e.from());
        }
    }

    /**
     * An EXIT the owner ordered directly (the Telegram /flatten command, confirmed): stored and moved
     * PROPOSED -> APPROVED (actor USER) in one transaction. {@code quantity} must be the position IBKR reports.
     * Fires no event: the caller sends the order itself.
     */
    public long createOwnerExit(ContractInfo c, BigDecimal quantity, String reason, Map<String, ?> details) {
        Instant now = clock.instant();
        BigDecimal qty = quantity(quantity);
        List<ProposalCheck> checks = List.of(
                new ProposalCheck("POSITION", true, plain(qty), "> 0 shares held"),
                new ProposalCheck("SELL_QUANTITY", true, plain(qty), "<= " + plain(qty) + " (position)"));
        Map<String, Object> riskResult = new LinkedHashMap<>();
        riskResult.put("passed", true);
        riskResult.put("positionQuantity", qty);
        riskResult.put("checks", checks);
        return QuarkusTransaction.requiringNew().call(() -> {
            saveInstrument(c);
            Proposal n = new Proposal();
            n.conid = c.conid();
            n.kind = ProposalKind.EXIT;
            n.status = ProposalStatus.PROPOSED.name();
            n.quantity = qty;
            n.thesis = reason;
            n.proposedBy = "owner";
            n.riskResult = AuditWriter.toJson(riskResult);
            n.createdAt = now;
            n.expiresAt = now.plus(Duration.ofMinutes(1));
            proposals.persist(n);
            proposals.flush();
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("kind", ProposalKind.EXIT.name());
            d.put("status", ProposalStatus.PROPOSED.name());
            d.put("symbol", c.symbol());
            d.put("conid", c.conid());
            d.put("proposedBy", n.proposedBy);
            d.put("quantity", qty);
            d.put("reason", reason);
            audit.write(AuditActor.USER, EVENT_CREATED, n.id, d);
            transition(n.id, ProposalStatus.APPROVED, AuditActor.USER, details);
            return n.id;
        });
    }

    /** Fresh checks for a stored proposal; {@code quantity} is what would be sent (ENTRY: re-sized). */
    private record Recheck(List<ProposalCheck> checks, BigDecimal quantity, BigDecimal riskBase,
                           Map<String, Object> values) {
        boolean passed() {
            return checks.stream().allMatch(ProposalCheck::passed);
        }
    }

    private Recheck recheck(Proposal p) {
        Instrument i = instrument(p.conid)
                .orElseThrow(() -> new ProposalRefusedException("instrument " + p.conid + " is not stored"));
        ContractInfo c = contract(i.symbol);
        Instant now = clock.instant();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("at", now);
        List<ProposalCheck> checks = new ArrayList<>();
        if (c.conid() != p.conid) {
            checks.add(new ProposalCheck("CONTRACT", false, "conid " + c.conid(), "conid " + p.conid));
        }
        switch (p.kind) {
            case ENTRY -> {
                boolean earningsSoon = p.riskResult != null && p.riskResult.path("earningsSoon").asBoolean(false);
                RiskContext ctx = broker(() -> contexts.forEntry(c, earningsSoon));
                RiskResult risk = engine.evaluate(new EntryProposal(p.id, c.conid(), c.symbol(), c.industry(),
                        c.currency(), p.entryLimit, p.stopPrice, p.targetPrice), ctx);
                risk.checks().stream().map(ProposalCheck::of).forEach(checks::add);
                BigDecimal qty = p.quantity == null ? risk.quantity() : p.quantity.min(risk.quantity());
                BigDecimal riskBase = qty.signum() > 0 && ctx.fxToBase() != null
                        ? qty.multiply(p.entryLimit.subtract(p.stopPrice)).multiply(ctx.fxToBase())
                        .setScale(2, RoundingMode.HALF_UP)
                        : risk.riskEur();
                values.put("quantity", qty);
                values.put("recomputedQuantity", risk.quantity());
                values.put("riskBase", riskBase);
                values.put("equity", ctx.equity());
                values.put("lastPrice", ctx.lastQuote().map(Quote::last).orElse(null));
                return new Recheck(withChecks(checks, values), qty, riskBase, values);
            }
            case EXIT -> {
                BigDecimal held = broker(() -> heldQuantity(c.conid()));
                checks.addAll(exitChecks(c.conid(), p.quantity, held, now, p.id, values));
                return new Recheck(withChecks(checks, values), p.quantity, null, values);
            }
            case STOP_UPDATE -> {
                checks.addAll(stopChecks(c, p.stopPrice, now, p.id, values));
                return new Recheck(withChecks(checks, values), p.quantity, null, values);
            }
            default -> throw new IllegalStateException("unknown kind " + p.kind);
        }
    }

    private static List<ProposalCheck> withChecks(List<ProposalCheck> checks, Map<String, Object> values) {
        values.put("passed", checks.stream().allMatch(ProposalCheck::passed));
        values.put("checks", checks);
        return checks;
    }

    /** In a transaction: PROPOSED -> APPROVED (USER), then -> REJECTED_RISK (SYSTEM) if the re-check failed. */
    private Decision decide(long id, Recheck r, Map<String, ?> details) {
        Proposal p = transition(id, ProposalStatus.APPROVED, AuditActor.USER, details);
        ObjectNode rr = p.riskResult instanceof
                ObjectNode o ? o.deepCopy()
                : JsonNodeFactory.instance.objectNode();
        rr.set("approval", AuditWriter.toJson(r.values()));
        p.riskResult = rr;
        if (r.passed()) {
            if (p.kind == ProposalKind.ENTRY && r.quantity() != null && p.quantity != null
                    && r.quantity().compareTo(p.quantity) < 0) {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("from", p.quantity);
                a.put("to", r.quantity());
                a.put("riskBase", r.riskBase());
                audit.write(AuditActor.SYSTEM, EVENT_QUANTITY_REDUCED, id, a);
                p.quantity = r.quantity();
                p.riskEur = r.riskBase();
            }
            return new Decision(id, Decision.Outcome.APPROVED, p.status, r.checks(), p.quantity, null);
        }
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("reason", "re-check at approval failed");
        a.put("failed", r.checks().stream().filter(c -> !c.passed()).map(ProposalCheck::describe).toList());
        p = transition(id, ProposalStatus.REJECTED_RISK, AuditActor.SYSTEM, a);
        return new Decision(id, Decision.Outcome.REJECTED_RISK, p.status, r.checks(), null, null);
    }

    private boolean isPastExpiry(Proposal p) {
        return p.expiresAt != null && !clock.instant().isBefore(p.expiresAt);
    }

    private Decision expireNow(long id) {
        try {
            QuarkusTransaction.requiringNew().run(() -> transition(id, ProposalStatus.EXPIRED, AuditActor.SYSTEM,
                    Map.of("reason", "not decided in time")));
            fireExpired(id);
            return Decision.of(id, Decision.Outcome.EXPIRED, ProposalStatus.EXPIRED.name());
        } catch (IllegalTransitionException e) {
            return Decision.of(id, Decision.Outcome.ALREADY_DECIDED, e.from());
        }
    }

    private void fireExpired(long id) {
        try {
            expired.fire(new ProposalExpired(id));
        } catch (RuntimeException e) {
            LOG.errorf(e, "ProposalExpired observer failed for proposal %d", id);
        }
    }

    /**
     * Cancels every proposal that could still lead to an order (PROPOSED or APPROVED), e.g. before a flatten.
     * Returns the ids it cancelled.
     */
    public List<Long> cancelOpen(AuditActor actor, String reason) {
        List<Long> ids = QuarkusTransaction.requiringNew().call(() -> proposals.getEntityManager().createQuery(
                        "select p.id from Proposal p where p.status in :s order by p.id", Long.class)
                .setParameter("s", List.of(ProposalStatus.PROPOSED.name(), ProposalStatus.APPROVED.name()))
                .getResultList());
        List<Long> done = new ArrayList<>();
        for (Long id : ids) {
            try {
                QuarkusTransaction.requiringNew().run(() ->
                        transition(id, ProposalStatus.CANCELLED, actor, Map.of("reason", reason)));
                done.add(id);
            } catch (IllegalTransitionException e) {
                LOG.debugf("Proposal %d changed before it could be cancelled: %s", id, e.getMessage());
            }
        }
        return done;
    }

    /** Expires PROPOSED proposals past their expires_at and fires {@link ProposalExpired} for each. */
    public int expireDue() {
        Instant now = clock.instant();
        List<Long> due = QuarkusTransaction.requiringNew().call(() -> proposals.getEntityManager().createQuery(
                        "select p.id from Proposal p where p.status = :s and p.expiresAt <= :now order by p.id",
                        Long.class)
                .setParameter("s", ProposalStatus.PROPOSED.name())
                .setParameter("now", now)
                .getResultList());
        int n = 0;
        for (Long id : due) {
            try {
                QuarkusTransaction.requiringNew().run(() ->
                        transition(id, ProposalStatus.EXPIRED, AuditActor.SYSTEM, Map.of("reason", "not decided in time")));
                fireExpired(id);
                n++;
            } catch (IllegalTransitionException e) {
                LOG.debugf("Proposal %d changed before it could expire: %s", id, e.getMessage());
            }
        }
        return n;
    }

    @Scheduled(every = "60s", delayed = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void expireJob() {
        int n = expireDue();
        if (n > 0) {
            LOG.infof("Expired %d proposal(s)", n);
        }
    }

    // =====================================================================================================
    // Read
    // =====================================================================================================

    public Optional<Proposal> find(long id) {
        return QuarkusTransaction.joiningExisting().call(() -> proposals.findByIdOptional(id));
    }

    /** Proposals in {@link ProposalStatus#IN_FLIGHT} (PROPOSED ones only while not expired), oldest first. */
    public List<Proposal> inFlight() {
        Instant now = clock.instant();
        return QuarkusTransaction.joiningExisting().call(() -> proposals.getEntityManager().createQuery("""
                        select p from Proposal p where p.status in :statuses
                          and (p.status <> :proposed or p.expiresAt is null or p.expiresAt > :now)
                        order by p.id
                        """, Proposal.class)
                .setParameter("statuses", ProposalStatus.IN_FLIGHT.stream().map(Enum::name).toList())
                .setParameter("proposed", ProposalStatus.PROPOSED.name())
                .setParameter("now", now)
                .getResultList());
    }

    /** Stores the id of the Telegram message that shows proposal {@code id}, with an audit row. */
    public void linkTelegramMessage(long id, long messageId) {
        QuarkusTransaction.requiringNew().run(() -> {
            Proposal p = proposals.findById(id);
            if (p == null) {
                throw new NoSuchElementException("no proposal " + id);
            }
            p.telegramMsgId = messageId;
            audit.write(AuditActor.SYSTEM, EVENT_TELEGRAM_SENT, id, Map.of("messageId", messageId));
        });
    }

    public Optional<Instrument> instrument(Long conid) {
        return conid == null ? Optional.empty()
                : QuarkusTransaction.joiningExisting().call(() -> instruments.findByIdOptional(conid));
    }

    public Optional<PositionMeta> positionMeta(long conid) {
        return QuarkusTransaction.joiningExisting().call(() -> positionMetas.findByIdOptional(conid));
    }

    public static ProposalStatus parse(String status) {
        try {
            return status == null ? null : ProposalStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // =====================================================================================================
    // Internals
    // =====================================================================================================

    private interface ProposalFields {
        void fill(Proposal p);
    }

    /** Upserts the instrument, persists the proposal and its audit row in one transaction; fires the event after. */
    private ProposalOutcome store(ContractInfo c, ProposalKind kind, List<ProposalCheck> checks,
                                  Map<String, Object> riskResult, List<ThirtyDayWarning> warnings,
                                  ProposalFields fields, String baseCurrency) {
        boolean passed = checks.stream().allMatch(ProposalCheck::passed);
        ProposalStatus status = passed ? ProposalStatus.PROPOSED : ProposalStatus.REJECTED_RISK;
        Instant now = clock.instant();

        Proposal p = QuarkusTransaction.requiringNew().call(() -> {
            saveInstrument(c);
            Proposal n = new Proposal();
            n.conid = c.conid();
            n.kind = kind;
            n.status = status.name();
            fields.fill(n);
            n.riskResult = AuditWriter.toJson(riskResult);
            n.warnings = AuditWriter.toJson(warnings);
            n.createdAt = now;
            if (passed) {
                n.expiresAt = expiry(kind, c, now);
            } else {
                n.decidedAt = now;
            }
            proposals.persist(n);
            proposals.flush();

            Map<String, Object> d = new LinkedHashMap<>();
            d.put("kind", kind.name());
            d.put("status", status.name());
            d.put("symbol", c.symbol());
            d.put("conid", c.conid());
            d.put("proposedBy", n.proposedBy);
            d.put("quantity", n.quantity);
            d.put("failed", checks.stream().filter(x -> !x.passed()).map(ProposalCheck::name).toList());
            d.put("warnings", warnings.size());
            audit.write(PROPOSER, EVENT_CREATED, n.id, d);
            return n;
        });

        return new ProposalOutcome(p.id, kind, status, c.symbol(), p.quantity, p.riskEur, baseCurrency,
                p.rewardRisk, checks, warnings.stream().map(ThirtyDayWarning::message).toList(), p.expiresAt);
    }

    /** Inserts or updates the instrument row (audited when it changes). Needs the caller's transaction. */
    public void saveInstrument(ContractInfo c) {
        Instrument i = instruments.findById(c.conid());
        boolean isNew = i == null;
        if (isNew) {
            i = new Instrument();
            i.conid = c.conid();
        } else if (Objects.equals(i.symbol, c.symbol()) && Objects.equals(i.primaryExch, c.primaryExchange())
                && Objects.equals(i.currency, c.currency()) && Objects.equals(i.isin, c.isin())
                && Objects.equals(i.name, c.name())) {
            return;
        }
        i.symbol = c.symbol();
        i.primaryExch = c.primaryExchange() == null ? "" : c.primaryExchange();
        i.currency = c.currency();
        i.isin = c.isin();
        i.name = c.name();
        if (isNew) {
            instruments.persist(i);
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("conid", c.conid());
        d.put("symbol", c.symbol());
        d.put("isin", c.isin());
        d.put("new", isNew);
        audit.write(AuditActor.SYSTEM, EVENT_INSTRUMENT_SAVED, null, d);
    }

    /**
     * When a new PROPOSED proposal expires: after desk.proposals.ttl, and an ENTRY at the latest at the end of
     * today's entry window (closeBufferMinutes before the day's close as IBKR reports it).
     */
    private Instant expiry(ProposalKind kind, ContractInfo c, Instant now) {
        Instant byTtl = now.plus(config.proposals().ttl());
        if (kind != ProposalKind.ENTRY) {
            return byTtl;
        }
        return windowEnd(c, now).filter(end -> end.isBefore(byTtl)).orElse(byTtl);
    }

    /** close - closeBufferMinutes of the contract's session for today's New York date, if IBKR reports one. */
    public Optional<Instant> windowEnd(ContractInfo c, Instant now) {
        LocalDate today = now.atZone(DeskConfig.MARKET_ZONE).toLocalDate();
        return c.todaySession()
                .filter(s -> s.close().atZone(DeskConfig.MARKET_ZONE).toLocalDate().equals(today))
                .map(s -> s.close().minus(Duration.ofMinutes(config.risk().closeBufferMinutes())));
    }

    /** Checks of an EXIT selling {@code qty} of a {@code held} position; puts what it saw into {@code values}. */
    private List<ProposalCheck> exitChecks(long conid, BigDecimal qty, BigDecimal held, Instant now, Long self,
                                           Map<String, Object> values) {
        List<ProposalCheck> checks = new ArrayList<>();
        checks.add(new ProposalCheck("POSITION", held.signum() > 0, plain(held), "> 0 shares held"));
        checks.add(new ProposalCheck("SELL_QUANTITY",
                qty != null && qty.signum() > 0 && qty.compareTo(held) <= 0, plain(qty),
                "<= " + plain(held) + " (position)"));
        checks.add(noneInFlight(ProposalKind.EXIT, conid, now, self));
        values.put("passed", checks.stream().allMatch(ProposalCheck::passed));
        values.put("positionQuantity", held);
        values.put("checks", checks);
        return checks;
    }

    /**
     * Checks of a STOP_UPDATE to {@code newStop}, reading position, working stop and a quote from IBKR; puts
     * what it saw into {@code values} (positionQuantity, currentStop, lastPrice).
     */
    private List<ProposalCheck> stopChecks(ContractInfo c, BigDecimal newStop, Instant now, Long self,
                                           Map<String, Object> values) {
        BigDecimal held = broker(() -> heldQuantity(c.conid()));
        Optional<BigDecimal> currentStop = broker(() -> currentStop(c.conid()));
        Optional<Quote> quote = broker(() -> quote(c.symbol()));
        Optional<BigDecimal> last = quote.filter(q -> !q.delayed()).map(Quote::last)
                .filter(l -> l.signum() > 0);

        List<ProposalCheck> checks = new ArrayList<>();
        checks.add(new ProposalCheck("POSITION", held.signum() > 0, plain(held), "> 0 shares held"));
        checks.add(new ProposalCheck("STOP_ABOVE_CURRENT",
                currentStop.isPresent() && newStop.compareTo(currentStop.get()) > 0,
                plain(newStop), currentStop.map(s -> "> " + plain(s) + " (current stop)")
                .orElse("a current stop (none found)")));
        checks.add(new ProposalCheck("STOP_BELOW_LAST",
                last.isPresent() && newStop.compareTo(last.get()) < 0, plain(newStop),
                last.map(l -> "< " + plain(l) + " (last price)").orElse(quote.isPresent() && quote.get().delayed()
                        ? "a live last price (quote is delayed)" : "a live last price (none)")));
        checks.add(noneInFlight(ProposalKind.STOP_UPDATE, c.conid(), now, self));

        values.put("passed", checks.stream().allMatch(ProposalCheck::passed));
        values.put("currentStop", currentStop.orElse(null));
        values.put("lastPrice", last.orElse(null));
        values.put("positionQuantity", held);
        values.put("checks", checks);
        return checks;
    }

    /** A proposal of {@code kind} for {@code conid}, other than {@code self}, that could still be acted on. */
    private ProposalCheck noneInFlight(ProposalKind kind, long conid, Instant now, Long self) {
        List<Long> ids = QuarkusTransaction.joiningExisting().call(() -> proposals.getEntityManager().createQuery("""
                        select p.id from Proposal p
                        where p.conid = :conid and p.kind = :kind and p.status in :statuses
                          and (p.status <> :proposed or p.expiresAt is null or p.expiresAt > :now)
                          and p.id <> :self
                        order by p.id
                        """, Long.class)
                .setParameter("self", self == null ? -1L : self)
                .setParameter("conid", conid)
                .setParameter("kind", kind)
                .setParameter("statuses", ProposalStatus.IN_FLIGHT.stream().map(Enum::name).toList())
                .setParameter("proposed", ProposalStatus.PROPOSED.name())
                .setParameter("now", now)
                .getResultList());
        return new ProposalCheck("NO_OPEN_" + kind.name(), ids.isEmpty(),
                ids.isEmpty() ? "none" : "proposal " + ids.get(0) + " is still open", "none");
    }

    private BigDecimal heldQuantity(long conid) {
        return broker.positions().stream()
                .filter(p -> p.conid() == conid)
                .map(BrokerPosition::quantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Optional<BigDecimal> currentStop(long conid) {
        List<BrokerOpenOrder> stops = broker.openOrders().stream()
                .filter(o -> o.conid() == conid && o.action() == Side.SELL && o.auxPrice() != null)
                .filter(o -> o.orderType() != null && o.orderType().toUpperCase(Locale.ROOT).startsWith("STP"))
                .toList();
        Optional<BigDecimal> ours = stops.stream()
                .filter(o -> OrderRefs.parse(o.orderRef()).map(x -> x.role() == OrderRole.STOP).orElse(false))
                .map(BrokerOpenOrder::auxPrice).max(BigDecimal::compareTo);
        if (ours.isPresent()) {
            return ours;
        }
        Optional<BigDecimal> any = stops.stream().map(BrokerOpenOrder::auxPrice).max(BigDecimal::compareTo);
        if (any.isPresent()) {
            return any;
        }
        return positionMeta(conid).map(m -> m.currentStop);
    }

    private Optional<Quote> quote(String symbol) {
        try {
            return Optional.of(broker.quote(symbol));
        } catch (BrokerUnavailableException e) {
            throw e;
        } catch (BrokerException e) {
            return Optional.empty();
        }
    }

    private ContractInfo contract(String symbol) {
        try {
            return broker.contract(symbol);
        } catch (BrokerUnavailableException | BrokerTimeoutException e) {
            throw brokerRefusal(e);
        } catch (BrokerException e) {
            throw new ProposalRefusedException("IBKR could not resolve symbol " + symbol + " as a US stock: "
                    + e.getMessage(), e);
        }
    }

    private static <T> T broker(Supplier<T> call) {
        try {
            return call.get();
        } catch (BrokerException e) {
            throw brokerRefusal(e);
        }
    }

    private static ProposalRefusedException brokerRefusal(BrokerException e) {
        if (e instanceof BrokerUnavailableException) {
            return new ProposalRefusedException("IBKR is not connected right now. Try again later.", e);
        }
        if (e instanceof BrokerTimeoutException) {
            return new ProposalRefusedException("IBKR did not answer in time. Try again later.", e);
        }
        return new ProposalRefusedException("IBKR returned an error: " + e.getMessage(), e);
    }

    private <T> T locked(Supplier<T> body) {
        proposeLock.lock();
        try {
            return body.get();
        } finally {
            proposeLock.unlock();
        }
    }

    /**
     * Runs {@code body}; a refusal is audited (PROPOSAL_REFUSED) and rethrown. An accepted proposal fires
     * {@link ProposalCreated} here, after the propose lock is released, so a slow observer (Telegram) does not
     * hold up other proposals.
     */
    private ProposalOutcome refusalAudited(ProposalKind kind, String symbol, String proposedBy,
                                           Supplier<ProposalOutcome> body) {
        try {
            ProposalOutcome o = body.get();
            if (o.accepted()) {
                try {
                    Long conid = find(o.proposalId()).map(p -> p.conid).orElseThrow();
                    created.fire(new ProposalCreated(o.proposalId(), o.kind(), conid, o.symbol()));
                } catch (RuntimeException e) {
                    // The proposal is stored; a failing observer (e.g. Telegram) must not turn it into an error.
                    LOG.errorf(e, "ProposalCreated observer failed for proposal %d", o.proposalId());
                }
            }
            return o;
        } catch (ProposalRefusedException e) {
            recordRefusal(kind, symbol, proposedBy, e.getMessage());
            throw e;
        }
    }

    /**
     * Writes a PROPOSAL_REFUSED audit row: a request that never became a proposal. The propose methods do this
     * themselves; callers use it for requests they refuse before calling them (e.g. unparsable arguments).
     */
    public void recordRefusal(ProposalKind kind, String symbol, String proposedBy, String reason) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("kind", kind.name());
        d.put("symbol", truncate(symbol));
        d.put("proposedBy", truncate(proposedBy));
        d.put("reason", reason);
        QuarkusTransaction.requiringNew().run(() -> audit.write(PROPOSER, EVENT_REFUSED, null, d));
    }

    private static String truncate(String s) {
        return s == null || s.length() <= MAX_PROPOSED_BY ? s : s.substring(0, MAX_PROPOSED_BY) + "...";
    }

    // ---- validation ----------------------------------------------------------------------------------------

    static String symbol(String s) {
        String v = s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
        if (!SYMBOL.matcher(v).matches()) {
            throw new ProposalRefusedException("symbol must be a US stock ticker such as AAPL, got '" + s + "'");
        }
        return v;
    }

    static String proposedBy(String s) {
        if (s == null || s.isBlank()) {
            throw new ProposalRefusedException("proposedBy is required: name the bot or model making the proposal");
        }
        String v = s.trim();
        if (v.length() > MAX_PROPOSED_BY) {
            throw new ProposalRefusedException("proposedBy is longer than " + MAX_PROPOSED_BY + " characters");
        }
        return v;
    }

    /** Positive, at most 6 decimals and 12 integer digits (numeric(18,6)). */
    static BigDecimal price(String name, BigDecimal v) {
        if (v == null) {
            throw new ProposalRefusedException(name + " is required");
        }
        BigDecimal s = v.stripTrailingZeros();
        if (s.signum() <= 0 || Math.max(s.scale(), 0) > 6 || s.precision() - s.scale() > 12) {
            throw new ProposalRefusedException(name + " must be a positive price with at most 6 decimals, got "
                    + v.toPlainString());
        }
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    /** Positive, at most 4 decimals and 14 integer digits (numeric(18,4)). */
    static BigDecimal quantity(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        if (s.signum() <= 0 || Math.max(s.scale(), 0) > 4 || s.precision() - s.scale() > 14) {
            throw new ProposalRefusedException("quantity must be a positive number of shares, got " + v.toPlainString());
        }
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    static String text(String name, String v) {
        if (v == null || v.isBlank()) {
            throw new ProposalRefusedException(name + " is required");
        }
        String t = v.strip();
        if (t.length() > MAX_TEXT) {
            throw new ProposalRefusedException(name + " is longer than " + MAX_TEXT + " characters");
        }
        return t;
    }

    static List<String> sources(List<String> sources) {
        if (sources == null) {
            return List.of();
        }
        if (sources.size() > MAX_SOURCES) {
            throw new ProposalRefusedException("at most " + MAX_SOURCES + " sources");
        }
        List<String> out = new ArrayList<>();
        for (String s : sources) {
            if (s == null || s.isBlank()) {
                continue;
            }
            String t = s.strip();
            if (t.length() > MAX_SOURCE_LENGTH) {
                throw new ProposalRefusedException("a source is longer than " + MAX_SOURCE_LENGTH + " characters");
            }
            out.add(t);
        }
        return out;
    }

    private static ArrayNode toArray(List<String> values) {
        ArrayNode a = JsonNodeFactory.instance.arrayNode();
        values.forEach(a::add);
        return a;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "n/a" : v.stripTrailingZeros().toPlainString();
    }
}
