package dev.belis.trader.desk.broker;

import dev.belis.trader.desk.store.OrderRole;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** orderRef format "P&lt;proposalId&gt;-&lt;ROLE&gt;", e.g. P42-STOP. */
public final class OrderRefs {

    private static final Pattern REF = Pattern.compile("P(\\d+)-(ENTRY|TARGET|STOP|EXIT)");

    public record Parsed(long proposalId, OrderRole role) {
    }

    private OrderRefs() {
    }

    public static String of(long proposalId, OrderRole role) {
        return "P" + proposalId + "-" + role.name();
    }

    /** Empty for refs we did not create (external or manual orders). */
    public static Optional<Parsed> parse(String orderRef) {
        if (orderRef == null) {
            return Optional.empty();
        }
        Matcher m = REF.matcher(orderRef);
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Parsed(Long.parseLong(m.group(1)), OrderRole.valueOf(m.group(2))));
    }
}
