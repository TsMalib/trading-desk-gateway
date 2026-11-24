package dev.belis.trader.desk.store;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class BrokerOrderRepository implements PanacheRepositoryBase<BrokerOrder, Long> {
}
