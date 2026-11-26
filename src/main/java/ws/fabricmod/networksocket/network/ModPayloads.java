package ws.fabricmod.networksocket.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import ws.fabricmod.networksocket.network.payload.BlockNameResultS2C;
import ws.fabricmod.networksocket.network.payload.ConfigDataS2C;
import ws.fabricmod.networksocket.network.payload.OpenBlockScreenS2C;
import ws.fabricmod.networksocket.network.payload.PermissionS2C;
import ws.fabricmod.networksocket.network.payload.RequestConfigC2S;
import ws.fabricmod.networksocket.network.payload.SaveConfigC2S;
import ws.fabricmod.networksocket.network.payload.SetBlockNameC2S;
import ws.fabricmod.networksocket.network.payload.TableS2C;
import ws.fabricmod.networksocket.network.payload.ToggleConnectionC2S;

/**
 * Registers every custom payload type on both sides.
 */
public final class ModPayloads {
    private ModPayloads() {
    }

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(RequestConfigC2S.TYPE, RequestConfigC2S.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SaveConfigC2S.TYPE, SaveConfigC2S.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ToggleConnectionC2S.TYPE, ToggleConnectionC2S.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SetBlockNameC2S.TYPE, SetBlockNameC2S.CODEC);

        PayloadTypeRegistry.clientboundPlay().register(ConfigDataS2C.TYPE, ConfigDataS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(TableS2C.TYPE, TableS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(OpenBlockScreenS2C.TYPE, OpenBlockScreenS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(BlockNameResultS2C.TYPE, BlockNameResultS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PermissionS2C.TYPE, PermissionS2C.CODEC);
    }
}