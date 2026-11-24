package dev.belis.trader.desk.broker;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Read methods against a running IB Gateway logged in to the PAPER account. Skipped unless
 * {@code IB_PAPER_TEST=true}. Refuses to run against the live port.
 *
 * <pre>
 *   IB_PAPER_TEST=true mvn test -Dtest=IbkrBrokerPaperTest
 * </pre>
 * Optional: IB_HOST (127.0.0.1), IB_PORT (4002), IB_TEST_CLIENT_ID (91, so it does not clash with a
 * running dev instance), IB_MARKET_DATA_TYPE (1; use 3 without live data subscriptions), IB_TEST_SYMBOL (AAPL).
 */
@EnabledIfEnvironmentVariable(named = "IB_PAPER_TEST", matches = "true")
class IbkrBrokerPaperTest {

    private static IbkrBroker broker;

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v;
    }

    @BeforeAll
    static void connect() throws InterruptedException {
        int port = Integer.parseInt(env("IB_PORT", "4002"));
        if (port == 4001 || port == 7496) {
            throw new IllegalStateException("IB_PORT " + port + " is a LIVE port; this test only runs on paper");
        }
        broker = new IbkrBroker(new IbkrBroker.Settings(env("IB_HOST", "127.0.0.1"), port,
                Integer.parseInt(env("IB_TEST_CLIENT_ID", "91")), Integer.parseInt(env("IB_MARKET_DATA_TYPE", "1")),
                Duration.ofSeconds(20), Duration.ofSeconds(10), Duration.ofSeconds(5), ZoneId.systemDefault()),
                e -> { }, Clock.systemUTC());
        broker.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!broker.isConnected() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertTrue(broker.isConnected(), "could not connect to IB Gateway on port " + port);
    }

    @AfterAll
    static void disconnect() {
        if (broker != null) {
            broker.stop();
        }
    }

    @Test
    void accountSummary() {
        AccountSummary s = broker.accountSummary();
        System.out.println(s);
        assertTrue(s.account().startsWith("D"), "paper accounts start with D: " + s.account());
        assertEquals(3, s.baseCurrency().length());
        assertTrue(s.netLiquidation().signum() > 0);
        assertNotNull(s.cash());
    }

    @Test
    void positions() {
        List<BrokerPosition> positions = broker.positions();
        positions.forEach(System.out::println);
        positions.forEach(p -> assertTrue(p.quantity().signum() != 0));
    }

    @Test
    void openOrders() {
        broker.openOrders().forEach(System.out::println);
    }

    @Test
    void executionsOfTheLastWeek() {
        List<BrokerExecution> executions = broker.executionsSince(Instant.now().minus(Duration.ofDays(7)));
        executions.forEach(System.out::println);
        executions.forEach(e -> {
            assertNotNull(e.executedAt());
            assertNotNull(e.price());
        });
    }

    @Test
    void contract() {
        ContractInfo c = broker.contract(env("IB_TEST_SYMBOL", "AAPL"));
        System.out.println(c);
        assertTrue(c.conid() > 0);
        assertEquals("USD", c.currency());
        assertEquals(12, c.isin().length());
        assertNotNull(c.primaryExchange());
        c.todaySession().ifPresent(s -> assertTrue(s.open().isBefore(s.close())));
    }

    @Test
    void quote() {
        Quote q = broker.quote(env("IB_TEST_SYMBOL", "AAPL"));
        System.out.println(q);
        assertTrue(q.last() != null || q.close() != null || q.bid() != null, "empty quote: " + q);
    }
}
