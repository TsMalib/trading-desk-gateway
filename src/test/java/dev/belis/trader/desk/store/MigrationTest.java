package dev.belis.trader.desk.store;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs against a real Postgres started by Quarkus Dev Services (Testcontainers).
 * Hibernate runs with schema-management=validate, so app startup alone proves
 * every entity matches the migrated schema; this test checks the migration
 * itself and a JSON/BigDecimal round trip.
 */
@QuarkusTest
class MigrationTest {

    @Inject
    Flyway flyway;

    @Inject
    AgroalDataSource dataSource;

    @Inject
    InstrumentRepository instruments;

    @Inject
    ProposalRepository proposals;

    @Inject
    AuditLogRepository auditLogs;

    @Test
    void v1MigrationIsApplied() {
        MigrationInfo current = flyway.info().current();
        assertNotNull(current, "no migration applied");
        assertEquals("1", current.getVersion().getVersion());
        assertTrue(current.getState().isApplied());
        assertEquals(0, flyway.info().pending().length);
    }

    @Test
    void allTablesExist() throws Exception {
        List<String> tables = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             ResultSet rs = c.createStatement().executeQuery(
                     "select table_name from information_schema.tables "
                             + "where table_schema = 'public' and table_name <> 'flyway_schema_history' "
                             + "order by table_name")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        assertEquals(List.of("audit_log", "broker_order", "execution", "instrument",
                "position_meta", "proposal", "setting"), tables);
    }

    @Test
    @Transactional
    void entitiesRoundTrip() {
        Instrument i = new Instrument();
        i.conid = 265598L;
        i.symbol = "AAPL";
        i.primaryExch = "NASDAQ";
        i.currency = "USD";
        i.isin = "US0378331005";
        instruments.persist(i);

        Proposal p = new Proposal();
        p.conid = i.conid;
        p.kind = ProposalKind.ENTRY;
        p.status = "TEST";
        p.entryLimit = new BigDecimal("187.123456");
        p.proposedBy = "test";
        p.sources = JsonNodeFactory.instance.arrayNode().add("https://example.com");
        p.expiresAt = Instant.parse("2026-01-02T15:00:00Z");
        proposals.persist(p);

        AuditLog a = new AuditLog();
        a.actor = AuditActor.SYSTEM;
        a.event = "TEST";
        a.proposalId = p.id;
        a.details = JsonNodeFactory.instance.objectNode().put("k", "v");
        auditLogs.persist(a);

        proposals.flush();
        proposals.getEntityManager().clear();

        Proposal loaded = proposals.findById(p.id);
        assertEquals(0, new BigDecimal("187.123456").compareTo(loaded.entryLimit));
        assertEquals("https://example.com", loaded.sources.get(0).asText());
        assertEquals(0, loaded.warnings.size());
        assertNotNull(loaded.createdAt);
        assertEquals(Instant.parse("2026-01-02T15:00:00Z"), loaded.expiresAt);
        assertEquals("v", auditLogs.findById(a.id).details.get("k").asText());
    }
}
