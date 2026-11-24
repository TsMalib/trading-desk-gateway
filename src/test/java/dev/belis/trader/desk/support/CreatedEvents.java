package dev.belis.trader.desk.support;

import dev.belis.trader.desk.proposal.ProposalCreated;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records every {@link ProposalCreated} event. */
@ApplicationScoped
public class CreatedEvents {

    private final List<ProposalCreated> events = new CopyOnWriteArrayList<>();

    void on(@Observes ProposalCreated e) {
        events.add(e);
    }

    public List<ProposalCreated> all() {
        return List.copyOf(events);
    }

    public void clear() {
        events.clear();
    }
}
