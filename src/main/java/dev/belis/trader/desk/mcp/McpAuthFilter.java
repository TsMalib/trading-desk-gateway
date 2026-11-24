package dev.belis.trader.desk.mcp;

import dev.belis.trader.desk.config.DeskConfig;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.OptionalLong;

/**
 * Guards the MCP endpoint (the MCP root path and everything below it; other paths pass through).
 *
 * <ul>
 *   <li>Header {@value #HEADER} must equal desk.mcp.apiKey, compared in constant time (SHA-256 of both, then
 *       {@link MessageDigest#isEqual}, so neither content nor length leaks through timing). Otherwise 401.</li>
 *   <li>At most desk.mcp.rateLimitPerMinute requests per minute: one budget for requests with the right key,
 *       and one per client IP for requests without it, so a flood of bad requests cannot starve the bots.
 *       Over the limit: 429 with Retry-After.</li>
 * </ul>
 *
 * A Vert.x route filter rather than a JAX-RS filter, because the MCP endpoint is a Vert.x route, not a REST
 * resource.
 */
@ApplicationScoped
public class McpAuthFilter {

    private static final Logger LOG = Logger.getLogger(McpAuthFilter.class);

    public static final String HEADER = "X-Desk-Key";
    /** Runs before the default filters (CORS etc. use lower priorities). */
    static final int PRIORITY = 1000;
    private static final String AUTHENTICATED_BUCKET = "key";

    private final String mcpPath;
    private final byte[] expectedHash;
    private final RateLimiter limiter;

    public McpAuthFilter(DeskConfig config,
                         @ConfigProperty(name = "quarkus.mcp.server.http.root-path", defaultValue = "/mcp")
                         String mcpPath) {
        String p = mcpPath.endsWith("/") && mcpPath.length() > 1 ? mcpPath.substring(0, mcpPath.length() - 1) : mcpPath;
        this.mcpPath = p.startsWith("/") ? p : "/" + p;
        String key = config.mcp().apiKey();
        if (key == null || key.isBlank()) {
            this.expectedHash = null; // every request is refused
            LOG.warn("desk.mcp.apiKey is empty: every MCP request will be refused");
        } else {
            this.expectedHash = sha256(key);
            if (key.length() < 24) {
                LOG.warnf("desk.mcp.apiKey is only %d characters; use a long random key", key.length());
            }
        }
        this.limiter = new RateLimiter(config.mcp().rateLimitPerMinute(), Duration.ofMinutes(1), Clock.systemUTC());
    }

    void register(@Observes Filters filters) {
        filters.register(this::handle, PRIORITY);
    }

    void handle(RoutingContext rc) {
        if (!isMcpPath(rc.normalizedPath())) {
            rc.next();
            return;
        }
        boolean authorized = keyMatches(rc.request().getHeader(HEADER));
        String bucket = authorized ? AUTHENTICATED_BUCKET : "ip:" + clientAddress(rc);
        OptionalLong retryAfterMillis = limiter.tryAcquire(bucket);
        if (retryAfterMillis.isPresent()) {
            long seconds = Math.max(1, (retryAfterMillis.getAsLong() + 999) / 1000);
            rc.response().putHeader("Retry-After", Long.toString(seconds));
            end(rc, 429, "rate limit exceeded; retry in " + seconds + "s");
            return;
        }
        if (!authorized) {
            LOG.debugf("Refused MCP request from %s: missing or wrong %s", clientAddress(rc), HEADER);
            end(rc, 401, "missing or invalid " + HEADER + " header");
            return;
        }
        rc.next();
    }

    /** Clears the rate limit counters; for tests. */
    public void resetRateLimit() {
        limiter.reset();
    }

    boolean isMcpPath(String path) {
        return path != null && (path.equals(mcpPath) || path.startsWith(mcpPath + "/"));
    }

    boolean keyMatches(String provided) {
        if (expectedHash == null || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(sha256(provided), expectedHash);
    }

    private static String clientAddress(RoutingContext rc) {
        return rc.request().remoteAddress() == null ? "unknown" : rc.request().remoteAddress().hostAddress();
    }

    private static void end(RoutingContext rc, int status, String message) {
        rc.response().setStatusCode(status)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("error", message).encode());
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
