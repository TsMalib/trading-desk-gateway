package dev.belis.trader.desk.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "position_meta")
public class PositionMeta {

    /** FK to instrument.conid. */
    @Id
    @Column(name = "conid")
    public Long conid;

    /** FK to proposal.id. */
    @Column(name = "proposal_id")
    public Long proposalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false)
    public PositionOrigin origin;

    @Column(name = "entry_price", precision = 18, scale = 6)
    public BigDecimal entryPrice;

    @Column(name = "current_stop", precision = 18, scale = 6)
    public BigDecimal currentStop;

    @Column(name = "target_price", precision = 18, scale = 6)
    public BigDecimal targetPrice;

    @Column(name = "breakeven_done", nullable = false)
    public boolean breakevenDone;

    @Column(name = "opened_at")
    public Instant openedAt;
}
