package dev.belis.trader.desk.proposal;

import dev.belis.trader.desk.store.ProposalKind;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * What happened to a stored proposal request.
 *
 * @param status       PROPOSED (waits for the owner) or REJECTED_RISK (a check failed)
 * @param riskBase     money at risk in the account base currency (ENTRY only, else null)
 * @param checks       every check made, passed or not
 * @param warnings     human-readable warnings (30-day rule); never block
 * @param expiresAt    when a PROPOSED proposal expires; null when rejected
 */
public record ProposalOutcome(long proposalId, ProposalKind kind, ProposalStatus status, String symbol,
                              BigDecimal quantity, BigDecimal riskBase, String baseCurrency, BigDecimal rewardRisk,
                              List<ProposalCheck> checks, List<String> warnings, Instant expiresAt) {

    public ProposalOutcome {
        checks = List.copyOf(checks);
        warnings = List.copyOf(warnings);
    }

    public boolean accepted() {
        return status == ProposalStatus.PROPOSED;
    }

    public List<ProposalCheck> failures() {
        return checks.stream().filter(c -> !c.passed()).toList();
    }
}
