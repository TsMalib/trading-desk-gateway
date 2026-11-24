package dev.belis.trader.desk.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @CreationTimestamp
    @Column(name = "at", nullable = false, updatable = false)
    public Instant at;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor", nullable = false)
    public AuditActor actor;

    @Column(name = "event", nullable = false)
    public String event;

    @Column(name = "proposal_id")
    public Long proposalId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", nullable = false)
    public JsonNode details = JsonNodeFactory.instance.objectNode();
}
