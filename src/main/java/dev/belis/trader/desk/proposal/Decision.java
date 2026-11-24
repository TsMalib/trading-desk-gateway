package dev.belis.trader.desk.proposal;

import java.math.BigDecimal;
import java.util.List;

/**
 * Result of the owner's Approve or Reject.
 *
 * @param status   the proposal's status after the call, as stored (null when there is no such proposal)
 * @param checks   APPROVED / REJECTED_RISK: every check of the re-check at approval time; otherwise empty
 * @param quantity APPROVED: the quantity that will be sent (may be lower than proposed, never higher)
 * @param message  UNAVAILABLE: why the re-check could not run; otherwise null
 */
public record Decision(long proposalId, Outcome outcome, String status, List<ProposalCheck> checks,
                       BigDecimal quantity, String message) {

    public enum Outcome {
        /** Re-check passed; status APPROVED and {@link ProposalApproved} fired. */
        APPROVED,
        /** Re-check failed; status REJECTED_RISK. */
        REJECTED_RISK,
        /** The owner rejected it; status REJECTED_USER. */
        REJECTED_USER,
        /** It had already left PROPOSED; nothing changed. */
        ALREADY_DECIDED,
        /** It was past its expiry; status EXPIRED. */
        EXPIRED,
        /** IBKR could not be asked for the re-check; nothing changed, still PROPOSED. */
        UNAVAILABLE,
        NOT_FOUND
    }

    public Decision {
        checks = checks == null ? List.of() : List.copyOf(checks);
    }

    static Decision of(long id, Outcome outcome, String status) {
        return new Decision(id, outcome, status, List.of(), null, null);
    }

    public List<ProposalCheck> failures() {
        return checks.stream().filter(c -> !c.passed()).toList();
    }
}
