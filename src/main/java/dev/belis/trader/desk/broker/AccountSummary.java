package dev.belis.trader.desk.broker;

import java.math.BigDecimal;

/**
 * Account values in the account's base currency.
 *
 * @param account        IBKR account id (DU... for paper)
 * @param baseCurrency   ISO code of the base currency, e.g. EUR
 * @param netLiquidation NetLiquidation in base currency
 * @param cash           TotalCashValue in base currency
 */
public record AccountSummary(String account, String baseCurrency, BigDecimal netLiquidation, BigDecimal cash) {
}
