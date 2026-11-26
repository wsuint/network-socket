package ws.fabricmod.networksocket.network.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ws.fabricmod.networksocket.NetworkSocket;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> client: full UI1 + UI2 snapshot. Sent only to the requesting player because it
 * contains the token.
 */
public record ConfigDataS2C(String address, int port, String token,
                            int maxConnectCount, int keepaliveTime, int timeout,
                            List<ConnectionEntryView> entries) implements CustomPacketPayload {
    public static final Type<ConfigDataS2C> TYPE = new Type<>(NetworkSocket.id("config_data"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ConfigDataS2C> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(255), ConfigDataS2C::address,
                    ByteBufCodecs.VAR_INT, ConfigDataS2C::port,
                    ByteBufCodecs.stringUtf8(256), ConfigDataS2C::token,
                    ByteBufCodecs.VAR_INT, ConfigDataS2C::maxConnectCount,
                    ByteBufCodecs.VAR_INT, ConfigDataS2C::keepaliveTime,
                    ByteBufCodecs.VAR_INT, ConfigDataS2C::timeout,
                    ByteBufCodecs.collection(ArrayList::new, ConnectionEntryView.STREAM_CODEC, 4096),
                    ConfigDataS2C::entries,
                    ConfigDataS2C::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}