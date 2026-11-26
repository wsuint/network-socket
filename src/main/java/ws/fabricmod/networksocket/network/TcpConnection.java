package ws.fabricmod.networksocket.network;

import ws.fabricmod.networksocket.NetworkSocket;
import ws.fabricmod.networksocket.protocol.FrameCodec;
import ws.fabricmod.networksocket.protocol.ProtocolConstants;
import ws.fabricmod.networksocket.protocol.ProtocolException;
import ws.fabricmod.networksocket.protocol.ReplayGuard;
import ws.fabricmod.networksocket.protocol.RequestIds;
import ws.fabricmod.networksocket.protocol.WireMessage;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One TCP connection owned by the Minecraft side (the protocol client).
 *
 * <p>The connection uses exactly one daemon thread for connecting, one for reading fixed
 * 256-byte frames and one for writing them. Minecraft state is never touched from these
 * threads; the listener callbacks are forwarded to the server thread by the manager.</p>
 */
public final class TcpConnection {
    public interface Listener {
        void onConnected(TcpConnection connection);

        void onConnectFailed(TcpConnection connection, String detail);

        void onDisconnected(TcpConnection connection, DisconnectReason reason);

        void onResponse(TcpConnection connection, int power, int tick);

        void onKeepaliveOk(TcpConnection connection);
    }

    public enum DisconnectReason {
        CONNECT_FAILED,
        IO_ERROR,
        HEARTBEAT_TIMEOUT,
        REPLAY_ABUSE,
        SEND_FAILED
    }

    private static final int READ_TIMEOUT_MILLIS = 500;
    private static final int MAX_OUTBOUND_FRAMES = 512;

    private final String name;
    private final ConnectionSettings settings;
    private final Listener listener;
    private final byte[] key;
    private final Socket socket = new Socket();
    private final ReplayGuard replayGuard = new ReplayGuard();
    private final BlockingQueue<byte[]> outbound = new LinkedBlockingQueue<>(MAX_OUTBOUND_FRAMES);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean disconnectReported = new AtomicBoolean();

    private volatile boolean connected;
    private volatile InputStream input;
    private volatile OutputStream output;
    private volatile Thread connectorThread;
    private volatile Thread readerThread;
    private volatile Thread writerThread;

    private volatile String pendingKeepaliveId;
    private volatile long pendingKeepaliveAtMillis;
    private volatile long lastKeepaliveSentMillis;
    private volatile long nextReplayResetMillis;
    private volatile long lastSuccessfulMessageMillis;

    public TcpConnection(String name, ConnectionSettings settings, Listener listener) {
        this.name = name;
        this.settings = settings;
        this.listener = listener;
        this.key = FrameCodec.keyFromToken(settings.token());
    }

    public String name() {
        return name;
    }

    public boolean isConnected() {
        return connected && !closed.get();
    }

    public boolean isClosed() {
        return closed.get();
    }

    public void start() {
        connectorThread = new Thread(this::connectLoop, threadName("connector"));
        connectorThread.setDaemon(true);
        connectorThread.start();
    }

    private void connectLoop() {
        try {
            int connectTimeoutMillis = Math.max(1, settings.timeoutSeconds()) * 1000;
            socket.connect(new InetSocketAddress(settings.address(), settings.port()), connectTimeoutMillis);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            input = socket.getInputStream();
            output = socket.getOutputStream();

            long now = System.currentTimeMillis();
            lastKeepaliveSentMillis = now;
            nextReplayResetMillis = now + ProtocolConstants.REPLAY_WINDOW_SECONDS * 1000L;
            lastSuccessfulMessageMillis = now;
            connected = true;

            writerThread = new Thread(this::writeLoop, threadName("writer"));
            writerThread.setDaemon(true);
            writerThread.start();

            readerThread = new Thread(this::readLoop, threadName("reader"));
            readerThread.setDaemon(true);
            readerThread.start();

            listener.onConnected(this);

            // Register the connection name with the server immediately. Without this first
            // keepalive, the server cannot map the fresh socket to a block name until the
            // first redstone request or the first 60-second heartbeat.
            sendKeepalive();
        } catch (IOException | RuntimeException e) {
            closeQuietly();
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (disconnectReported.compareAndSet(false, true)) {
                listener.onConnectFailed(this, detail);
            }
        }
    }

    private void readLoop() {
        byte[] frame = new byte[ProtocolConstants.FRAME_SIZE];
        try {
            while (!closed.get()) {
                int offset = 0;
                while (offset < frame.length) {
                    int read;
                    try {
                        read = input.read(frame, offset, frame.length - offset);
                    } catch (SocketTimeoutException e) {
                        if (closed.get()) {
                            return;
                        }
                        continue;
                    }
                    if (read < 0) {
                        throw new EOFException("server closed the connection");
                    }
                    offset += read;
                }
                handleFrame(frame.clone());
            }
        } catch (IOException e) {
            if (!closed.get()) {
                closeWith(DisconnectReason.IO_ERROR);
            }
        } catch (RuntimeException e) {
            NetworkSocket.LOGGER.error("[tcp:{}] unexpected error while handling a frame", name, e);
            closeWith(DisconnectReason.IO_ERROR);
        }
    }

    private void writeLoop() {
        try {
            while (!closed.get()) {
                byte[] frame = outbound.take();
                output.write(frame);
                output.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (!closed.get()) {
                closeWith(DisconnectReason.SEND_FAILED);
            }
        }
    }

    private void handleFrame(byte[] frame) {
        String plaintext;
        try {
            plaintext = FrameCodec.decrypt(frame, key);
        } catch (ProtocolException e) {
            NetworkSocket.LOGGER.debug("[tcp:{}] dropped frame: {}", name, e.getMessage());
            return;
        }

        WireMessage message;
        try {
            message = WireMessage.parse(plaintext);
        } catch (ProtocolException e) {
            NetworkSocket.LOGGER.debug("[tcp:{}] dropped malformed message: {}", name, e.getMessage());
            return;
        }

        long nowSeconds = RequestIds.nowSeconds();
        if (Math.abs(nowSeconds - message.timestamp()) > ProtocolConstants.MAX_CLOCK_SKEW_SECONDS) {
            abortOnReplayLimit(replayGuard.increment(message.requestId()));
            return;
        }
        if (replayGuard.contains(message.requestId())) {
            abortOnReplayLimit(replayGuard.increment(message.requestId()));
            return;
        }

        switch (message.type()) {
            case RESPONSE -> {
                if (!isConnected()) {
                    return;
                }
                listener.onResponse(this, message.power(), message.tick());
                int count = replayGuard.recordSuccess(message.requestId());
                lastSuccessfulMessageMillis = System.currentTimeMillis();
                abortOnReplayLimit(count);
            }
            case KEEPALIVE_OK -> {
                String pending = pendingKeepaliveId;
                if (pending == null || !pending.equals(message.requestId())) {
                    NetworkSocket.LOGGER.debug("[tcp:{}] keepaliveok requestid does not match the pending heartbeat", name);
                    return;
                }
                pendingKeepaliveId = null;
                pendingKeepaliveAtMillis = 0L;
                listener.onKeepaliveOk(this);
                int count = replayGuard.recordSuccess(message.requestId());
                lastSuccessfulMessageMillis = System.currentTimeMillis();
                abortOnReplayLimit(count);
            }
            default -> NetworkSocket.LOGGER.debug("[tcp:{}] ignored inbound message type {}",
                    name, message.type());
        }
    }

    private void abortOnReplayLimit(int count) {
        if (count > ProtocolConstants.REPLAY_COUNT_LIMIT) {
            NetworkSocket.LOGGER.warn("[tcp:{}] requestid count reached {}, closing connection", name, count);
            closeWith(DisconnectReason.REPLAY_ABUSE);
        }
    }

    /**
     * Called roughly every 250 ms by the manager. Handles the 30-second replay-table reset,
     * keepalive emission, and the keepalive response timeout.
     */
    public void maintain(long nowMillis) {
        if (!isConnected()) {
            return;
        }

        if (nowMillis >= nextReplayResetMillis) {
            replayGuard.reset();
            nextReplayResetMillis = nowMillis + ProtocolConstants.REPLAY_WINDOW_SECONDS * 1000L;
        }

        if (nowMillis - lastKeepaliveSentMillis >= settings.keepaliveSeconds() * 1000L) {
            sendKeepalive();
        }

        String pending = pendingKeepaliveId;
        if (pending != null && nowMillis - pendingKeepaliveAtMillis > settings.timeoutSeconds() * 1000L) {
            NetworkSocket.LOGGER.warn("[tcp:{}] keepaliveok timeout", name);
            closeWith(DisconnectReason.HEARTBEAT_TIMEOUT);
        }
    }

    /** Sends one request with a fresh requestid/timestamp. Never blocks on I/O. */
    public void sendRequest(int power) {
        if (!isConnected()) {
            return;
        }
        try {
            String requestId = RequestIds.newRequestId();
            long timestamp = RequestIds.nowSeconds();
            String text = WireMessage.request(name, power, requestId, timestamp).encode();
            byte[] frame = FrameCodec.encrypt(text, key);
            NetworkSocket.LOGGER.info("[tcp:{}] request queued: power={} requestid={} frame={} bytes",
                    name, power, requestId, frame.length);
            enqueue(frame);
        } catch (ProtocolException | IllegalArgumentException e) {
            NetworkSocket.LOGGER.warn("[tcp:{}] request was not sent: {}", name, e.getMessage());
        }
    }

    private void sendKeepalive() {
        if (!isConnected()) {
            return;
        }
        lastKeepaliveSentMillis = System.currentTimeMillis();
        try {
            String requestId = RequestIds.newRequestId();
            long timestamp = RequestIds.nowSeconds();
            String text = WireMessage.keepalive(name, requestId, timestamp).encode();
            byte[] frame = FrameCodec.encrypt(text, key);
            // Record the pending id before handing the frame to the writer thread, otherwise a
            // very fast keepaliveok could arrive before pendingKeepaliveId is visible.
            pendingKeepaliveId = requestId;
            pendingKeepaliveAtMillis = lastKeepaliveSentMillis;
            if (!enqueue(frame)) {
                pendingKeepaliveId = null;
                pendingKeepaliveAtMillis = 0L;
            }
        } catch (ProtocolException | IllegalArgumentException e) {
            NetworkSocket.LOGGER.warn("[tcp:{}] keepalive was not sent: {}", name, e.getMessage());
        }
    }

    private boolean enqueue(byte[] frame) {
        if (outbound.offer(frame)) {
            return true;
        }
        NetworkSocket.LOGGER.warn("[tcp:{}] outbound queue is full, closing connection", name);
        closeWith(DisconnectReason.SEND_FAILED);
        return false;
    }

    public void closeSilently() {
        closeQuietly();
    }

    public void closeWith(DisconnectReason reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cleanup();
        if (disconnectReported.compareAndSet(false, true)) {
            listener.onDisconnected(this, reason);
        }
    }

    private void closeQuietly() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cleanup();
    }

    private void cleanup() {
        connected = false;
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing useful to do.
        }
        outbound.clear();
        replayGuard.reset();
        interrupt(connectorThread);
        interrupt(readerThread);
        interrupt(writerThread);
    }

    private static void interrupt(Thread thread) {
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
        }
    }

    private String threadName(String suffix) {
        return "tcp-interface-" + name + "-" + suffix;
    }

    public long lastSuccessfulMessageMillis() {
        return lastSuccessfulMessageMillis;
    }
}