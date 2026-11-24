package dev.belis.trader.desk.broker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** IbkrBroker against {@link FakeTwsServer}: handshake, reconnect with backoff, events, error routing. */
class IbkrBrokerConnectionTest {

    private FakeTwsServer tws;
    private IbkrBroker broker;
    private final List<BrokerReconnected> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        tws = new FakeTwsServer();
        broker = new IbkrBroker(settings(tws.port()), events::add, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() throws Exception {
        broker.stop();
        tws.close();
    }

    private static IbkrBroker.Settings settings(int port) {
        return new IbkrBroker.Settings("127.0.0.1", port, 7, 1, Duration.ofSeconds(3), Duration.ofSeconds(3),
                Duration.ofSeconds(1), ZoneId.of("America/New_York"));
    }

    @Test
    void connectsAndHandsOutOrderIdsFromNextValidId() {
        tws.setNextValidId(500);
        broker.start();
        await("connected", broker::isConnected);

        assertEquals(500, broker.allocateOrderId());
        assertEquals(501, broker.allocateOrderId());
        assertTrue(events.isEmpty(), "first connect is not a reconnect");
    }

    @Test
    void reconnectsAfterGatewayRestartAndFiresEvent() throws Exception {
        broker.start();
        await("connected", broker::isConnected);

        tws.setAcceptConnections(false);
        tws.dropClients();
        await("disconnected", () -> broker.connectionState() == ConnectionState.DISCONNECTED);
        assertThrows(BrokerUnavailableException.class, broker::accountSummary);

        tws.setAcceptConnections(true);
        await("reconnected", broker::isConnected);
        await("event", () -> events.size() == 1);
        assertEquals(BrokerReconnected.Reason.SOCKET_RECONNECTED, events.get(0).reason());
        assertTrue(tws.connections() >= 2);

        // and it keeps working after the reconnect
        assertEquals("EUR", broker.accountSummary().baseCurrency());
    }

    @Test
    void ibServerLossAndRestoreChangeStateAndFireEvent() throws Exception {
        broker.start();
        await("connected", broker::isConnected);

        tws.sendError(-1, 1100, "Connectivity between IB and Trader Workstation has been lost.");
        await("server lost", () -> broker.connectionState() == ConnectionState.IB_SERVER_LOST);

        tws.sendError(-1, 1102, "Connectivity between IB and Trader Workstation has been restored - data maintained.");
        await("restored", broker::isConnected);
        await("event", () -> events.size() == 1);
        assertEquals(BrokerReconnected.Reason.IB_SERVER_RESTORED, events.get(0).reason());
    }

    @Test
    void accountSummaryIsMappedToBaseCurrencyRecord() {
        broker.start();
        await("connected", broker::isConnected);

        AccountSummary s = broker.accountSummary();

        assertEquals("DU123456", s.account());
        assertEquals("EUR", s.baseCurrency());
        assertEquals(0, new BigDecimal("100123.45").compareTo(s.netLiquidation()));
        assertEquals(0, new BigDecimal("-250.5").compareTo(s.cash()));
    }

    @Test
    void requestsFailFastWhenNotConnected() {
        assertEquals(ConnectionState.DISCONNECTED, broker.connectionState());
        assertThrows(BrokerUnavailableException.class, broker::positions);
        assertThrows(BrokerUnavailableException.class, () -> broker.quote("AAPL"));
        assertThrows(BrokerUnavailableException.class, broker::allocateOrderId);
    }

    @Test
    void writeMethodsAreNotImplementedYet() {
        assertThrows(UnsupportedOperationException.class, () -> broker.cancel("P1-STOP"));
        assertThrows(UnsupportedOperationException.class,
                () -> broker.marketSell(1, BigDecimal.ONE, "P1-EXIT"));
        assertThrows(UnsupportedOperationException.class, () -> broker.modifyStop("P1-STOP", BigDecimal.TEN));
        assertThrows(UnsupportedOperationException.class, () -> broker.placeBracket(
                new BracketOrder(1, 1, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.valueOf(12), BigDecimal.valueOf(9))));
    }

    @Test
    void backoffDoublesUpToTheMaximum() {
        IbkrBroker b = new IbkrBroker(new IbkrBroker.Settings("h", 1, 1, 1, Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(60), ZoneId.of("UTC")), e -> { }, Clock.systemUTC());
        assertEquals(List.of(1L, 2L, 4L, 8L, 16L, 32L, 60L, 60L),
                java.util.stream.IntStream.of(0, 1, 2, 3, 4, 5, 6, 30)
                        .mapToObj(i -> b.backoff(i).toSeconds()).toList());
    }

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
        }
        fail("timed out waiting for " + what);
    }
}
