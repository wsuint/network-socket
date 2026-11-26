package ws.fabricmod.networksocket.config;

/**
 * The four status values required by the specification.
 */
public enum ConnectionStatus {
    NOT_CONNECTED("not_connected"),
    CONNECTING("connecting"),
    CONNECTED("connected"),
    DISCONNECTED("disconnected");

    private final String key;

    ConnectionStatus(String key) {
        this.key = key;
    }

    public String translationKey() {
        return "status.network-socket." + key;
    }

    public static ConnectionStatus byName(String name) {
        if (name != null) {
            for (ConnectionStatus status : values()) {
                if (status.name().equals(name)) {
                    return status;
                }
            }
        }
        return NOT_CONNECTED;
    }

    public static ConnectionStatus byOrdinal(int ordinal) {
        ConnectionStatus[] values = values();
        if (ordinal < 0 || ordinal >= values.length) {
            return NOT_CONNECTED;
        }
        return values[ordinal];
    }
}