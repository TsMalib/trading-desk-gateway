package dev.belis.trader.desk.broker;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One day's regular (liquid) session.
 *
 * @param date  the trading date in the exchange's time zone
 * @param open  first regular-session open of that date
 * @param close last regular-session close of that date (early on half days)
 */
public record TradingSession(LocalDate date, Instant open, Instant close) {
}
