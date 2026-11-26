package ws.fabricmod.networksocket.network.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ws.fabricmod.networksocket.NetworkSocket;

/**
 * Client -> server: save the six UI1 fields.
 */
public record SaveConfigC2S(String address, int port, String token,
                           int maxConnectCount, int keepaliveTime, int timeout)
        implements CustomPacketPayload {
    public static final Type<SaveConfigC2S> TYPE = new Type<>(NetworkSocket.id("save_config"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SaveConfigC2S> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(255), SaveConfigC2S::address,
                    ByteBufCodecs.VAR_INT, SaveConfigC2S::port,
                    ByteBufCodecs.stringUtf8(256), SaveConfigC2S::token,
                    ByteBufCodecs.VAR_INT, SaveConfigC2S::maxConnectCount,
                    ByteBufCodecs.VAR_INT, SaveConfigC2S::keepaliveTime,
                    ByteBufCodecs.VAR_INT, SaveConfigC2S::timeout,
                    SaveConfigC2S::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}