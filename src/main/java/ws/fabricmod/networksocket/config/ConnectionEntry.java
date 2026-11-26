package ws.fabricmod.networksocket.config;

import java.util.UUID;

/**
 * One name/status record in the per-map connection table.
 *
 * <p>The owner is the player who last enabled the connection; it is used to deliver the
 * "notify player" messages required for connection failures, heartbeat timeouts and
 * replay-triggered disconnects.</p>
 */
public final class ConnectionEntry {
    private final String name;
    private ConnectionStatus status;
    private UUID owner;

    public ConnectionEntry(String name, ConnectionStatus status, UUID owner) {
        this.name = name;
        this.status = status == null ? ConnectionStatus.NOT_CONNECTED : status;
        this.owner = owner;
    }

    public String name() {
        return name;
    }

    public ConnectionStatus status() {
        return status;
    }

    public void setStatus(ConnectionStatus status) {
        this.status = status == null ? ConnectionStatus.NOT_CONNECTED : status;
    }

    public UUID owner() {
        return owner;
    }

    public void setOwner(UUID owner) {
        this.owner = owner;
    }
}