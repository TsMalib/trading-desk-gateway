package dev.belis.trader.desk.broker;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Just enough of the TWS socket protocol (text encoding, server version 100) to test connect,
 * reconnect and a few callbacks of {@link IbkrBroker} without IB Gateway.
 */
final class FakeTwsServer implements AutoCloseable {

    private static final int SERVER_VERSION = 100;
    private static final int START_API = 71;
    private static final int REQ_ACCOUNT_SUMMARY = 62;

    private final ServerSocket server;
    private final List<Socket> clients = new CopyOnWriteArrayList<>();
    private final AtomicInteger connections = new AtomicInteger();
    private volatile boolean acceptConnections = true;
    private volatile int nextValidId = 100;

    FakeTwsServer() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofPlatform().daemon().name("fake-tws-accept").start(this::acceptLoop);
    }

    int port() {
        return server.getLocalPort();
    }

    int connections() {
        return connections.get();
    }

    void setNextValidId(int id) {
        nextValidId = id;
    }

    /** Closes every client socket, like IB Gateway's daily restart. */
    void dropClients() throws IOException {
        for (Socket s : clients) {
            s.close();
        }
        clients.clear();
    }

    /** While false, new connections are closed right after accept (Gateway still restarting). */
    void setAcceptConnections(boolean accept) {
        acceptConnections = accept;
    }

    /** Sends an error message (msg id 4) to every client. */
    void sendError(int id, int code, String message) throws IOException {
        broadcast("4", "2", String.valueOf(id), String.valueOf(code), message);
    }

    /** Sends an orderStatus (msg id 3, version 6 below server version 131) to every client. */
    void sendOrderStatus(int orderId, String status, String filled, String remaining) throws IOException {
        broadcast("3", "6", String.valueOf(orderId), status, filled, remaining, "0", "9" + orderId, "0", "0", "7",
                "");
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                if (!acceptConnections) {
                    s.close();
                    continue;
                }
                clients.add(s);
                Thread.ofPlatform().daemon().name("fake-tws-client").start(() -> serve(s));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket s) {
        try {
            DataInputStream in = new DataInputStream(s.getInputStream());
            byte[] api = new byte[4];
            in.readFully(api); // "API\0"
            readMessage(in);   // "v100..226" plus connect options
            send(s, String.valueOf(SERVER_VERSION), "20261124 10:00:00 EST");
            connections.incrementAndGet();
            while (true) {
                List<String> fields = readMessage(in);
                int msgId = Integer.parseInt(fields.get(0));
                if (msgId == START_API) {
                    send(s, "15", "1", "DU123456");                         // managedAccounts
                    send(s, "9", "1", String.valueOf(nextValidId));         // nextValidId
                } else if (msgId == REQ_ACCOUNT_SUMMARY) {
                    String reqId = fields.get(2);
                    send(s, "63", "1", reqId, "DU123456", "NetLiquidation", "100123.45", "EUR");
                    send(s, "63", "1", reqId, "DU123456", "TotalCashValue", "-250.5", "EUR");
                    send(s, "64", "1", reqId);
                }
            }
        } catch (IOException | RuntimeException e) {
            // client went away or socket closed by dropClients()
        } finally {
            clients.remove(s);
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static List<String> readMessage(DataInputStream in) throws IOException {
        int len = in.readInt();
        byte[] buf = new byte[len];
        in.readFully(buf);
        List<String> fields = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < len; i++) {
            if (buf[i] == 0) {
                fields.add(new String(buf, start, i - start, StandardCharsets.UTF_8));
                start = i + 1;
            }
        }
        if (start < len) {
            fields.add(new String(buf, start, len - start, StandardCharsets.UTF_8));
        }
        return fields;
    }

    private void broadcast(String... fields) throws IOException {
        for (Socket s : clients) {
            send(s, fields);
        }
    }

    private static void send(Socket s, String... fields) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (String f : fields) {
            body.writeBytes(f.getBytes(StandardCharsets.UTF_8));
            body.write(0);
        }
        synchronized (s) {
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            out.writeInt(body.size());
            out.write(body.toByteArray());
            out.flush();
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        dropClients();
    }
}
