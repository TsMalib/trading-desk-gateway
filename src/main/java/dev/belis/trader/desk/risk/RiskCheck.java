package dev.belis.trader.desk.risk;

/**
 * One check: what was measured and what it was compared with. Values are display strings
 * (BigDecimal plain strings, times in America/New_York) so they can go to proposal.risk_result as they are.
 */
public record RiskCheck(RiskRule rule, boolean passed, String actual, String limit) {

    public String name() {
        return rule.name();
    }
}
