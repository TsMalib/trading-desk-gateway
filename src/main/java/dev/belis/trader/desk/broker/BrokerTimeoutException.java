package dev.belis.trader.desk.broker;

/** IBKR did not answer a request in time. */
public class BrokerTimeoutException extends BrokerException {

    public BrokerTimeoutException(String message) {
        super(message);
    }
}
