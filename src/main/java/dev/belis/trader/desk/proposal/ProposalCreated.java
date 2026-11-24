package dev.belis.trader.desk.proposal;

import dev.belis.trader.desk.store.ProposalKind;

/**
 * CDI event fired after a proposal was committed in status PROPOSED (any kind), i.e. it now waits for the
 * owner's decision. Fired after the transaction commits, so observers can read the row.
 * Not fired for proposals rejected at proposal time.
 */
public record ProposalCreated(long proposalId, ProposalKind kind, long conid, String symbol) {
}
