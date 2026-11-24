package dev.belis.trader.desk.risk;

/** The named checks of an entry. Every evaluation reports every rule, in this order. */
public enum RiskRule {
    KILL_SWITCH,
    DAILY_LOSS,
    WHITELIST,
    EARNINGS,
    PRICE_ORDER,
    STOP_DISTANCE,
    REWARD_RISK,
    QUOTE,
    DRIFT,
    ENTRY_WINDOW,
    FX_RATE,
    MIN_QUANTITY,
    POSITION_SIZE,
    CASH,
    DUPLICATE_SYMBOL,
    MAX_OPEN_POSITIONS,
    MAX_PER_INDUSTRY,
    MAX_ENTRIES_PER_DAY
}
