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

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "proposal")
public class Proposal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    /** FK to instrument.conid. */
    @Column(name = "conid")
    public Long conid;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    public ProposalKind kind;

    @Column(name = "status", nullable = false)
    public String status;

    @Column(name = "entry_limit", precision = 18, scale = 6)
    public BigDecimal entryLimit;

    @Column(name = "stop_price", precision = 18, scale = 6)
    public BigDecimal stopPrice;

    @Column(name = "target_price", precision = 18, scale = 6)
    public BigDecimal targetPrice;

    /** Computed by the risk engine. */
    @Column(name = "quantity", precision = 18, scale = 4)
    public BigDecimal quantity;

    @Column(name = "risk_eur", precision = 18, scale = 2)
    public BigDecimal riskEur;

    @Column(name = "reward_risk", precision = 8, scale = 2)
    public BigDecimal rewardRisk;

    @Column(name = "thesis")
    public String thesis;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sources", nullable = false)
    public JsonNode sources = JsonNodeFactory.instance.arrayNode();

    @Column(name = "proposed_by", nullable = false)
    public String proposedBy;

    /** Every check with pass/fail and values. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "risk_result")
    public JsonNode riskResult;

    /** e.g. 30-day rule. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "warnings", nullable = false)
    public JsonNode warnings = JsonNodeFactory.instance.arrayNode();

    @Column(name = "telegram_msg_id")
    public Long telegramMsgId;

    @Column(name = "expires_at")
    public Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "decided_at")
    public Instant decidedAt;
}
