package dev.belis.trader.desk.broker;

/** Not connected to IB Gateway, or the connection dropped while a request was pending. */
public class BrokerUnavailableException extends BrokerException {

    public BrokerUnavailableException(String message) {
        super(message);
    }
}
