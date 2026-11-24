package dev.belis.trader.desk.proposal;

import dev.belis.trader.desk.store.ProposalKind;

/** A status change the state machine does not allow. Nothing is written when it is thrown. */
public class IllegalTransitionException extends IllegalStateException {

    private final long proposalId;
    private final String from;
    private final ProposalStatus to;

    public IllegalTransitionException(long proposalId, ProposalKind kind, String from, ProposalStatus to) {
        this(proposalId, kind, from, to, "illegal transition");
    }

    public IllegalTransitionException(long proposalId, ProposalKind kind, String from, ProposalStatus to,
                                      String reason) {
        super("proposal " + proposalId + " (" + kind + "): " + reason + " " + from + " -> " + to);
        this.proposalId = proposalId;
        this.from = from;
        this.to = to;
    }

    public long proposalId() {
        return proposalId;
    }

    /** The stored status, as text: it may not be a known {@link ProposalStatus}. */
    public String from() {
        return from;
    }

    public ProposalStatus to() {
        return to;
    }
}
