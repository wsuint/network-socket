package ws.fabricmod.networksocket.network.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ws.fabricmod.networksocket.NetworkSocket;

/**
 * Client -> server: enable or disable the connection belonging to one name.
 */
public record ToggleConnectionC2S(String name, boolean enable) implements CustomPacketPayload {
    public static final Type<ToggleConnectionC2S> TYPE = new Type<>(NetworkSocket.id("toggle_connection"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ToggleConnectionC2S> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(256), ToggleConnectionC2S::name,
                    ByteBufCodecs.BOOL, ToggleConnectionC2S::enable,
                    ToggleConnectionC2S::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}