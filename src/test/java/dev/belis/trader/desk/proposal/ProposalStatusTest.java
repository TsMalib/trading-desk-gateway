package dev.belis.trader.desk.proposal;

import dev.belis.trader.desk.store.ProposalKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Set;

import static dev.belis.trader.desk.proposal.ProposalStatus.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProposalStatusTest {

    static Set<ProposalStatus> next(ProposalStatus from, ProposalKind kind) {
        Set<ProposalStatus> out = EnumSet.noneOf(ProposalStatus.class);
        for (ProposalStatus to : values()) {
            if (from.canTransitionTo(to, kind)) {
                out.add(to);
            }
        }
        return out;
    }

    @Test
    void entryMainPath() {
        assertTrue(PROPOSED.canTransitionTo(APPROVED, ProposalKind.ENTRY));
        assertTrue(APPROVED.canTransitionTo(SUBMITTED, ProposalKind.ENTRY));
        assertTrue(SUBMITTED.canTransitionTo(FILLED, ProposalKind.ENTRY));
        assertTrue(FILLED.canTransitionTo(CLOSED, ProposalKind.ENTRY));
    }

    @Test
    void fullTableForEntry() {
        assertEquals(EnumSet.of(APPROVED, REJECTED_RISK, REJECTED_USER, EXPIRED, CANCELLED),
                next(PROPOSED, ProposalKind.ENTRY));
        assertEquals(EnumSet.of(SUBMITTED, REJECTED_RISK, CANCELLED, FAILED), next(APPROVED, ProposalKind.ENTRY));
        assertEquals(EnumSet.of(FILLED, CANCELLED, FAILED), next(SUBMITTED, ProposalKind.ENTRY));
        assertEquals(EnumSet.of(CLOSED), next(FILLED, ProposalKind.ENTRY));
    }

    @Test
    void stopUpdateClosesInsteadOfFilling() {
        assertEquals(EnumSet.of(CLOSED, CANCELLED, FAILED), next(SUBMITTED, ProposalKind.STOP_UPDATE));
        assertEquals(EnumSet.of(FILLED, CANCELLED, FAILED), next(SUBMITTED, ProposalKind.EXIT));
    }

    @ParameterizedTest
    @EnumSource(value = ProposalStatus.class, names = {"CLOSED", "REJECTED_RISK", "REJECTED_USER", "EXPIRED",
            "CANCELLED", "FAILED"})
    void terminalStatesGoNowhere(ProposalStatus s) {
        assertTrue(s.isTerminal());
        for (ProposalKind k : ProposalKind.values()) {
            assertEquals(Set.of(), next(s, k), s + " " + k);
        }
    }

    @Test
    void noSkippingOrGoingBack() {
        assertFalse(PROPOSED.canTransitionTo(SUBMITTED, ProposalKind.ENTRY));
        assertFalse(PROPOSED.canTransitionTo(FILLED, ProposalKind.ENTRY));
        assertFalse(APPROVED.canTransitionTo(PROPOSED, ProposalKind.ENTRY));
        assertFalse(APPROVED.canTransitionTo(REJECTED_USER, ProposalKind.ENTRY));
        assertFalse(SUBMITTED.canTransitionTo(APPROVED, ProposalKind.ENTRY));
        assertFalse(PROPOSED.canTransitionTo(PROPOSED, ProposalKind.ENTRY));
        assertFalse(PROPOSED.canTransitionTo(null, ProposalKind.ENTRY));
    }
}
