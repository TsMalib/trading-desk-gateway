package dev.belis.trader.desk.mcp;

import java.math.BigDecimal;

/** Parsing of tool arguments. */
final class ToolArgs {

    /** An argument that cannot be parsed; the message is written for the caller. */
    static final class BadArgument extends RuntimeException {
        BadArgument(String message) {
            super(message);
        }
    }

    private ToolArgs() {
    }

    /**
     * A decimal from its string form ("187.25"). A JSON number reaches here already as text, converted by the
     * MCP layer. Null or blank gives null; the service reports missing required values.
     */
    static BigDecimal decimal(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            throw new BadArgument(name + " must be a decimal number such as \"187.25\", got '" + value + "'");
        }
    }
}
