package ws.fabricmod.networksocket.network.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ws.fabricmod.networksocket.NetworkSocket;

/**
 * Client -> server: open the configuration UI and send all current data.
 */
public record RequestConfigC2S() implements CustomPacketPayload {
    public static final RequestConfigC2S INSTANCE = new RequestConfigC2S();
    public static final Type<RequestConfigC2S> TYPE = new Type<>(NetworkSocket.id("request_config"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestConfigC2S> CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}