package dev.belis.trader.desk.proposal;

import dev.belis.trader.desk.broker.Broker;
import dev.belis.trader.desk.broker.BrokerException;
import dev.belis.trader.desk.broker.BrokerOpenOrder;
import dev.belis.trader.desk.broker.BrokerPosition;
import dev.belis.trader.desk.broker.ContractInfo;
import dev.belis.trader.desk.broker.OrderRefs;
import dev.belis.trader.desk.store.AuditActor;
import dev.belis.trader.desk.store.AuditWriter;
import dev.belis.trader.desk.store.BrokerOrder;
import dev.belis.trader.desk.store.BrokerOrderRepository;
import dev.belis.trader.desk.store.OrderRole;
import dev.belis.trader.desk.store.Side;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Closes everything on the owner's confirmed request (Telegram /flatten):
 * <ol>
 *   <li>turns the kill switch on and cancels every PROPOSED or APPROVED proposal;</li>
 *   <li>cancels every working order this service placed (orderRef P&lt;id&gt;-ROLE), BUY entries first, and waits
 *       until IBKR no longer reports them;</li>
 *   <li>market-sells each long position IBKR then reports, its whole quantity, as an EXIT proposal of its own
 *       (orderRef P&lt;id&gt;-EXIT, approved by the owner).</li>
 * </ol>
 * A position that still has a working SELL order (an external order, or a cancel IBKR has not confirmed) is not
 * sold, so a stop cannot fire on top of the market sell and leave the account short. Orders without our
 * orderRef are never touched.
 */
@ApplicationScoped
public class FlattenService {

    private static final Logger LOG = Logger.getLogger(FlattenService.class);

    public static final String EVENT_REQUESTED = "FLATTEN_REQUESTED";
    public static final String EVENT_DONE = "FLATTEN_DONE";

    /** How long to wait for IBKR to confirm cancels before selling. */
    static final Duration CANCEL_WAIT = Duration.ofSeconds(10);
    private static final long POLL_MS = 250;

    private final Broker broker;
    private final ProposalService proposals;
    private final KillSwitch killSwitch;
    private final BrokerOrderRepository brokerOrders;
    private final AuditWriter audit;

    public FlattenService(Broker broker, ProposalService proposals, KillSwitch killSwitch,
                          BrokerOrderRepository brokerOrders, AuditWriter audit) {
        this.broker = broker;
        this.proposals = proposals;
        this.killSwitch = killSwitch;
        this.brokerOrders = brokerOrders;
        this.audit = audit;
    }

    /** What a flatten would do now, for the confirmation prompt. */
    public record Preview(List<BrokerPosition> positions, List<BrokerOpenOrder> ourOrders,
                          List<BrokerOpenOrder> externalOrders) {
    }

    /** @throws BrokerException when IBKR cannot be asked */
    public Preview preview() {
        List<BrokerPosition> positions = longPositions(broker.positions());
        List<BrokerOpenOrder> orders = broker.openOrders();
        return new Preview(positions, orders.stream().filter(FlattenService::ours).toList(),
                orders.stream().filter(o -> !ours(o)).toList());
    }

    /**
     * One line per action, e.g. "AAPL: sold 100 (P57-EXIT)".
     *
     * @param error set when the flatten stopped early (e.g. IBKR not connected); the lines say what was done
     */
    public record Result(List<String> cancelledProposals, List<String> cancelledOrders, List<String> sold,
                         List<String> notSold, String error) {
    }

    /** Runs the flatten. Never throws for broker trouble: the result says what was and was not done. */
    public Result flatten(AuditActor actor, Map<String, ?> details) {
        Map<String, Object> req = new LinkedHashMap<>();
        if (details != null) {
            req.putAll(details);
        }
        QuarkusTransaction.requiringNew().run(() -> audit.write(actor, EVENT_REQUESTED, null, req));
        killSwitch.set(true, actor, "flatten");

        List<String> cancelledProposals = proposals.cancelOpen(actor, "flatten").stream()
                .map(id -> "#" + id).toList();
        List<String> cancelledOrders = new ArrayList<>();
        List<String> sold = new ArrayList<>();
        List<String> notSold = new ArrayList<>();
        String error = null;
        try {
            // 1. cancel our working orders, entries first so nothing new is bought
            List<BrokerOpenOrder> ourOrders = broker.openOrders().stream().filter(FlattenService::ours)
                    .sorted(Comparator.comparing((BrokerOpenOrder o) -> o.action() == Side.BUY ? 0 : 1))
                    .toList();
            Set<String> cancelling = new LinkedHashSet<>();
            for (BrokerOpenOrder o : ourOrders) {
                if (cancelling.contains(o.orderRef())) {
                    continue;
                }
                try {
                    broker.cancel(o.orderRef());
                    cancelling.add(o.orderRef());
                    cancelledOrders.add(o.orderRef());
                } catch (BrokerException | IllegalArgumentException e) {
                    // already gone (e.g. a child cancelled with its parent) is fine; anything else shows below
                    LOG.infof("Cancel %s: %s", o.orderRef(), e.getMessage());
                }
            }
            waitUntil(() -> broker.openOrders().stream().noneMatch(o -> cancelling.contains(o.orderRef())));

            // 2. sell what IBKR reports now
            List<BrokerOpenOrder> stillWorking = broker.openOrders();
            for (BrokerPosition p : longPositions(broker.positions())) {
                List<BrokerOpenOrder> sells = stillWorking.stream()
                        .filter(o -> o.conid() == p.conid() && o.action() == Side.SELL).toList();
                if (!sells.isEmpty()) {
                    notSold.add(p.symbol() + ": " + plain(p.quantity()) + " not sold, SELL order(s) still working: "
                            + sells.stream().map(FlattenService::describe).collect(Collectors.joining(", ")));
                    continue;
                }
                Line line = sell(p, details);
                (line.sold() ? sold : notSold).add(line.text());
            }
        } catch (BrokerException e) {
            error = "IBKR: " + e.getMessage();
            LOG.errorf(e, "Flatten stopped");
        }

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("cancelledProposals", cancelledProposals);
        d.put("cancelledOrders", cancelledOrders);
        d.put("sold", sold);
        d.put("notSold", notSold);
        d.put("error", error);
        QuarkusTransaction.requiringNew().run(() -> audit.write(actor, EVENT_DONE, null, d));
        return new Result(cancelledProposals, cancelledOrders, sold, notSold, error);
    }

    private record Line(boolean sold, String text) {
        static Line notSold(BrokerPosition p, String why) {
            return new Line(false, p.symbol() + ": " + plain(p.quantity()) + " not sold, " + why);
        }
    }

    /** Sells the whole position as an owner-approved EXIT proposal. */
    private Line sell(BrokerPosition p, Map<String, ?> details) {
        long id;
        try {
            ContractInfo c = broker.contract(p.symbol());
            if (c.conid() != p.conid()) {
                return Line.notSold(p, "contract lookup returned conid " + c.conid());
            }
            Map<String, Object> approval = new LinkedHashMap<>();
            if (details != null) {
                approval.putAll(details);
            }
            approval.put("via", "flatten");
            id = proposals.createOwnerExit(c, p.quantity(), "Flatten requested by the owner", approval);
        } catch (BrokerException | ProposalRefusedException e) {
            return Line.notSold(p, String.valueOf(e.getMessage()));
        }

        String ref = OrderRefs.of(id, OrderRole.EXIT);
        boolean known = QuarkusTransaction.requiringNew().call(() -> brokerOrders.count("orderRef", ref) > 0)
                || broker.openOrders().stream().anyMatch(o -> ref.equals(o.orderRef()));
        if (known) {
            return Line.notSold(p, "order " + ref + " already exists");
        }
        try {
            int orderId = broker.marketSell(p.conid(), p.quantity(), ref);
            QuarkusTransaction.requiringNew().run(() -> {
                BrokerOrder o = new BrokerOrder();
                o.proposalId = id;
                o.ibOrderId = orderId;
                o.orderRef = ref;
                o.role = OrderRole.EXIT;
                o.action = Side.SELL;
                o.orderType = "MKT";
                o.quantity = p.quantity();
                o.status = "Submitted";
                brokerOrders.persist(o);
                proposals.transition(id, ProposalStatus.SUBMITTED, AuditActor.SYSTEM,
                        Map.of("orderRef", ref, "ibOrderId", orderId));
            });
            return new Line(true, p.symbol() + ": sold " + plain(p.quantity()) + " at market (" + ref + ")");
        } catch (RuntimeException e) {
            LOG.errorf(e, "Flatten sell of %s failed", p.symbol());
            try {
                proposals.transition(id, ProposalStatus.FAILED, AuditActor.SYSTEM,
                        Map.of("error", String.valueOf(e.getMessage())));
            } catch (RuntimeException ignored) {
                // the reconciler sorts it out
            }
            return Line.notSold(p, String.valueOf(e.getMessage()));
        }
    }

    private static void waitUntil(BooleanSupplier done) {
        long deadline = System.nanoTime() + CANCEL_WAIT.toNanos();
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static List<BrokerPosition> longPositions(List<BrokerPosition> positions) {
        return positions.stream().filter(p -> p.quantity() != null && p.quantity().signum() > 0).toList();
    }

    private static boolean ours(BrokerOpenOrder o) {
        return OrderRefs.parse(o.orderRef()).isPresent();
    }

    private static String describe(BrokerOpenOrder o) {
        String ref = o.orderRef() == null || o.orderRef().isBlank() ? "external #" + o.permId() : o.orderRef();
        return ref + " " + o.orderType() + " " + plain(o.quantity());
    }

    private static String plain(BigDecimal v) {
        return v == null ? "n/a" : v.stripTrailingZeros().toPlainString();
    }
}
