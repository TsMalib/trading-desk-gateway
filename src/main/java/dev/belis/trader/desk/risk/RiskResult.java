package dev.belis.trader.desk.risk;

import java.math.BigDecimal;
import java.util.List;

/**
 * Outcome of an entry evaluation.
 *
 * @param checks     every rule, passed or not, in {@link RiskRule} order
 * @param quantity   whole shares; 0 when no valid size exists
 * @param riskEur    quantity * (entry - stop) in the base currency, 2 decimals; null when the prices are invalid
 * @param rewardRisk (target - entry) / (entry - stop) truncated to 2 decimals; null when the prices are invalid
 */
public record RiskResult(List<RiskCheck> checks, BigDecimal quantity, BigDecimal riskEur, BigDecimal rewardRisk) {

    public RiskResult {
        checks = List.copyOf(checks);
    }

    public boolean passed() {
        return checks.stream().allMatch(RiskCheck::passed);
    }

    public List<RiskCheck> failures() {
        return checks.stream().filter(c -> !c.passed()).toList();
    }

    public RiskCheck check(RiskRule rule) {
        return checks.stream().filter(c -> c.rule() == rule).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no check " + rule));
    }
}
