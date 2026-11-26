package ws.fabricmod.networksocket.permission;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.NameAndId;

/**
 * Central permission policy for the mod.
 *
 * <p>Default deny: a player may configure the mod only when they are an administrator or
 * are on the server-wide UUID whitelist. The administrator check is permission level 2
 * (normal OP), with an explicit singleplayer-host fallback. Whitelist membership does not
 * grant administrator rights.</p>
 */
public final class AccessControl {
    private AccessControl() {
    }

    public static boolean isAuthorized(ServerPlayer player) {
        return isAdmin(player) || isWhitelisted(player);
    }

    public static boolean isAdmin(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return false;
        }
        if (isSingleplayerHost(server, player)) {
            return true;
        }
        return isAdmin(player.permissions());
    }

    /**
     * Permission-set variant used by command sources (including the server console).
     */
    public static boolean isAdmin(PermissionSet permissions) {
        // The dedicated server console uses the unrestricted set.
        if (permissions == PermissionSet.ALL_PERMISSIONS) {
            return true;
        }
        if (permissions instanceof LevelBasedPermissionSet levelBased) {
            return levelBased.level().isEqualOrHigherThan(PermissionLevel.GAMEMASTERS);
        }
        return false;
    }

    public static boolean isWhitelisted(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        MinecraftServer server = player.level().getServer();
        return server != null && WhitelistData.get(server).contains(player.getUUID());
    }

    private static boolean isSingleplayerHost(MinecraftServer server, ServerPlayer player) {
        return server.isSingleplayer()
                && server.isSingleplayerOwner(new NameAndId(player.getGameProfile()));
    }
}