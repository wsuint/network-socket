package ws.fabricmod.networksocket.network.payload;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ws.fabricmod.networksocket.NetworkSocket;

/**
 * Client -> server: save the name of the TCP interface block that was right-clicked.
 */
public record SetBlockNameC2S(BlockPos pos, String name) implements CustomPacketPayload {
    public static final Type<SetBlockNameC2S> TYPE = new Type<>(NetworkSocket.id("set_block_name"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetBlockNameC2S> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SetBlockNameC2S::pos,
                    ByteBufCodecs.stringUtf8(256), SetBlockNameC2S::name,
                    SetBlockNameC2S::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}