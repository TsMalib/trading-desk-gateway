package dev.belis.trader.desk.broker;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class DevBrokerResourceTest {

    @Inject
    Broker broker;

    @Inject
    FakeBroker fake;

    @BeforeEach
    void setUp() {
        fake.reset();
        fake.addStock("AAPL", 265598L, "US0378331005", "Technology");
        fake.setQuote("AAPL", new BigDecimal("99.80"), new BigDecimal("99.90"), new BigDecimal("99.85"));
        fake.setPosition(265598L, new BigDecimal("10"), new BigDecimal("90"));
        fake.placeBracket(new BracketOrder(1, 265598L, BigDecimal.ONE, new BigDecimal("95"),
                new BigDecimal("110"), new BigDecimal("90")));
    }

    @AfterEach
    void tearDown() {
        fake.reset();
    }

    @Test
    void testProfileUsesTheFakeBroker() {
        org.junit.jupiter.api.Assertions.assertInstanceOf(FakeBroker.class,
                io.quarkus.arc.ClientProxy.unwrap(broker));
    }

    @Test
    void snapshot() {
        given().when().get("/dev/broker/snapshot")
                .then().statusCode(200)
                .body("mode", equalTo("paper"))
                .body("connection", equalTo("CONNECTED"))
                .body("summary.account", equalTo(FakeBroker.ACCOUNT))
                .body("summary.baseCurrency", equalTo("USD"))
                .body("positions", hasSize(1))
                .body("positions[0].symbol", equalTo("AAPL"))
                .body("openOrders", hasSize(3))
                .body("openOrders.orderRef", hasItem("P1-STOP"));
    }

    @Test
    void contractAndQuote() {
        given().when().get("/dev/broker/contract/aapl")
                .then().statusCode(200)
                .body("conid", equalTo(265598))
                .body("isin", equalTo("US0378331005"))
                .body("industry", equalTo("Technology"));
        given().when().get("/dev/broker/quote/AAPL")
                .then().statusCode(200)
                .body("bid", equalTo(99.80f))
                .body("delayed", equalTo(false));
    }

    @Test
    void unknownSymbolIs502WithIbkrCode() {
        given().when().get("/dev/broker/contract/NOPE")
                .then().statusCode(502)
                .body("code", equalTo(200));
    }

    @Test
    void disconnectedIs503AndNotReady() {
        fake.setConnectionState(ConnectionState.DISCONNECTED);
        given().when().get("/dev/broker/snapshot").then().statusCode(503);
        given().when().get("/q/health/ready")
                .then().statusCode(503)
                .body("checks.find { it.name == 'broker' }.data.state", equalTo("DISCONNECTED"));
    }
}
