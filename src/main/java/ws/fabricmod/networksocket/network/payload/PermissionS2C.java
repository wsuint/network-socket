package ws.fabricmod.networksocket.network.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ws.fabricmod.networksocket.NetworkSocket;

/**
 * Server -> client: whether this player may open/configure the mod. Sent on join, after
 * whitelist edits, and periodically so OP changes are reflected without reconnecting.
 */
public record PermissionS2C(boolean canConfigure) implements CustomPacketPayload {
    public static final Type<PermissionS2C> TYPE = new Type<>(NetworkSocket.id("permission"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PermissionS2C> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL,
                    PermissionS2C::canConfigure,
                    PermissionS2C::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}