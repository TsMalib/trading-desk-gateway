package dev.belis.trader.desk.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "broker_order")
public class BrokerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    /** FK to proposal.id. */
    @Column(name = "proposal_id", nullable = false)
    public Long proposalId;

    @Column(name = "ib_order_id", nullable = false)
    public Integer ibOrderId;

    @Column(name = "ib_perm_id")
    public Long ibPermId;

    /** P42-ENTRY / -TARGET / -STOP / -EXIT. */
    @Column(name = "order_ref", nullable = false, unique = true)
    public String orderRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    public OrderRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false)
    public Side action;

    @Column(name = "order_type", nullable = false)
    public String orderType;

    @Column(name = "quantity", nullable = false, precision = 18, scale = 4)
    public BigDecimal quantity;

    @Column(name = "limit_price", precision = 18, scale = 6)
    public BigDecimal limitPrice;

    /** Stop trigger. */
    @Column(name = "aux_price", precision = 18, scale = 6)
    public BigDecimal auxPrice;

    @Column(name = "status", nullable = false)
    public String status;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
