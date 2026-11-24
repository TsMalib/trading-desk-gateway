package dev.belis.trader.desk.proposal;

import dev.belis.trader.desk.store.ProposalKind;

/**
 * CDI event fired after a proposal was committed in status APPROVED: the owner approved it and every check
 * passed again with fresh data from IBKR. The order executor observes it. Fired after the transaction commits.
 */
public record ProposalApproved(long proposalId, ProposalKind kind, long conid, String symbol) {
}
