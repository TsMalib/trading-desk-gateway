package dev.belis.trader.desk.broker;

import dev.belis.trader.desk.config.DeskConfig;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Dev-only endpoints to compare what the broker returns with the paper account in TWS / Client Portal.
 * Not part of a prod build (also present in the test profile so it can be tested against FakeBroker).
 */
@Path("/dev/broker")
@Produces(MediaType.APPLICATION_JSON)
@IfBuildProfile(anyOf = {"dev", "test"})
public class DevBrokerResource {

    public record Snapshot(String mode, ConnectionState connection, AccountSummary summary,
                           List<BrokerPosition> positions, List<BrokerOpenOrder> openOrders) {
    }

    private final Broker broker;
    private final DeskConfig config;

    public DevBrokerResource(Broker broker, DeskConfig config) {
        this.broker = broker;
        this.config = config;
    }

    @GET
    @Path("/snapshot")
    public Snapshot snapshot() {
        return new Snapshot(config.mode().name().toLowerCase(), broker.connectionState(),
                broker.accountSummary(), broker.positions(), broker.openOrders());
    }

    @GET
    @Path("/contract/{symbol}")
    public ContractInfo contract(@PathParam("symbol") String symbol) {
        return broker.contract(symbol);
    }

    @GET
    @Path("/quote/{symbol}")
    public Quote quote(@PathParam("symbol") String symbol) {
        return broker.quote(symbol);
    }

    /** Executions since {@code since} (ISO instant), default the last 24 hours. */
    @GET
    @Path("/executions")
    public List<BrokerExecution> executions(@QueryParam("since") Instant since) {
        return broker.executionsSince(since != null ? since : Instant.now().minus(Duration.ofDays(1)));
    }

    @ServerExceptionMapper
    public RestResponse<Map<String, Object>> brokerError(BrokerException e) {
        Response.Status status = e instanceof BrokerUnavailableException ? Response.Status.SERVICE_UNAVAILABLE
                : e instanceof BrokerTimeoutException ? Response.Status.GATEWAY_TIMEOUT
                : Response.Status.BAD_GATEWAY;
        return RestResponse.status(status, Map.of(
                "error", e.getClass().getSimpleName(),
                "code", e.code(),
                "message", String.valueOf(e.getMessage())));
    }
}
