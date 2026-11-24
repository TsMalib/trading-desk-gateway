package dev.belis.trader.desk.broker;

import dev.belis.trader.desk.store.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeBrokerTest {

    private static final long AAPL = 265598L;

    private FakeBroker broker;

    @BeforeEach
    void setUp() {
        broker = new FakeBroker();
        broker.addStock("AAPL", AAPL, "US0378331005", "Technology");
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void readsReflectState() {
        broker.setQuote("AAPL", d("189.90"), d("190.10"), d("190.00"));
        broker.setPosition(AAPL, d("10"), d("150"));

        assertEquals(ConnectionState.CONNECTED, broker.connectionState());
        assertEquals("US0378331005", broker.contract("aapl").isin());
        assertTrue(broker.contract("AAPL").todaySession().isPresent());
        assertEquals(d("190.10"), broker.quote("AAPL").ask());
        assertEquals(List.of(new BrokerPosition(FakeBroker.ACCOUNT, AAPL, "AAPL", "USD", d("10"), d("150"))),
                broker.positions());
        // 100000 cash + 10 * 190 last
        assertEquals(0, d("101900").compareTo(broker.accountSummary().netLiquidation()));
        assertTrue(broker.openOrders().isEmpty());
    }

    @Test
    void unknownSymbolAndMissingQuoteFail() {
        assertEquals(200, assertThrows(BrokerException.class, () -> broker.contract("NOPE")).code());
        assertThrows(BrokerException.class, () -> broker.quote("AAPL"));
    }

    @Test
    void disconnectedBrokerRefusesRequests() {
        broker.setConnectionState(ConnectionState.DISCONNECTED);
        assertThrows(BrokerUnavailableException.class, broker::accountSummary);
        assertThrows(BrokerUnavailableException.class, broker::openOrders);
    }

    @Test
    void bracketFillsEntryThenTargetAndCancelsStop() {
        broker.setQuote("AAPL", d("100.50"), d("100.70"), d("100.60"));
        PlacedBracket placed = broker.placeBracket(new BracketOrder(42, AAPL, d("10"), d("100"), d("110"), d("95")));

        Map<String, String> status = statusByRef();
        assertEquals("Submitted", status.get("P42-ENTRY"));
        assertEquals("PreSubmitted", status.get("P42-TARGET"));
        assertEquals("PreSubmitted", status.get("P42-STOP"));
        assertEquals(placed.entryOrderId(), broker.openOrders().stream()
                .filter(o -> o.orderRef().equals("P42-STOP")).findFirst().orElseThrow().parentId());

        broker.setQuote("AAPL", d("99.80"), d("99.90"), d("99.85")); // ask crosses the entry limit
        assertEquals(List.of(new BrokerPosition(FakeBroker.ACCOUNT, AAPL, "AAPL", "USD", d("10"), d("100.000000"))),
                broker.positions()); // (10 * 99.90 + 1.00) / 10
        assertEquals(Map.of("P42-TARGET", "Submitted", "P42-STOP", "Submitted"), statusByRef());

        broker.setQuote("AAPL", d("110.20"), d("110.30"), d("110.25"));
        assertTrue(broker.positions().isEmpty());
        assertTrue(broker.openOrders().isEmpty(), "stop is cancelled by OCA");

        List<BrokerExecution> fills = broker.executionsSince(Instant.EPOCH);
        assertEquals(2, fills.size());
        assertEquals(Side.BUY, fills.get(0).side());
        assertNull(fills.get(0).realizedPnl());
        assertEquals(Side.SELL, fills.get(1).side());
        assertEquals("P42-TARGET", fills.get(1).orderRef());
        // 10 * (110.20 - 100.00) - 1.00
        assertEquals(0, d("101.00").compareTo(fills.get(1).realizedPnl()));
        // 100000 - 999 - 1 + 1102 - 1
        assertEquals(0, d("100101.00").compareTo(broker.accountSummary().cash()));
    }

    @Test
    void stopTriggersOnLastAndFillsAtBid() {
        broker.placeBracket(new BracketOrder(7, AAPL, d("5"), d("100"), d("110"), d("95")));
        broker.fill("P7-ENTRY", d("100"));

        broker.setQuote("AAPL", d("94.50"), d("94.70"), d("94.90"));

        BrokerExecution stop = broker.executionsSince(Instant.EPOCH).get(1);
        assertEquals("P7-STOP", stop.orderRef());
        assertEquals(d("94.50"), stop.price());
        assertTrue(broker.positions().isEmpty());
        assertTrue(broker.openOrders().isEmpty());
    }

    @Test
    void stopsOnlyMoveUp() {
        broker.placeBracket(new BracketOrder(8, AAPL, d("5"), d("100"), d("110"), d("95")));
        broker.fill("P8-ENTRY", d("100"));

        broker.modifyStop("P8-STOP", d("100"));
        assertEquals(d("100"), broker.openOrders().stream().filter(o -> o.orderRef().equals("P8-STOP"))
                .findFirst().orElseThrow().auxPrice());
        assertThrows(IllegalArgumentException.class, () -> broker.modifyStop("P8-STOP", d("99")));
    }

    @Test
    void marketSellNeverExceedsPosition() {
        broker.setPosition(AAPL, d("10"), d("100"));
        broker.setQuote("AAPL", d("105"), d("105.10"), d("105.05"));

        assertThrows(IllegalArgumentException.class, () -> broker.marketSell(AAPL, d("11"), "P9-EXIT"));
        broker.marketSell(AAPL, d("4"), "P9-EXIT");

        assertEquals(d("6"), broker.positions().get(0).quantity());
        assertEquals(d("105"), broker.executionsSince(Instant.EPOCH).get(0).price());
    }

    @Test
    void orderRefsAreUnique() {
        broker.placeBracket(new BracketOrder(5, AAPL, d("1"), d("100"), d("110"), d("95")));
        assertThrows(BrokerException.class,
                () -> broker.placeBracket(new BracketOrder(5, AAPL, d("1"), d("100"), d("110"), d("95"))));
    }

    @Test
    void cancellingTheParentCancelsChildren() {
        broker.placeBracket(new BracketOrder(6, AAPL, d("1"), d("100"), d("110"), d("95")));
        broker.cancel("P6-ENTRY");
        assertTrue(broker.openOrders().isEmpty());
    }

    @Test
    void invalidBracketIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> broker.placeBracket(new BracketOrder(3, AAPL, d("1"), d("100"), d("110"), d("101"))));
    }

    private Map<String, String> statusByRef() {
        return broker.openOrders().stream()
                .collect(Collectors.toMap(BrokerOpenOrder::orderRef, BrokerOpenOrder::status));
    }
}
