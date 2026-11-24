package dev.belis.trader.desk.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "setting")
public class Setting {

    /** kill_switch, start_of_day_equity, ... */
    @Id
    @Column(name = "key")
    public String key;

    @Column(name = "value", nullable = false)
    public String value;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
