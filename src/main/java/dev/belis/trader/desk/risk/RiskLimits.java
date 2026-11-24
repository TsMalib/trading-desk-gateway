package dev.belis.trader.desk.risk;

import dev.belis.trader.desk.config.DeskConfig;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Objects;

/**
 * The numeric {@code desk.risk.*} limits as a plain value, so the engine has no dependency on CDI or config.
 * Percent values are percents (0.5 means 0.5 %), not fractions. The whitelist travels in {@link RiskContext}.
 */
public record RiskLimits(BigDecimal riskPerTradePct, BigDecimal maxPositionPct, int maxOpenPositions,
                         int maxEntriesPerDay, BigDecimal minRewardRisk, BigDecimal maxDriftPct,
                         BigDecimal minStopPct, BigDecimal maxStopPct, LocalTime windowStart,
                         int closeBufferMinutes, BigDecimal dailyLossPct, int maxPerIndustry) {

    public RiskLimits {
        Objects.requireNonNull(riskPerTradePct, "riskPerTradePct");
        Objects.requireNonNull(maxPositionPct, "maxPositionPct");
        Objects.requireNonNull(minRewardRisk, "minRewardRisk");
        Objects.requireNonNull(maxDriftPct, "maxDriftPct");
        Objects.requireNonNull(minStopPct, "minStopPct");
        Objects.requireNonNull(maxStopPct, "maxStopPct");
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(dailyLossPct, "dailyLossPct");
    }

    public static RiskLimits from(DeskConfig.Risk r) {
        return new RiskLimits(r.riskPerTradePct(), r.maxPositionPct(), r.maxOpenPositions(), r.maxEntriesPerDay(),
                r.minRewardRisk(), r.maxDriftPct(), r.minStopPct(), r.maxStopPct(), r.windowStart(),
                r.closeBufferMinutes(), r.dailyLossPct(), r.maxPerIndustry());
    }
}
