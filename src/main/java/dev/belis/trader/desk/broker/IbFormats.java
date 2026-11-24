package dev.belis.trader.desk.broker;

import com.ib.client.Decimal;
import dev.belis.trader.desk.config.DeskConfig;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;

/** Parsing of IBKR's string formats and sentinel values. Pure functions, no IBKR connection needed. */
final class IbFormats {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HHmm");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss");
    private static final DateTimeFormatter UTC_DASHED = DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss");

    /** IBKR's legacy / abbreviated zone names that java.time does not map to a DST-aware zone. */
    private static final Map<String, String> ZONE_ALIASES = Map.of(
            "EST", "America/New_York",
            "EDT", "America/New_York",
            "EST5EDT", "America/New_York",
            "CST", "America/Chicago",
            "CDT", "America/Chicago",
            "MET", "Europe/Berlin",
            "CET", "Europe/Berlin",
            "GMT", "Europe/London");

    private IbFormats() {
    }

    // ---- time zones ---------------------------------------------------------

    /** Resolves an IBKR time zone id (e.g. "US/Eastern"); unknown or blank ids fall back to New York. */
    static ZoneId zone(String ibZoneId) {
        if (ibZoneId == null || ibZoneId.isBlank()) {
            return DeskConfig.MARKET_ZONE;
        }
        String id = ZONE_ALIASES.getOrDefault(ibZoneId.trim(), ibZoneId.trim());
        try {
            return ZoneId.of(id);
        } catch (RuntimeException e) {
            return DeskConfig.MARKET_ZONE;
        }
    }

    // ---- liquidHours ----------------------------------------------------------

    /**
     * The regular session on {@code date} from ContractDetails.liquidHours.
     *
     * <p>Handles both formats IBKR has used:
     * <ul>
     *   <li>{@code 20261124:0930-20261124:1600;20261126:CLOSED}</li>
     *   <li>{@code 20261124:0930-1600;20261126:CLOSED} (older, may hold comma separated ranges)</li>
     * </ul>
     * Several ranges on the same date (lunch breaks) give earliest open and latest close.
     *
     * @return empty if the date is CLOSED or not listed
     */
    static Optional<TradingSession> sessionOn(String liquidHours, ZoneId zone, LocalDate date) {
        if (liquidHours == null || liquidHours.isBlank()) {
            return Optional.empty();
        }
        LocalDateTime open = null;
        LocalDateTime close = null;
        for (String rawSegment : liquidHours.split(";")) {
            String segment = rawSegment.trim();
            int colon = segment.indexOf(':');
            if (colon != 8) {
                continue;
            }
            LocalDate segmentDate = LocalDate.parse(segment.substring(0, 8), DATE);
            if (!segmentDate.equals(date)) {
                continue;
            }
            String rest = segment.substring(colon + 1).trim();
            if (rest.equalsIgnoreCase("CLOSED")) {
                continue;
            }
            for (String range : rest.split(",")) {
                String[] ends = range.trim().split("-");
                if (ends.length != 2) {
                    throw new IllegalArgumentException("unparseable liquidHours range: " + range);
                }
                LocalDateTime start = LocalDateTime.of(segmentDate, LocalTime.parse(ends[0].trim(), HHMM));
                LocalDateTime end = parseEnd(ends[1].trim(), segmentDate);
                if (open == null || start.isBefore(open)) {
                    open = start;
                }
                if (close == null || end.isAfter(close)) {
                    close = end;
                }
            }
        }
        if (open == null) {
            return Optional.empty();
        }
        return Optional.of(new TradingSession(date, open.atZone(zone).toInstant(), close.atZone(zone).toInstant()));
    }

    private static LocalDateTime parseEnd(String end, LocalDate defaultDate) {
        int colon = end.indexOf(':');
        if (colon < 0) {
            return LocalDateTime.of(defaultDate, LocalTime.parse(end, HHMM));
        }
        return LocalDateTime.of(LocalDate.parse(end.substring(0, colon), DATE),
                LocalTime.parse(end.substring(colon + 1), HHMM));
    }

    // ---- execution times ------------------------------------------------------

    /**
     * Parses Execution.time(): {@code 20261124 10:30:00 US/Eastern}, {@code 20261124  10:30:00}
     * (no zone: {@code fallbackZone}, the Gateway's login zone) or {@code 20261124-15:30:00} (UTC).
     */
    static Instant executionTime(String raw, ZoneId fallbackZone) {
        String s = raw.trim();
        try {
            if (s.length() == 17 && s.charAt(8) == '-') {
                return LocalDateTime.parse(s, UTC_DASHED).toInstant(ZoneOffset.UTC);
            }
            String[] parts = s.split("\\s+");
            if (parts.length < 2) {
                throw new IllegalArgumentException("unparseable execution time: " + raw);
            }
            LocalDateTime local = LocalDateTime.parse(parts[0] + " " + parts[1], DATE_TIME);
            ZoneId zone = parts.length > 2 ? zone(parts[2]) : fallbackZone;
            return local.atZone(zone).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("unparseable execution time: " + raw, e);
        }
    }

    /** ExecutionFilter.time format: {@code yyyyMMdd-HH:mm:ss} in UTC. */
    static String executionFilterTime(Instant instant) {
        return UTC_DASHED.format(instant.atOffset(ZoneOffset.UTC));
    }

    // ---- numbers --------------------------------------------------------------

    /** A price; IBKR sends -1, 0, NaN or Double.MAX_VALUE for "none". */
    static BigDecimal price(double value) {
        if (!isSet(value) || value <= 0) {
            return null;
        }
        return BigDecimal.valueOf(value);
    }

    /** An amount that may be zero or negative (P&amp;L, commission); null when unset. */
    static BigDecimal amount(double value) {
        return isSet(value) ? BigDecimal.valueOf(value) : null;
    }

    static BigDecimal quantity(Decimal value) {
        if (value == null || !value.isValid()) {
            return null;
        }
        BigDecimal v = value.value().stripTrailingZeros();
        return v.scale() < 0 ? v.setScale(0) : v;
    }

    private static boolean isSet(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value != Double.MAX_VALUE;
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
