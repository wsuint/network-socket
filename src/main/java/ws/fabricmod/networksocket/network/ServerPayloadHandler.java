package ws.fabricmod.networksocket.network;

import net.minecraft.server.level.ServerPlayer;
import ws.fabricmod.networksocket.network.payload.SaveConfigC2S;
import ws.fabricmod.networksocket.network.payload.SetBlockNameC2S;
import ws.fabricmod.networksocket.network.payload.ToggleConnectionC2S;
import ws.fabricmod.networksocket.permission.AccessControl;

/**
 * Server-thread entry points for the C2S payloads. Every entry point is checked here as
 * well as in {@link ConnectionManager}: the client cache is only a convenience, never a
 * security boundary.
 */
public final class ServerPayloadHandler {
    private ServerPayloadHandler() {
    }

    public static void onRequestConfig(ServerPlayer player) {
        if (!AccessControl.isAuthorized(player)) {
            return;
        }
        ConnectionManager manager = ConnectionManager.get(player.level().getServer());
        if (manager != null) {
            manager.sendConfigData(player);
        }
    }

    public static void onSaveConfig(ServerPlayer player, SaveConfigC2S payload) {
        if (!AccessControl.isAuthorized(player)) {
            return;
        }
        ConnectionManager manager = ConnectionManager.get(player.level().getServer());
        if (manager != null) {
            manager.saveConfig(player, payload);
        }
    }

    public static void onToggleConnection(ServerPlayer player, ToggleConnectionC2S payload) {
        if (!AccessControl.isAuthorized(player)) {
            return;
        }
        ConnectionManager manager = ConnectionManager.get(player.level().getServer());
        if (manager != null) {
            manager.toggleConnection(player, payload.name(), payload.enable());
        }
    }

    public static void onSetBlockName(ServerPlayer player, SetBlockNameC2S payload) {
        if (!AccessControl.isAuthorized(player)) {
            return;
        }
        ConnectionManager manager = ConnectionManager.get(player.level().getServer());
        if (manager != null) {
            manager.setBlockName(player, payload.pos(), payload.name());
        }
    }
}