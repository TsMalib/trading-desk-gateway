package dev.belis.trader.desk.mcp;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The MCP endpoint over HTTP: X-Desk-Key (test profile: "test-key") and the 30/min rate limit. */
@QuarkusTest
class McpAuthFilterTest {

    static final String KEY = "test-key";
    static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26",
             "capabilities":{},"clientInfo":{"name":"filter-test","version":"1"}}}""";

    @Inject
    McpAuthFilter filter;

    @BeforeEach
    @AfterEach
    void reset() {
        filter.resetRateLimit();
    }

    static RequestSpecification mcp() {
        return given().contentType("application/json").accept("application/json, text/event-stream");
    }

    static Response initialize(String key) {
        RequestSpecification r = mcp().body(INITIALIZE);
        if (key != null) {
            r = r.header(McpAuthFilter.HEADER, key);
        }
        return r.post("/mcp");
    }

    @Test
    void missingKeyIs401() {
        initialize(null).then().statusCode(401)
                .body("error", equalTo("missing or invalid X-Desk-Key header"));
    }

    @Test
    void wrongKeyIs401() {
        initialize("test-kez").then().statusCode(401);
        initialize("test-key-and-more").then().statusCode(401);
        initialize("").then().statusCode(401);
        initialize("TEST-KEY").then().statusCode(401);
    }

    @Test
    void everyPathUnderTheMcpRootIsGuarded() {
        mcp().body(INITIALIZE).post("/mcp/sse").then().statusCode(401);
        given().get("/mcp/sse").then().statusCode(401);
        given().get("/mcp").then().statusCode(401);
    }

    @Test
    void otherPathsAreNotGuarded() {
        given().get("/q/health/live").then().statusCode(200);
        given().get("/mcpx").then().statusCode(404);
    }

    @Test
    void rightKeyReachesTheMcpServerWhichListsExactlyTheSevenTools() {
        Response init = initialize(KEY);
        init.then().statusCode(200);
        String session = init.header("Mcp-Session-Id");
        assertNotNull(session, "no Mcp-Session-Id: " + init.asString());

        mcp().header(McpAuthFilter.HEADER, KEY).header("Mcp-Session-Id", session)
                .body("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
                .post("/mcp").then().statusCode(202);

        Response list = mcp().header(McpAuthFilter.HEADER, KEY).header("Mcp-Session-Id", session)
                .body("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")
                .post("/mcp");
        list.then().statusCode(200);
        List<String> names = list.jsonPath().getList("result.tools.name");
        assertEquals(Set.of("get_account_summary", "get_positions", "get_quote", "propose_order", "propose_exit",
                "propose_stop_update", "get_proposal_status"), Set.copyOf(names));
        assertEquals(7, names.size());

        // propose_order's schema: the documented arguments, prices as strings
        List<String> required = list.jsonPath().getList(
                "result.tools.find { it.name == 'propose_order' }.inputSchema.required");
        assertEquals(Set.of("symbol", "entryLimit", "stop", "target", "thesis", "earningsSoon", "proposedBy"),
                Set.copyOf(required));
        assertEquals("string", list.jsonPath().getString(
                "result.tools.find { it.name == 'propose_order' }.inputSchema.properties.entryLimit.type"));
        assertEquals("array", list.jsonPath().getString(
                "result.tools.find { it.name == 'propose_order' }.inputSchema.properties.sources.type"));
    }

    @Test
    void thirtyRequestsPerMinuteThen429() {
        for (int i = 1; i <= 30; i++) {
            int status = initialize(KEY).statusCode();
            assertNotEquals(429, status, "request " + i);
            assertNotEquals(401, status, "request " + i);
        }
        Response blocked = initialize(KEY);
        blocked.then().statusCode(429);
        int retryAfter = Integer.parseInt(blocked.header("Retry-After"));
        assertTrue(retryAfter >= 1 && retryAfter <= 60, "Retry-After " + retryAfter);
    }

    @Test
    void badKeyFloodDoesNotUseTheBotsBudget() {
        for (int i = 0; i < 30; i++) {
            initialize("wrong").then().statusCode(401);
        }
        initialize("wrong").then().statusCode(429);
        initialize(KEY).then().statusCode(200);
    }

    @Test
    void keyComparison() {
        assertTrue(filter.keyMatches("test-key"));
        assertFalse(filter.keyMatches("test-ke"));
        assertFalse(filter.keyMatches(null));
        assertTrue(filter.isMcpPath("/mcp"));
        assertTrue(filter.isMcpPath("/mcp/sse"));
        assertFalse(filter.isMcpPath("/mcpx"));
        assertFalse(filter.isMcpPath("/q/health"));
    }
}
