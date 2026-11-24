package dev.belis.trader.desk.proposal;

/** CDI event fired after a PROPOSED proposal was committed as EXPIRED (not decided in time). */
public record ProposalExpired(long proposalId) {
}
