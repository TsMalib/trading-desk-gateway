package dev.belis.trader.desk.support;

import dev.belis.trader.desk.proposal.OrderExecutor;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.time.Duration;

/** Empties every table between tests, once the order executor has finished what it was doing. */
@ApplicationScoped
public class TestDb {

    @Inject
    EntityManager em;

    @Inject
    OrderExecutor executor;

    public void clean() {
        executor.awaitIdle(Duration.ofSeconds(30));
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("truncate audit_log, execution, "
                + "broker_order, position_meta, proposal, instrument, setting restart identity cascade")
                .executeUpdate());
    }
}
