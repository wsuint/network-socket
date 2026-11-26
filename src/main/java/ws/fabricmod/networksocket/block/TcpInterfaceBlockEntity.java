package ws.fabricmod.networksocket.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import ws.fabricmod.networksocket.NetworkSocket;
import ws.fabricmod.networksocket.network.ConnectionManager;

/**
 * State holder for one TCP interface block.
 *
 * <p>The block's configured name lives in block-entity NBT (so it travels with the block),
 * while the name/status record itself lives in the per-map {@code NetworkConfig}.</p>
 */
public class TcpInterfaceBlockEntity extends BlockEntity {
    private String name = "";
    private int lastInputPower = -1;
    private boolean registered;
    private boolean removalHandled;
    private long outputEndTick = -1L;

    public TcpInterfaceBlockEntity(BlockPos pos, BlockState state) {
        super(NetworkSocket.TCP_INTERFACE_BLOCK_ENTITY, pos, state);
    }

    public String getName() {
        return name;
    }

    public void setName(String newName) {
        String safeName = newName == null ? "" : newName;
        if (safeName.equals(name)) {
            return;
        }
        String oldName = name;
        name = safeName;
        setChanged();
        if (level instanceof ServerLevel serverLevel) {
            ConnectionManager manager = ConnectionManager.get(serverLevel.getServer());
            if (manager != null) {
                manager.renameBlock(this, oldName, safeName);
            }
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("name", name);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        name = input.getStringOr("name", "");
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level instanceof ServerLevel serverLevel) {
            ConnectionManager manager = ConnectionManager.get(serverLevel.getServer());
            if (manager != null) {
                removeFromConfig(manager);
            }
        }
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel serverLevel) {
            ConnectionManager manager = ConnectionManager.get(serverLevel.getServer());
            if (manager != null) {
                // Some vanilla paths (notably /setblock) pass UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS,
                // which skips preRemoveSideEffects. The block state has already been replaced here,
                // so a state mismatch reliably means "really removed" rather than "chunk unloading".
                if (!removalHandled
                        && !serverLevel.getBlockState(worldPosition).is(NetworkSocket.TCP_INTERFACE_BLOCK)) {
                    removeFromConfig(manager);
                }
                if (registered) {
                    manager.unregisterBlock(this);
                    registered = false;
                }
            }
        }
        super.setRemoved();
    }

    private void removeFromConfig(ConnectionManager manager) {
        if (removalHandled) {
            return;
        }
        removalHandled = true;
        if (registered) {
            manager.unregisterBlock(this);
            registered = false;
        }
        manager.removeBlockConfig(name);
    }

    /** Called once per server tick while the chunk is ticking. */
    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        ConnectionManager manager = ConnectionManager.get(level.getServer());
        if (manager == null) {
            return;
        }
        if (!registered) {
            manager.registerBlock(this);
            registered = true;
        }

        int now = level.getServer().getTickCount();
        if (outputEndTick >= 0L) {
            if (now >= outputEndTick) {
                setOutputPower(level, pos, 0);
                outputEndTick = -1L;
            }
        }

        BlockState current = level.getBlockState(pos);
        if (!current.is(NetworkSocket.TCP_INTERFACE_BLOCK)) {
            return;
        }

        Direction front = state.getValue(TcpInterfaceBlock.FACING);
        BlockPos frontPos = pos.relative(front);
        Direction towardsThisBlock = front.getOpposite();
        int input = Math.max(level.getSignal(frontPos, towardsThisBlock),
                level.getDirectSignal(frontPos, towardsThisBlock));
        input = Math.max(0, Math.min(15, input));

        if (lastInputPower < 0) {
            lastInputPower = input;
            // POWER is transient output state; never keep a stale pulse across a reload.
            if (current.getValue(TcpInterfaceBlock.POWER) != 0) {
                setOutputPower(level, pos, 0);
            }
            return;
        }
        if (input != lastInputPower) {
            lastInputPower = input;
            manager.onRedstoneChanged(this, input);
        }
    }

    /** Called on the server thread when a response for this block's name arrives. */
    public void applyResponse(int power, int tick) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockState current = serverLevel.getBlockState(worldPosition);
        if (!current.is(NetworkSocket.TCP_INTERFACE_BLOCK)) {
            return;
        }
        int safePower = Math.max(0, Math.min(15, power));
        if (safePower == 0 || tick <= 0) {
            setOutputPower(serverLevel, worldPosition, 0);
            outputEndTick = -1L;
            return;
        }
        setOutputPower(serverLevel, worldPosition, safePower);
        outputEndTick = (long) serverLevel.getServer().getTickCount() + tick;
    }

    private void setOutputPower(ServerLevel level, BlockPos pos, int power) {
        BlockState current = level.getBlockState(pos);
        if (!current.is(NetworkSocket.TCP_INTERFACE_BLOCK)) {
            return;
        }
        int safePower = Math.max(0, Math.min(15, power));
        if (current.getValue(TcpInterfaceBlock.POWER) != safePower) {
            level.setBlock(pos, current.setValue(TcpInterfaceBlock.POWER, safePower), Block.UPDATE_ALL);
        }
    }
}