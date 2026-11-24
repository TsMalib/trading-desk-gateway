package dev.belis.trader.desk.config;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

/**
 * Liveness: the app is up and reports which IBKR mode it runs in.
 * Readiness comes from the built-in datasource check and
 * {@link dev.belis.trader.desk.broker.BrokerHealthCheck} (IB Gateway connection).
 */
@Liveness
@ApplicationScoped
public class DeskHealthCheck implements HealthCheck {

    private final DeskConfig config;

    public DeskHealthCheck(DeskConfig config) {
        this.config = config;
    }

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("desk")
                .up()
                .withData("mode", config.mode().name().toLowerCase())
                .build();
    }
}
