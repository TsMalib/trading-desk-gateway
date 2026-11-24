package dev.belis.trader.desk.support;

import dev.belis.trader.desk.broker.FakeBroker;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/** A FakeBroker market for proposal tests: 100,000 USD cash, AAPL around 100.20, MSFT 400, TSLA 250. */
public final class Market {

    public static final long AAPL = 265598L;
    public static final String AAPL_ISIN = "US0378331005";
    public static final long MSFT = 272093L;
    public static final long TSLA = 76792991L;

    private Market() {
    }

    public static void open(FakeBroker fake, Instant now) {
        fake.reset();
        fake.setClock(Clock.fixed(now, ZoneOffset.UTC));
        fake.addStock("AAPL", AAPL, AAPL_ISIN, "Technology");
        fake.addStock("MSFT", MSFT, "US5949181045", "Technology");
        fake.addStock("TSLA", TSLA, "US88160R1014", "Consumer, Cyclical");
        quote(fake, "AAPL", "100.20");
        quote(fake, "MSFT", "400.00");
        quote(fake, "TSLA", "250.00");
    }

    /** bid = last - 0.10, ask = last + 0.10. */
    public static void quote(FakeBroker fake, String symbol, String last) {
        BigDecimal l = new BigDecimal(last);
        fake.setQuote(symbol, l.subtract(new BigDecimal("0.10")), l.add(new BigDecimal("0.10")), l);
    }

    public static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }
}
