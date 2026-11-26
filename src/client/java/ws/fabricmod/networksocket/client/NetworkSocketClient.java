package ws.fabricmod.networksocket.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.InteractionResult;
import ws.fabricmod.networksocket.block.TcpInterfaceBlock;
import ws.fabricmod.networksocket.network.payload.BlockNameResultS2C;
import ws.fabricmod.networksocket.network.payload.ConfigDataS2C;
import ws.fabricmod.networksocket.network.payload.OpenBlockScreenS2C;
import ws.fabricmod.networksocket.network.payload.PermissionS2C;
import ws.fabricmod.networksocket.network.payload.RequestConfigC2S;
import ws.fabricmod.networksocket.network.payload.TableS2C;

public class NetworkSocketClient implements ClientModInitializer {
    private static KeyMapping openConfigKey;

    @Override
    public void onInitializeClient() {
        openConfigKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.network-socket.open_config",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_K,
                KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openConfigKey.consumeClick()) {
                if (ClientAccess.canConfigure()
                        && client.player != null && client.gui.screen() == null) {
                    ClientPlayNetworking.send(RequestConfigC2S.INSTANCE);
                }
            }
        });

        // Deny before the client sends a use packet, so an unauthorized player neither
        // swings nor opens a screen. The server still checks every packet independently.
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (player == null || world == null || !world.isClientSide()) {
                return InteractionResult.PASS;
            }
            if (!(world.getBlockState(hitResult.getBlockPos()).getBlock() instanceof TcpInterfaceBlock)) {
                return InteractionResult.PASS;
            }
            return ClientAccess.canConfigure() ? InteractionResult.PASS : InteractionResult.FAIL;
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientAccess.reset());

        ClientPlayNetworking.registerGlobalReceiver(PermissionS2C.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    Minecraft client = context.client();
                    ClientAccess.set(payload.canConfigure());
                    if (!payload.canConfigure()) {
                        Screen current = client.gui.screen();
                        if (current instanceof NetworkConfigScreen || current instanceof BlockConfigScreen) {
                            client.gui.setScreen(null);
                        }
                    }
                }));

        ClientPlayNetworking.registerGlobalReceiver(ConfigDataS2C.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    Minecraft client = context.client();
                    if (!ClientAccess.canConfigure()) {
                        return;
                    }
                    Screen current = client.gui.screen();
                    if (current instanceof NetworkConfigScreen screen) {
                        screen.applyConfigData(payload);
                    } else {
                        client.gui.setScreen(new NetworkConfigScreen(payload));
                    }
                }));

        ClientPlayNetworking.registerGlobalReceiver(TableS2C.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    if (ClientAccess.canConfigure()
                            && context.client().gui.screen() instanceof NetworkConfigScreen screen) {
                        screen.applyTable(payload);
                    }
                }));

        ClientPlayNetworking.registerGlobalReceiver(OpenBlockScreenS2C.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    if (ClientAccess.canConfigure()) {
                        context.client().gui.setScreen(
                                new BlockConfigScreen(payload.pos(), payload.name(), payload.status()));
                    }
                }));

        ClientPlayNetworking.registerGlobalReceiver(BlockNameResultS2C.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    if (context.client().gui.screen() instanceof BlockConfigScreen screen) {
                        screen.applyResult(payload);
                    }
                }));
    }
}