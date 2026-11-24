package dev.belis.trader.desk.support;

import dev.belis.trader.desk.proposal.ProposalApproved;
import dev.belis.trader.desk.proposal.ProposalExpired;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records every {@link ProposalApproved} and {@link ProposalExpired} event. */
@ApplicationScoped
public class DecisionEvents {

    private final List<ProposalApproved> approved = new CopyOnWriteArrayList<>();
    private final List<ProposalExpired> expired = new CopyOnWriteArrayList<>();

    void onApproved(@Observes ProposalApproved e) {
        approved.add(e);
    }

    void onExpired(@Observes ProposalExpired e) {
        expired.add(e);
    }

    public List<ProposalApproved> approved() {
        return List.copyOf(approved);
    }

    public List<ProposalExpired> expired() {
        return List.copyOf(expired);
    }

    public void clear() {
        approved.clear();
        expired.clear();
    }
}
