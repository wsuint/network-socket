package ws.fabricmod.networksocket.client;

/**
 * Client-side cache of the server's permission decision. Defaults to {@code false}; the
 * server sends {@code PermissionS2C} on join and whenever access may have changed.
 */
public final class ClientAccess {
    private static volatile boolean canConfigure;

    private ClientAccess() {
    }

    public static boolean canConfigure() {
        return canConfigure;
    }

    public static void set(boolean allowed) {
        canConfigure = allowed;
    }

    public static void reset() {
        canConfigure = false;
    }
}