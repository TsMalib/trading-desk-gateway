package dev.belis.trader.desk.broker;

/** A broker request failed. {@link #code()} is the IBKR error code, or -1 if not from IBKR. */
public class BrokerException extends RuntimeException {

    private final int code;

    public BrokerException(String message) {
        this(-1, message);
    }

    public BrokerException(int code, String message) {
        super(code >= 0 ? "IBKR " + code + ": " + message : message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
