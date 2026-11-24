package dev.belis.trader.desk.broker;

import java.util.Optional;

/**
 * Stock contract details.
 *
 * @param isin         from ContractDetails.secIdList; always present (lookup fails without it)
 * @param name         long name, may be null
 * @param industry     ContractDetails.industry, may be null
 * @param timeZoneId   exchange time zone as IBKR reports it, e.g. US/Eastern
 * @param todaySession today's regular session parsed from liquidHours; empty on a market holiday or weekend
 */
public record ContractInfo(long conid, String symbol, String primaryExchange, String currency, String isin,
                           String name, String industry, String timeZoneId,
                           Optional<TradingSession> todaySession) {
}
