package dev.belis.trader.desk.support;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/** Empties every table between tests. */
@ApplicationScoped
public class TestDb {

    @Inject
    EntityManager em;

    @Transactional
    public void clean() {
        em.createNativeQuery("truncate audit_log, execution, broker_order, position_meta, proposal, instrument, "
                + "setting restart identity cascade").executeUpdate();
    }
}
