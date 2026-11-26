package ws.fabricmod.networksocket.network.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ws.fabricmod.networksocket.NetworkSocket;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> client: live UI2 table update, broadcast after any status/entry change.
 */
public record TableS2C(List<ConnectionEntryView> entries) implements CustomPacketPayload {
    public static final Type<TableS2C> TYPE = new Type<>(NetworkSocket.id("connection_table"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TableS2C> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.collection(ArrayList::new, ConnectionEntryView.STREAM_CODEC, 4096),
                    TableS2C::entries,
                    TableS2C::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}