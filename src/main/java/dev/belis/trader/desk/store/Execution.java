package dev.belis.trader.desk.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "execution")
public class Execution {

    @Id
    @Column(name = "ib_exec_id")
    public String ibExecId;

    /** FK to broker_order.id; null for EXTERNAL trades. */
    @Column(name = "broker_order_id")
    public Long brokerOrderId;

    /** FK to instrument.conid. */
    @Column(name = "conid", nullable = false)
    public Long conid;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false)
    public Side side;

    @Column(name = "quantity", nullable = false, precision = 18, scale = 4)
    public BigDecimal quantity;

    @Column(name = "price", nullable = false, precision = 18, scale = 6)
    public BigDecimal price;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    public String currency;

    @Column(name = "commission", precision = 18, scale = 6)
    public BigDecimal commission;

    /** From the IBKR commission report; check its currency on the first fill. */
    @Column(name = "realized_pnl", precision = 18, scale = 6)
    public BigDecimal realizedPnl;

    @Column(name = "executed_at", nullable = false)
    public Instant executedAt;
}
