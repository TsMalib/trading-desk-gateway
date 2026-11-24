package dev.belis.trader.desk.risk;

import java.math.BigDecimal;

/**
 * The values of an ENTRY proposal that the risk engine checks. Prices are in the trade currency.
 *
 * @param proposalId null when the proposal is not stored yet; when set, a pending entry with the same id
 *                   in the context is this proposal and is ignored (the re-check at approval time)
 * @param industry   IBKR ContractDetails.industry; null or blank counts as its own "UNKNOWN" industry
 * @param currency   trade currency, e.g. USD
 */
public record EntryProposal(Long proposalId, long conid, String symbol, String industry, String currency,
                            BigDecimal entryLimit, BigDecimal stopPrice, BigDecimal targetPrice) {
}
