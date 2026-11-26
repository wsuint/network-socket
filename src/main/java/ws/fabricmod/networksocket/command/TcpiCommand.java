package ws.fabricmod.networksocket.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import ws.fabricmod.networksocket.network.ConnectionManager;
import ws.fabricmod.networksocket.permission.AccessControl;
import ws.fabricmod.networksocket.permission.PermissionSync;
import ws.fabricmod.networksocket.permission.WhitelistData;

import java.util.Collection;
import java.util.UUID;

/**
 * Admin-only commands:
 * <pre>
 * /tcpi test &lt;name&gt; &lt;power&gt;
 * /tcpi whitelist add &lt;player&gt;
 * /tcpi whitelist remove &lt;player&gt;
 * /tcpi whitelist list
 * </pre>
 *
 * <p>The root requires administrator rights, so whitelist members can use the UI but
 * cannot manage the whitelist or run the debug request command.</p>
 */
public final class TcpiCommand {
    private TcpiCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tcpi")
                .requires(source -> source.getEntity() instanceof ServerPlayer player
                        ? AccessControl.isAdmin(player)
                        : AccessControl.isAdmin(source.permissions()))
                .then(Commands.literal("test")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .then(Commands.argument("power", IntegerArgumentType.integer(0, 15))
                                        .executes(TcpiCommand::test))))
                .then(Commands.literal("whitelist")
                        .then(Commands.literal("add")
                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                        .executes(TcpiCommand::add)))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                        .executes(TcpiCommand::remove)))
                        .then(Commands.literal("list")
                                .executes(TcpiCommand::list))));
    }

    private static int test(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String name = StringArgumentType.getString(context, "name");
        int power = IntegerArgumentType.getInteger(context, "power");
        ServerPlayer player = context.getSource().getPlayerOrException();
        ConnectionManager manager = ConnectionManager.get(context.getSource().getServer());
        if (manager == null) {
            context.getSource().sendFailure(Component.translatable(
                    "message.network-socket.test_unavailable"));
            return 0;
        }
        return manager.sendTestRequest(player, name, power) ? 1 : 0;
    }

    private static int add(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "player");
        if (profiles.isEmpty()) {
            return 0;
        }
        MinecraftServer server = context.getSource().getServer();
        WhitelistData data = WhitelistData.get(server);
        int added = 0;
        for (NameAndId profile : profiles) {
            if (data.add(profile.id())) {
                added++;
            }
            syncIfOnline(server, profile.id());
        }
        int result = added;
        if (result > 0) {
            context.getSource().sendSuccess(() -> Component.translatable(
                    "command.network-socket.whitelist.added",
                    String.valueOf(result)), false);
        } else {
            context.getSource().sendSuccess(() -> Component.translatable(
                    "command.network-socket.whitelist.already"), false);
        }
        return result;
    }

    private static int remove(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "player");
        if (profiles.isEmpty()) {
            return 0;
        }
        MinecraftServer server = context.getSource().getServer();
        WhitelistData data = WhitelistData.get(server);
        int removed = 0;
        for (NameAndId profile : profiles) {
            if (data.remove(profile.id())) {
                removed++;
            }
            syncIfOnline(server, profile.id());
        }
        int result = removed;
        if (result > 0) {
            context.getSource().sendSuccess(() -> Component.translatable(
                    "command.network-socket.whitelist.removed",
                    String.valueOf(result)), false);
        } else {
            context.getSource().sendSuccess(() -> Component.translatable(
                    "command.network-socket.whitelist.not_whitelisted"), false);
        }
        return result;
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        MinecraftServer server = context.getSource().getServer();
        WhitelistData data = WhitelistData.get(server);
        context.getSource().sendSuccess(() -> Component.translatable(
                "command.network-socket.whitelist.list_header",
                String.valueOf(data.size())), false);
        if (data.size() == 0) {
            context.getSource().sendSuccess(() -> Component.translatable(
                    "command.network-socket.whitelist.list_empty"), false);
            return 0;
        }
        for (UUID playerId : data.all()) {
            ServerPlayer online = server.getPlayerList() == null
                    ? null : server.getPlayerList().getPlayer(playerId);
            String label = online == null ? playerId.toString() : online.getGameProfile().name();
            context.getSource().sendSuccess(() -> Component.translatable(
                    "command.network-socket.whitelist.list_entry", label), false);
        }
        return data.size();
    }

    private static void syncIfOnline(MinecraftServer server, UUID playerId) {
        if (server.getPlayerList() == null) {
            return;
        }
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        if (online != null) {
            PermissionSync.send(online);
        }
    }
}