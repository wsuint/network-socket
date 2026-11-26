package ws.fabricmod.networksocket.network.payload;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ws.fabricmod.networksocket.NetworkSocket;

/**
 * Server -> client: open the block configuration screen for a TCP interface block.
 */
public record OpenBlockScreenS2C(BlockPos pos, String name, int status)
        implements CustomPacketPayload {
    public static final Type<OpenBlockScreenS2C> TYPE = new Type<>(NetworkSocket.id("open_block_screen"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenBlockScreenS2C> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, OpenBlockScreenS2C::pos,
                    ByteBufCodecs.stringUtf8(256), OpenBlockScreenS2C::name,
                    ByteBufCodecs.VAR_INT, OpenBlockScreenS2C::status,
                    OpenBlockScreenS2C::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}