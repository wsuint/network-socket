package ws.fabricmod.networksocket.network.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * One row of UI2. Kept free of the token: table updates are broadcast to every player.
 */
public record ConnectionEntryView(String name, int status) {
    public static final StreamCodec<RegistryFriendlyByteBuf, ConnectionEntryView> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(256), ConnectionEntryView::name,
                    ByteBufCodecs.VAR_INT, ConnectionEntryView::status,
                    ConnectionEntryView::new);
}