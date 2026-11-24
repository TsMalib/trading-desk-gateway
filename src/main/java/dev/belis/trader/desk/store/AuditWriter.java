package dev.belis.trader.desk.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * Writes audit_log rows. Joins the caller's transaction, so the row commits or rolls back with the
 * state change it describes.
 */
@ApplicationScoped
public class AuditWriter {

    /** Same settings as {@link DbJsonFormatMapper}: what goes to jsonb never depends on the REST mapper. */
    public static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final AuditLogRepository repository;

    public AuditWriter(AuditLogRepository repository) {
        this.repository = repository;
    }

    /** @param details any Jackson-serializable value (a Map, a record, a JsonNode); null for none */
    @Transactional(Transactional.TxType.MANDATORY)
    public AuditLog write(AuditActor actor, String event, Long proposalId, Object details) {
        AuditLog a = new AuditLog();
        a.actor = actor;
        a.event = event;
        a.proposalId = proposalId;
        a.details = details == null ? JsonNodeFactory.instance.objectNode() : JSON.valueToTree(details);
        repository.persist(a);
        return a;
    }

    public static JsonNode toJson(Object value) {
        return JSON.valueToTree(value);
    }
}
