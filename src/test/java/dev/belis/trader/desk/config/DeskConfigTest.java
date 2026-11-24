package dev.belis.trader.desk.config;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class DeskConfigTest {

    @Inject
    DeskConfig config;

    @Test
    void riskDefaults() {
        DeskConfig.Risk r = config.risk();
        assertEquals(0, new BigDecimal("0.5").compareTo(r.riskPerTradePct()));
        assertEquals(0, new BigDecimal("10").compareTo(r.maxPositionPct()));
        assertEquals(5, r.maxOpenPositions());
        assertEquals(3, r.maxEntriesPerDay());
        assertEquals(0, new BigDecimal("2.0").compareTo(r.minRewardRisk()));
        assertEquals(0, new BigDecimal("1.0").compareTo(r.maxDriftPct()));
        assertEquals(0, new BigDecimal("1").compareTo(r.minStopPct()));
        assertEquals(0, new BigDecimal("15").compareTo(r.maxStopPct()));
        assertEquals(LocalTime.of(9, 45), r.windowStart());
        assertEquals(30, r.closeBufferMinutes());
        assertEquals(0, new BigDecimal("2.0").compareTo(r.dailyLossPct()));
        assertEquals(2, r.maxPerIndustry());
        assertEquals(List.of("AAPL", "MSFT"), r.whitelist().orElseThrow());
        assertEquals("America/New_York", DeskConfig.MARKET_ZONE.getId());
    }

    @Test
    void modeAndConnection() {
        assertEquals(DeskConfig.Mode.PAPER, config.mode());
        assertEquals(4002, config.ib().port());
        assertEquals(1L, config.telegram().chatId());
    }

    @Test
    void healthIsUp() {
        given().when().get("/q/health")
                .then().statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks.name", hasItem("desk"))
                .body("checks.name", hasItem("Database connections health check"));
    }
}
