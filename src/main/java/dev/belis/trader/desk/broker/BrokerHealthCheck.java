package dev.belis.trader.desk.broker;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/** Ready only while connected to IB Gateway and IB Gateway is connected to IBKR. */
@Readiness
@ApplicationScoped
public class BrokerHealthCheck implements HealthCheck {

    private final Broker broker;

    public BrokerHealthCheck(Broker broker) {
        this.broker = broker;
    }

    @Override
    public HealthCheckResponse call() {
        ConnectionState state = broker.connectionState();
        return HealthCheckResponse.named("broker")
                .status(state == ConnectionState.CONNECTED)
                .withData("state", state.name())
                .build();
    }
}
