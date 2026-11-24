package dev.belis.trader.desk.broker;

import java.time.Instant;

/**
 * CDI event fired after the broker connection comes back. Not fired for the first connect at startup.
 * Observers (the reconciler) run on a dedicated thread and may call the broker.
 */
public record BrokerReconnected(Instant at, Reason reason) {

    public enum Reason {
        /** The API socket to IB Gateway was re-established (e.g. after the daily Gateway restart). */
        SOCKET_RECONNECTED,
        /** IB Gateway regained its connection to IBKR's servers (error 1101/1102). */
        IB_SERVER_RESTORED
    }
}
