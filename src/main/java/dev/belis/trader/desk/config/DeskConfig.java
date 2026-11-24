package dev.belis.trader.desk.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Typed configuration under the {@code desk.*} prefix.
 * Secrets are wired to environment variables in application.properties; see .env.example.
 */
@ConfigMapping(prefix = "desk")
public interface DeskConfig {

    /** Market hours are always evaluated in this zone. */
    ZoneId MARKET_ZONE = ZoneId.of("America/New_York");

    enum Mode { PAPER, LIVE }

    /** paper | live. Shown in every Telegram message. */
    Mode mode();

    /**
     * Broker implementation: ibkr (TWS API) or fake (in-memory, tests). Build-time: it selects the
     * CDI bean, so changing it needs a rebuild. Tests run with fake.
     */
    @WithDefault("ibkr")
    String broker();

    Ib ib();

    Telegram telegram();

    Mcp mcp();

    Risk risk();

    interface Ib {
        String host();

        int port();

        @WithName("clientId")
        int clientId();

        /** reqMarketDataType: 1 live, 2 frozen, 3 delayed, 4 delayed-frozen. Delayed quotes are flagged. */
        @WithName("marketDataType")
        @WithDefault("1")
        int marketDataType();

        /** How long to wait for IBKR to answer one request. */
        @WithName("requestTimeout")
        @WithDefault("15s")
        Duration requestTimeout();

        /** How long to wait for nextValidId after the socket connects. */
        @WithName("connectTimeout")
        @WithDefault("10s")
        Duration connectTimeout();

        /** Reconnect backoff doubles from 1s up to this. */
        @WithName("reconnectMaxBackoff")
        @WithDefault("60s")
        Duration reconnectMaxBackoff();
    }

    interface Telegram {
        String token();

        @WithName("chatId")
        long chatId();

        /** The only Telegram user allowed to approve proposals. */
        @WithName("userId")
        long userId();
    }

    interface Mcp {
        @WithName("apiKey")
        String apiKey();
    }

    interface Risk {
        /** Percent of equity risked per trade (entry - stop) * qty. */
        @WithName("riskPerTradePct")
        @WithDefault("0.5")
        BigDecimal riskPerTradePct();

        /** Max position value as percent of equity. */
        @WithName("maxPositionPct")
        @WithDefault("10")
        BigDecimal maxPositionPct();

        @WithName("maxOpenPositions")
        @WithDefault("5")
        int maxOpenPositions();

        @WithName("maxEntriesPerDay")
        @WithDefault("3")
        int maxEntriesPerDay();

        @WithName("minRewardRisk")
        @WithDefault("2.0")
        BigDecimal minRewardRisk();

        /** Max drift in percent between proposed entry limit and the live quote. */
        @WithName("maxDriftPct")
        @WithDefault("1.0")
        BigDecimal maxDriftPct();

        /** Stop distance below entry, percent: lower bound. */
        @WithName("minStopPct")
        @WithDefault("1")
        BigDecimal minStopPct();

        /** Stop distance below entry, percent: upper bound. */
        @WithName("maxStopPct")
        @WithDefault("15")
        BigDecimal maxStopPct();

        /** Entry window opens at this local time in {@link DeskConfig#MARKET_ZONE}. */
        @WithName("windowStart")
        @WithDefault("09:45")
        LocalTime windowStart();

        /** Entry window closes this many minutes before the day's actual close (from IBKR trading hours). */
        @WithName("closeBufferMinutes")
        @WithDefault("30")
        int closeBufferMinutes();

        /** Kill switch trips when the day's loss reaches this percent of start-of-day equity. */
        @WithName("dailyLossPct")
        @WithDefault("2.0")
        BigDecimal dailyLossPct();

        @WithName("maxPerIndustry")
        @WithDefault("2")
        int maxPerIndustry();

        /** Symbols that may be proposed. Comma separated in properties/env. */
        Optional<List<String>> whitelist();
    }
}
