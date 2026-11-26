package ws.fabricmod.networksocket;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ws.fabricmod.networksocket.block.TcpInterfaceBlock;
import ws.fabricmod.networksocket.block.TcpInterfaceBlockEntity;
import ws.fabricmod.networksocket.command.TcpiCommand;
import ws.fabricmod.networksocket.network.ConnectionManager;
import ws.fabricmod.networksocket.network.ModPayloads;
import ws.fabricmod.networksocket.network.ServerPayloadHandler;
import ws.fabricmod.networksocket.network.payload.RequestConfigC2S;
import ws.fabricmod.networksocket.network.payload.SaveConfigC2S;
import ws.fabricmod.networksocket.network.payload.SetBlockNameC2S;
import ws.fabricmod.networksocket.network.payload.ToggleConnectionC2S;
import ws.fabricmod.networksocket.permission.PermissionSync;

import java.util.Set;

public class NetworkSocket implements ModInitializer {
    public static final String MOD_ID = "network-socket";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final ResourceKey<net.minecraft.world.level.block.Block> TCP_INTERFACE_BLOCK_KEY =
            ResourceKey.create(Registries.BLOCK, id("tcp_interface"));
    public static final ResourceKey<Item> TCP_INTERFACE_ITEM_KEY =
            ResourceKey.create(Registries.ITEM, id("tcp_interface"));
    public static final ResourceKey<CreativeModeTab> CREATIVE_TAB_KEY =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, id("main"));

    public static final TcpInterfaceBlock TCP_INTERFACE_BLOCK = new TcpInterfaceBlock(
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(3.0F)
                    .sound(SoundType.STONE)
                    .requiresCorrectToolForDrops()
                    .setId(TCP_INTERFACE_BLOCK_KEY));

    public static final BlockEntityType<TcpInterfaceBlockEntity> TCP_INTERFACE_BLOCK_ENTITY =
            new BlockEntityType<>(TcpInterfaceBlockEntity::new, Set.of(TCP_INTERFACE_BLOCK));

    public static final Item TCP_INTERFACE_ITEM = new BlockItem(TCP_INTERFACE_BLOCK,
            new Item.Properties()
                    .setId(TCP_INTERFACE_ITEM_KEY)
                    .useBlockDescriptionPrefix());

    static {
        Registry.register(BuiltInRegistries.BLOCK, TCP_INTERFACE_BLOCK_KEY, TCP_INTERFACE_BLOCK);
        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("tcp_interface"), TCP_INTERFACE_BLOCK_ENTITY);
        Registry.register(BuiltInRegistries.ITEM, TCP_INTERFACE_ITEM_KEY, TCP_INTERFACE_ITEM);

        CreativeModeTab tab = FabricCreativeModeTab.builder()
                .title(Component.translatable("itemGroup.network-socket.main"))
                .icon(() -> new ItemStack(TCP_INTERFACE_ITEM))
                .displayItems((parameters, output) -> output.accept(TCP_INTERFACE_ITEM))
                .build();
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, CREATIVE_TAB_KEY, tab);
    }

    @Override
    public void onInitialize() {
        LOGGER.info("TCP interface loading");

        ModPayloads.register();

        ServerPlayNetworking.registerGlobalReceiver(RequestConfigC2S.TYPE, (payload, context) ->
                context.server().execute(() -> ServerPayloadHandler.onRequestConfig(context.player())));
        ServerPlayNetworking.registerGlobalReceiver(SaveConfigC2S.TYPE, (payload, context) ->
                context.server().execute(() -> ServerPayloadHandler.onSaveConfig(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(ToggleConnectionC2S.TYPE, (payload, context) ->
                context.server().execute(() -> ServerPayloadHandler.onToggleConnection(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(SetBlockNameC2S.TYPE, (payload, context) ->
                context.server().execute(() -> ServerPayloadHandler.onSetBlockName(context.player(), payload)));

        ServerLifecycleEvents.SERVER_STARTED.register(ConnectionManager::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(ConnectionManager::stop);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            PermissionSync.send(handler.player);
            ConnectionManager manager = ConnectionManager.get(server);
            if (manager != null) {
                manager.onPlayerJoin(handler.player);
            }
        });
        // Re-sync periodically so /op and /deop take effect without a reconnect.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 100 == 0) {
                PermissionSync.broadcast(server);
            }
        });

        // Admin-only commands: /tcpi test ... and /tcpi whitelist ...
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                TcpiCommand.register(dispatcher));
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}