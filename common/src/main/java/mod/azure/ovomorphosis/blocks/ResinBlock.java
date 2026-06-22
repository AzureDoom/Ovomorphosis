package mod.azure.ovomorphosis.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.entities.AbstractAlienEntity;
import mod.azure.ovomorphosis.registry.BlockRegistry;
import mod.azure.ovomorphosis.util.ModTags;

public class ResinBlock extends AbstractResinBlock {

    public static final IntegerProperty LAYERS = BlockStateProperties.LAYERS;

    protected static final List<VoxelShape> ALIEN_LAYERS_TO_SHAPE = interpolateShapes(false);

    protected static final List<VoxelShape> LAYERS_TO_SHAPE = interpolateShapes(true);

    public ResinBlock(Properties settings) {
        super(settings);

        registerDefaultState(getStateDefinition().any().setValue(BlockStateProperties.LAYERS, 1));
    }

    private static List<VoxelShape> interpolateShapes(boolean divide) {
        ArrayList<VoxelShape> list = new ArrayList<>();
        list.add(Shapes.empty());
        for (var i = 0; i < 8; i++)
            list.add(box(0.0, 0.0, 0.0, 16.0, divide ? (i * 2.0) / 2.0 : i * 2.0, 16.0));
        return list;
    }

    @Override
    public @NotNull VoxelShape getShape(
        BlockState state,
        @NotNull BlockGetter world,
        @NotNull BlockPos pos,
        @NotNull CollisionContext context
    ) {
        return ALIEN_LAYERS_TO_SHAPE.get(state.getValue(LAYERS));
    }

    @Override
    public @NotNull VoxelShape getCollisionShape(
        @NotNull BlockState state,
        @NotNull BlockGetter world,
        @NotNull BlockPos pos,
        @NotNull CollisionContext context
    ) {
        return context instanceof EntityCollisionContext entitycollisioncontext && entitycollisioncontext
            .getEntity() instanceof AbstractAlienEntity
                ? Block.box(0, 0, 0, 0, 0, 0)
                : LAYERS_TO_SHAPE.get(state.getValue(LAYERS));
    }

    @Override
    public @NotNull VoxelShape getBlockSupportShape(
        BlockState state,
        @NotNull BlockGetter world,
        @NotNull BlockPos pos
    ) {
        return LAYERS_TO_SHAPE.get(state.getValue(LAYERS));
    }

    @Override
    public @NotNull VoxelShape getVisualShape(
        BlockState state,
        @NotNull BlockGetter world,
        @NotNull BlockPos pos,
        @NotNull CollisionContext context
    ) {
        return LAYERS_TO_SHAPE.get(state.getValue(LAYERS));
    }

    @Override
    public boolean useShapeForLightOcclusion(@NotNull BlockState state) {
        return true;
    }

    @Override
    public boolean canSurvive(@NotNull BlockState state, LevelReader world, BlockPos pos) {
        var blockState = world.getBlockState(pos.below());
        var isIce = blockState.is(Blocks.ICE);
        var isPackedIce = blockState.is(Blocks.PACKED_ICE);
        var isBarrier = blockState.is(Blocks.BARRIER);
        var isHoney = blockState.is(Blocks.HONEY_BLOCK);
        var isSoulSand = blockState.is(Blocks.SOUL_SAND);
        if (!isIce && !isPackedIce && !isBarrier) {
            if (!isHoney && !isSoulSand)
                return isFaceFull(blockState.getCollisionShape(world, pos.below()), Direction.UP) || blockState.is(
                    this
                ) && blockState.getValue(LAYERS) == 8;
            else
                return true;
        } else
            return false;
    }

    @Override
    public @NotNull BlockState updateShape(
        BlockState state,
        @NotNull Direction direction,
        @NotNull BlockState neighborState,
        @NotNull LevelAccessor world,
        @NotNull BlockPos pos,
        @NotNull BlockPos neighborPos
    ) {
        return !state.canSurvive(world, pos)
            ? Blocks.AIR.defaultBlockState()
            : super.updateShape(
                state,
                direction,
                neighborState,
                world,
                pos,
                neighborPos
            );
    }

    @Override
    public boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        int layers = state.getValue(LAYERS);
        if (context.getItemInHand().is(asItem()) && layers < 8)
            if (context.replacingClickedOnBlock())
                return context.getClickedFace() == Direction.UP;
            else
                return true;
        else
            return layers == 1;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        var blockState = ctx.getLevel().getBlockState(ctx.getClickedPos());
        if (blockState.is(this))
            return blockState.setValue(LAYERS, Math.min(8, blockState.getValue(LAYERS) + 1));
        else
            return super.getStateForPlacement(ctx);
    }

    @Override
    public boolean isRandomlyTicking(@NotNull BlockState state) {
        return CommonMod.getConfig().blockConfigs.enableResinBlockTicking;
    }

    @Override
    public void randomTick(
        @NotNull BlockState state,
        @NotNull ServerLevel level,
        @NotNull BlockPos pos,
        @NotNull RandomSource random
    ) {
        int layers = state.getValue(LAYERS);

        if (layers < 8) {
            level.setBlockAndUpdate(pos, state.setValue(LAYERS, layers + 1));
            return;
        }

        var directions = Direction.values();
        var start = random.nextInt(directions.length);
        for (var i = 0; i < directions.length; i++) {
            var dir = directions[(start + i) % directions.length];
            var neighbor = pos.relative(dir);
            var neighborState = level.getBlockState(neighbor);

            if (neighborState.is(ModTags.RESIN))
                continue;

            var spreadPos = findSupportedPos(level, neighbor, dir);
            if (spreadPos == null)
                continue;

            var spreadState = level.getBlockState(spreadPos);
            if (!spreadState.canBeReplaced())
                continue;

            var newState = random.nextFloat() < 0.1F
                ? BlockRegistry.RESIN_WEB_CROSS.get().defaultBlockState()
                : defaultBlockState().setValue(LAYERS, 1 + random.nextInt(3));

            level.setBlockAndUpdate(spreadPos, newState);
            return;
        }
    }

    @Nullable
    private BlockPos findSupportedPos(ServerLevel level, BlockPos from, Direction spreadDir) {
        if (spreadDir == Direction.UP || spreadDir == Direction.DOWN) {
            return from;
        }
        var floor = from.below();
        var floorState = level.getBlockState(floor);
        if (floorState.isFaceSturdy(level, floor, Direction.UP) || floorState.is(ModTags.RESIN)) {
            return from;
        }

        var neighborState = level.getBlockState(from);
        if (neighborState.isFaceSturdy(level, from, spreadDir.getOpposite())) {
            return from;
        }
        return null;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LAYERS);
    }
}
