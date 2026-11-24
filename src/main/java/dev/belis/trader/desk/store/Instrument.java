package dev.belis.trader.desk.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "instrument")
public class Instrument {

    /** IBKR contract id. */
    @Id
    @Column(name = "conid")
    public Long conid;

    @Column(name = "symbol", nullable = false)
    public String symbol;

    @Column(name = "primary_exch", nullable = false)
    public String primaryExch;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    public String currency;

    /** Needed for the 30-day rule and tax. */
    @Column(name = "isin", nullable = false)
    public String isin;

    @Column(name = "name")
    public String name;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
