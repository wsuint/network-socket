package ws.fabricmod.networksocket.network.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ws.fabricmod.networksocket.NetworkSocket;

/**
 * Server -> client: result of renaming a block. Failure details are sent as a chat message.
 */
public record BlockNameResultS2C(boolean success, String name, int status)
        implements CustomPacketPayload {
    public static final Type<BlockNameResultS2C> TYPE = new Type<>(NetworkSocket.id("block_name_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BlockNameResultS2C> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, BlockNameResultS2C::success,
                    ByteBufCodecs.stringUtf8(256), BlockNameResultS2C::name,
                    ByteBufCodecs.VAR_INT, BlockNameResultS2C::status,
                    BlockNameResultS2C::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}