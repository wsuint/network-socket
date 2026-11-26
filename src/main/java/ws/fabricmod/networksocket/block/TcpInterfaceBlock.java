package ws.fabricmod.networksocket.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import ws.fabricmod.networksocket.NetworkSocket;
import ws.fabricmod.networksocket.network.ConnectionManager;
import ws.fabricmod.networksocket.permission.AccessControl;

/**
 * The TCP interface block. It follows the vanilla observer's shape/placement conventions:
 * the textured "face" is the detection side (front, FACING), while the back side emits
 * redstone power towards the block behind it.
 */
public class TcpInterfaceBlock extends DirectionalBlock implements EntityBlock {
    public static final IntegerProperty POWER = IntegerProperty.create("power", 0, 15);

    public TcpInterfaceBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState()
                .setValue(FACING, Direction.NORTH)
                .setValue(POWER, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, POWER);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getNearestLookingDirection());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    /**
     * {@code direction} points from the querying block towards this block. A receiver behind
     * the observer-style face therefore queries with {@code FACING}, which is where the
     * output is emitted.
     */
    @Override
    protected int getSignal(BlockState state, net.minecraft.world.level.BlockGetter level,
                            BlockPos pos, Direction direction) {
        return direction == state.getValue(FACING) ? state.getValue(POWER) : 0;
    }

    @Override
    protected int getDirectSignal(BlockState state, net.minecraft.world.level.BlockGetter level,
                                  BlockPos pos, Direction direction) {
        return direction == state.getValue(FACING) ? state.getValue(POWER) : 0;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        // The client must report success so the interaction packet reaches the server. The
        // client-side UseBlockCallback suppresses the swing/packet when access is denied.
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        return openConfiguration(level, pos, player) ? InteractionResult.SUCCESS : InteractionResult.FAIL;
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                          Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        return openConfiguration(level, pos, player) ? InteractionResult.SUCCESS : InteractionResult.FAIL;
    }

    private boolean openConfiguration(Level level, BlockPos pos, Player player) {
        if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
            if (!AccessControl.isAuthorized(serverPlayer)) {
                return false;
            }
            ConnectionManager manager = ConnectionManager.get(serverLevel.getServer());
            if (manager != null) {
                manager.openBlockScreen(serverPlayer, pos);
                return true;
            }
        }
        return false;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TcpInterfaceBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return (tickLevel, pos, tickState, blockEntity) -> {
            if (blockEntity instanceof TcpInterfaceBlockEntity tcp
                    && tickLevel instanceof ServerLevel serverLevel) {
                tcp.serverTick(serverLevel, pos, tickState);
            }
        };
    }
}