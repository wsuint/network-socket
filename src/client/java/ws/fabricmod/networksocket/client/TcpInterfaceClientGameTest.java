package ws.fabricmod.networksocket.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import ws.fabricmod.networksocket.NetworkSocket;
import ws.fabricmod.networksocket.block.TcpInterfaceBlock;
import ws.fabricmod.networksocket.config.ConnectionEntry;
import ws.fabricmod.networksocket.config.ConnectionStatus;
import ws.fabricmod.networksocket.config.NetworkConfig;
import ws.fabricmod.networksocket.network.ConnectionManager;
import ws.fabricmod.networksocket.network.payload.RequestConfigC2S;
import ws.fabricmod.networksocket.network.payload.SaveConfigC2S;
import ws.fabricmod.networksocket.network.payload.SetBlockNameC2S;
import ws.fabricmod.networksocket.network.payload.ToggleConnectionC2S;
import ws.fabricmod.networksocket.permission.WhitelistData;

import java.util.function.Predicate;

/**
 * Runtime smoke test (client gametest only; not loaded during normal play).
 *
 * <p>Requires a reference server on 127.0.0.1:19001 with token "gametest-token".</p>
 */
public class TcpInterfaceClientGameTest implements FabricClientGameTest {
    private static final BlockPos POS = new BlockPos(0, 80, 0);
    private static final BlockPos POS2 = new BlockPos(0, 80, 2);
    private static final String NAME = "demo";

    @Override
    public void runTest(ClientGameTestContext context) {
        TestWorldSave save;
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getServer().runCommand("gamemode creative @a");
            singleplayer.getServer().runCommand(
                    "setblock 0 80 0 network-socket:tcp_interface[facing=north]");
            context.waitTicks(5);

            Boolean placed = singleplayer.getServer().computeOnServer(server ->
                    server.overworld().getBlockState(POS).is(NetworkSocket.TCP_INTERFACE_BLOCK));
            if (!Boolean.TRUE.equals(placed)) {
                throw new AssertionError("TCP interface block was not placed");
            }

            // Look at the block so the blockstate/model is loaded and rendered at least once.
            singleplayer.getServer().runCommand("gamemode spectator @a");
            singleplayer.getServer().runCommand("tp @a 0 80 4 180 0");
            context.getInput().lookAt(POS);
            context.waitTicks(10);
            context.takeScreenshot("tcp-interface-block-view");
            singleplayer.getServer().runCommand("gamemode creative @a");
            context.waitTicks(2);

            // Name the block through the real client -> server payload.
            context.runOnClient(client -> ClientPlayNetworking.send(new SetBlockNameC2S(POS, NAME)));
            waitForServer(context, singleplayer, server -> {
                ConnectionEntry entry = NetworkConfig.get(server).getEntry(NAME);
                return entry != null && entry.status() == ConnectionStatus.NOT_CONNECTED;
            }, "named config entry");

            // A second block exercises duplicate/invalid name rejection and maxConnectCount.
            singleplayer.getServer().runCommand(
                    "setblock 0 80 2 network-socket:tcp_interface[facing=north]");
            context.waitTicks(2);
            context.runOnClient(client -> ClientPlayNetworking.send(new SetBlockNameC2S(POS2, NAME)));
            context.waitTicks(10);
            Boolean duplicateRejected = singleplayer.getServer().computeOnServer(server ->
                    ((ws.fabricmod.networksocket.block.TcpInterfaceBlockEntity)
                            server.overworld().getBlockEntity(POS2)).getName().isEmpty());
            if (!Boolean.TRUE.equals(duplicateRejected)) {
                throw new AssertionError("duplicate name was accepted");
            }

            context.runOnClient(client -> ClientPlayNetworking.send(
                    new SetBlockNameC2S(POS2, "bad,name")));
            context.waitTicks(10);
            Boolean invalidRejected = singleplayer.getServer().computeOnServer(server ->
                    ((ws.fabricmod.networksocket.block.TcpInterfaceBlockEntity)
                            server.overworld().getBlockEntity(POS2)).getName().isEmpty());
            if (!Boolean.TRUE.equals(invalidRejected)) {
                throw new AssertionError("invalid name was accepted");
            }

            context.runOnClient(client -> ClientPlayNetworking.send(
                    new SetBlockNameC2S(POS2, "other")));
            waitForServer(context, singleplayer,
                    server -> NetworkConfig.get(server).getEntry("other") != null,
                    "second named entry");

            // Save UI1 through the payload; the server replies with config data and the UI opens.
            context.runOnClient(client -> ClientPlayNetworking.send(
                    new SaveConfigC2S("127.0.0.1", 19001, "gametest-token", 1, 60, 10)));
            context.waitForScreen(NetworkConfigScreen.class);
            context.takeScreenshot("tcp-interface-network-config");

            String tableLabel = context.computeOnClient(client ->
                    Component.translatable("screen.network-socket.tab.table").getString());
            context.clickScreenButton(tableLabel);
            context.waitTicks(2);
            context.takeScreenshot("tcp-interface-connection-table");
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            context.waitTicks(2);

            // Enable the connection and wait for the handshake-less TCP connect.
            context.runOnClient(client -> ClientPlayNetworking.send(
                    new ToggleConnectionC2S(NAME, true)));
            waitForServer(context, singleplayer, server -> {
                ConnectionEntry entry = NetworkConfig.get(server).getEntry(NAME);
                return entry != null && entry.status() == ConnectionStatus.CONNECTED;
            }, "connected status");

            // Debug command: send a request without touching redstone.
            // The Python server log should show this as a TEST request.
            singleplayer.getServer().runCommand("execute as @a run tcpi test demo 15");
            context.waitTicks(5);

            // Console executes the admin-only whitelist command; the entry is server-wide.
            String playerName = singleplayer.getServer().computeOnServer(server ->
                    server.getPlayerList().getPlayers().get(0).getGameProfile().name());
            singleplayer.getServer().runCommand("tcpi whitelist add " + playerName);
            Boolean whitelisted = singleplayer.getServer().computeOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().get(0);
                return WhitelistData.get(server).contains(player.getUUID());
            });
            if (!Boolean.TRUE.equals(whitelisted)) {
                throw new AssertionError("whitelist entry was not persisted");
            }
            singleplayer.getServer().runCommand("tcpi whitelist remove " + playerName);

            // maxConnectCount=1 is already used by "demo", so "other" must be refused.
            context.runOnClient(client -> ClientPlayNetworking.send(
                    new ToggleConnectionC2S("other", true)));
            context.waitTicks(10);
            Boolean secondBlocked = singleplayer.getServer().computeOnServer(server -> {
                ConnectionEntry entry = NetworkConfig.get(server).getEntry("other");
                return entry != null && entry.status() == ConnectionStatus.NOT_CONNECTED;
            });
            if (!Boolean.TRUE.equals(secondBlocked)) {
                throw new AssertionError("maxConnectCount did not block the second connection");
            }
            singleplayer.getServer().runCommand("setblock 0 80 2 minecraft:air");
            waitForServer(context, singleplayer,
                    server -> NetworkConfig.get(server).getEntry("other") == null,
                    "second entry removal");

            singleplayer.getServer().runOnServer(server -> {
                ConnectionManager manager = ConnectionManager.get(server);
                if (manager == null) {
                    throw new AssertionError("connection manager is missing");
                }
                ServerPlayer player = server.getPlayerList().getPlayers().get(0);
                manager.openBlockScreen(player, POS);
            });
            context.waitForScreen(BlockConfigScreen.class);
            context.takeScreenshot("tcp-interface-block-config");
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            context.waitTicks(2);

            // Redstone change -> request -> response -> output power for 20 ticks.
            singleplayer.getServer().runCommand("setblock 0 80 -1 minecraft:redstone_block");
            waitForServer(context, singleplayer, server -> {
                BlockState state = server.overworld().getBlockState(POS);
                int received = server.overworld().getBestNeighborSignal(
                        POS.relative(Direction.SOUTH));
                return state.is(NetworkSocket.TCP_INTERFACE_BLOCK)
                        && state.getValue(TcpInterfaceBlock.POWER) > 0
                        && received == state.getValue(TcpInterfaceBlock.POWER);
            }, "redstone response output");

            context.waitTicks(40);
            waitForServer(context, singleplayer, server -> {
                BlockState state = server.overworld().getBlockState(POS);
                return state.is(NetworkSocket.TCP_INTERFACE_BLOCK)
                        && state.getValue(TcpInterfaceBlock.POWER) == 0;
            }, "redstone output clearing");

            // Exiting the world keeps status=CONNECTED but closes the socket; reopening must reconnect.
            save = singleplayer.getWorldSave();
        }

        try (TestSingleplayerContext reopened = save.open()) {
            waitForServer(context, reopened, server -> {
                ConnectionEntry entry = NetworkConfig.get(server).getEntry(NAME);
                return entry != null && entry.status() == ConnectionStatus.CONNECTED;
            }, "reconnected status after world reload");

            // Breaking the block must remove its table entry immediately.
            reopened.getServer().runCommand("setblock 0 80 0 minecraft:air");
            waitForServer(context, reopened,
                    server -> NetworkConfig.get(server).getEntry(NAME) == null,
                    "config entry removal");
        }
    }

    private static void waitForServer(ClientGameTestContext context,
                                      TestSingleplayerContext singleplayer,
                                      Predicate<MinecraftServer> predicate,
                                      String description) {
        for (int i = 0; i < 100; i++) {
            Boolean matched = singleplayer.getServer().computeOnServer(predicate::test);
            if (Boolean.TRUE.equals(matched)) {
                return;
            }
            context.waitTicks(2);
        }
        throw new AssertionError("timed out waiting for " + description);
    }
}