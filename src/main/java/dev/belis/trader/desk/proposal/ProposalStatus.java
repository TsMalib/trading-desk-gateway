package dev.belis.trader.desk.proposal;

import dev.belis.trader.desk.store.ProposalKind;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Proposal states. Stored by name in proposal.status.
 *
 * <pre>
 * Main path:   PROPOSED -> APPROVED -> SUBMITTED -> FILLED -> CLOSED
 *
 * PROPOSED  -> APPROVED | REJECTED_RISK | REJECTED_USER | EXPIRED | CANCELLED
 * APPROVED  -> SUBMITTED | REJECTED_RISK | CANCELLED | FAILED
 * SUBMITTED -> FILLED | CANCELLED | FAILED          (STOP_UPDATE: CLOSED instead of FILLED)
 * FILLED    -> CLOSED                               (not STOP_UPDATE: a stop move never fills)
 * </pre>
 *
 * A proposal is created as PROPOSED, or as REJECTED_RISK when its checks fail at proposal time.
 * APPROVED -> REJECTED_RISK is the re-check with a fresh quote at approval time failing.
 * REJECTED_RISK, REJECTED_USER, EXPIRED, CANCELLED, FAILED and CLOSED are terminal.
 */
public enum ProposalStatus {
    PROPOSED,
    APPROVED,
    SUBMITTED,
    FILLED,
    CLOSED,
    REJECTED_RISK,
    REJECTED_USER,
    EXPIRED,
    CANCELLED,
    FAILED;

    private static final Map<ProposalStatus, Set<ProposalStatus>> NEXT = Map.of(
            PROPOSED, EnumSet.of(APPROVED, REJECTED_RISK, REJECTED_USER, EXPIRED, CANCELLED),
            APPROVED, EnumSet.of(SUBMITTED, REJECTED_RISK, CANCELLED, FAILED),
            SUBMITTED, EnumSet.of(FILLED, CLOSED, CANCELLED, FAILED),
            FILLED, EnumSet.of(CLOSED));

    /** Statuses of an ENTRY that may still become (or already be) a position: used for the risk checks. */
    public static final Set<ProposalStatus> IN_FLIGHT = EnumSet.of(PROPOSED, APPROVED, SUBMITTED);

    public boolean isTerminal() {
        return !NEXT.containsKey(this);
    }

    public boolean canTransitionTo(ProposalStatus to, ProposalKind kind) {
        if (to == null || kind == null || !NEXT.getOrDefault(this, Set.of()).contains(to)) {
            return false;
        }
        if (this == SUBMITTED && to == FILLED) {
            return kind != ProposalKind.STOP_UPDATE;
        }
        if (this == SUBMITTED && to == CLOSED) {
            return kind == ProposalKind.STOP_UPDATE;
        }
        return true;
    }
}
