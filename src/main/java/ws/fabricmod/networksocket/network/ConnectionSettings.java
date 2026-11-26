package ws.fabricmod.networksocket.network;

/**
 * Immutable snapshot of the UI1 settings taken when a connection is created. Changing UI1
 * therefore affects new connections; live connections keep the settings they were opened with.
 */
public record ConnectionSettings(String address, int port, String token,
                                 int keepaliveSeconds, int timeoutSeconds) {
}