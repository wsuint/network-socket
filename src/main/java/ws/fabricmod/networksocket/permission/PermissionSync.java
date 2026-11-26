package ws.fabricmod.networksocket.permission;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import ws.fabricmod.networksocket.network.payload.PermissionS2C;

/**
 * Pushes the client-side permission cache. The server remains authoritative; this only
 * lets the client avoid sending packets or swinging when the player is not allowed to
 * configure the mod.
 */
public final class PermissionSync {
    private PermissionSync() {
    }

    public static void send(ServerPlayer player) {
        if (player == null) {
            return;
        }
        ServerPlayNetworking.send(player, new PermissionS2C(AccessControl.isAuthorized(player)));
    }

    public static void broadcast(MinecraftServer server) {
        if (server == null || server.getPlayerList() == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            send(player);
        }
    }
}