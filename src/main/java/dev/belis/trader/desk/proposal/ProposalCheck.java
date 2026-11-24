package dev.belis.trader.desk.proposal;

import dev.belis.trader.desk.risk.RiskCheck;

/**
 * One check of any proposal kind, as stored in proposal.risk_result.checks. ENTRY checks come from the
 * {@link dev.belis.trader.desk.risk.RiskEngine}; EXIT and STOP_UPDATE checks are made by {@link ProposalService}.
 */
public record ProposalCheck(String name, boolean passed, String actual, String limit) {

    static ProposalCheck of(RiskCheck c) {
        return new ProposalCheck(c.name(), c.passed(), c.actual(), c.limit());
    }

    /** e.g. "DRIFT: 2.5% (limit <= 1.0%)". */
    public String describe() {
        return name + ": " + actual + " (limit " + limit + ")";
    }
}
