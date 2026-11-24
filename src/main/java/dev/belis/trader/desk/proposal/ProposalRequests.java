package dev.belis.trader.desk.proposal;

import java.math.BigDecimal;
import java.util.List;

/** Inputs of the three proposal kinds, already parsed (money as BigDecimal). Validated by {@link ProposalService}. */
public final class ProposalRequests {

    private ProposalRequests() {
    }

    public record Entry(String symbol, BigDecimal entryLimit, BigDecimal stop, BigDecimal target, String thesis,
                        List<String> sources, boolean earningsSoon, String proposedBy) {
    }

    /** @param quantity shares to sell; null sells the whole position IBKR reports */
    public record Exit(String symbol, BigDecimal quantity, String reason, String proposedBy) {
    }

    public record StopUpdate(String symbol, BigDecimal newStop, String reason, String proposedBy) {
    }
}
