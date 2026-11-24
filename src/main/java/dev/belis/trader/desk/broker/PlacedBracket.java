package dev.belis.trader.desk.broker;

/** IBKR order ids assigned to the three legs of a bracket. */
public record PlacedBracket(int entryOrderId, int targetOrderId, int stopOrderId) {
}
