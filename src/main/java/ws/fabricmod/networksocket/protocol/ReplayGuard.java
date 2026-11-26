package ws.fabricmod.networksocket.protocol;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-connection, in-memory receive-side request count table. It is never persisted and is
 * reset every 30 seconds (or when the connection closes, at which point it is discarded).
 */
public final class ReplayGuard {
    private final Map<String, Integer> counts = new HashMap<>();

    /** Adds one to the requestid count and returns the new count. */
    public synchronized int increment(String requestId) {
        int next = counts.getOrDefault(requestId, 0) + 1;
        counts.put(requestId, next);
        return next;
    }

    public synchronized boolean contains(String requestId) {
        return counts.containsKey(requestId);
    }

    /** Records the requestid after a message has been processed successfully. */
    public synchronized int recordSuccess(String requestId) {
        return increment(requestId);
    }

    /** Clears the table. Does not affect the connection or any persisted status. */
    public synchronized void reset() {
        counts.clear();
    }

    public synchronized int size() {
        return counts.size();
    }
}