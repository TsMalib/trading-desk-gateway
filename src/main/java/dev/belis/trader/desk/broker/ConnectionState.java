package dev.belis.trader.desk.broker;

/** Connection state of the broker session. */
public enum ConnectionState {
    /** No API socket. A reconnect is scheduled unless the service is shutting down. */
    DISCONNECTED,
    /** Socket handshake in progress, waiting for nextValidId. */
    CONNECTING,
    /** API socket up and IB Gateway connected to IBKR's servers. */
    CONNECTED,
    /** API socket up but IB Gateway lost its connection to IBKR's servers (error 1100). */
    IB_SERVER_LOST
}
