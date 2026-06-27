package mod.azure.ovomorphosis.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.entities.ovomorph.OvomorphEntity;
import mod.azure.ovomorphosis.registry.BlockRegistry;
import mod.azure.ovomorphosis.registry.EntityRegistry;
import mod.azure.ovomorphosis.util.ModTags;

@SuppressWarnings("deprecation")
public class ResinBlock extends AbstractResinBlock {

    public static final IntegerProperty LAYERS = BlockStateProperties.LAYERS;

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    protected static final List<VoxelShape> LAYERS_TO_SHAPE = buildLayerShapes();

    public ResinBlock(Properties settings) {
        super(settings);

        registerDefaultState(
            getStateDefinition().any().setValue(BlockStateProperties.LAYERS, 1).setValue(FACING, Direction.NORTH)
        );
    }

    private static List<VoxelShape> buildLayerShapes() {
        var list = new ArrayList<VoxelShape>();
        list.add(Shapes.empty());
        for (var i = 1; i <= 8; i++)
            list.add(box(0.0, 0.0, 0.0, 16.0, i * 2.0, 16.0));
        return list;
    }

    @Override
    public @NotNull VoxelShape getShape(
        BlockState state,
        @NotNull BlockGetter world,
        @NotNull BlockPos pos,
        @NotNull CollisionContext context
    ) {
        return LAYERS_TO_SHAPE.get(state.getValue(LAYERS));
    }

    @Override
    public @NotNull VoxelShape getCollisionShape(
        @NotNull BlockState state,
        @NotNull BlockGetter world,
        @NotNull BlockPos pos,
        @NotNull CollisionContext context
    ) {
        return LAYERS_TO_SHAPE.get(state.getValue(LAYERS));
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
        else {
            var directions = new Direction[] { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST };
            var facing = directions[ctx.getLevel().getRandom().nextInt(directions.length)];
            return super.getStateForPlacement(ctx).setValue(FACING, facing);
        }
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
        super.randomTick(state, level, pos, random);
        if (
            !level
                .getGameRules()
                .getBoolean(GameRules.RULE_MOBGRIEFING)
        ) {
            return;
        }
        var directions = Direction.values();
        var start = random.nextInt(directions.length);
        for (var i = 0; i < directions.length; i++) {
            var dir = directions[(start + i) % directions.length];

            if (dir == Direction.DOWN)
                continue;

            var neighbor = pos.relative(dir);
            var neighborState = level.getBlockState(neighbor);

            if (neighborState.is(ModTags.RESIN) || neighborState.is(ModTags.ACID_RESISTANT_BLOCKS))
                continue;

            if (!neighborState.getFluidState().isEmpty())
                continue;

            if (
                !neighborState.isAir() && !neighborState.isFaceSturdy(level, neighbor, Direction.UP)
                    && neighborState.canBeReplaced()
            ) {
                level.setBlockAndUpdate(neighbor, randomFacingState(random));
                return;
            }

            if (!neighborState.isAir() && neighborState.isFaceSturdy(level, neighbor, Direction.UP)) {
                var aboveNeighbor = level.getBlockState(neighbor.above());
                var aboveIsPassable = aboveNeighbor.isAir()
                    || (aboveNeighbor.getFluidState().isEmpty()
                        && !aboveNeighbor.isFaceSturdy(level, neighbor.above(), Direction.UP)
                        && aboveNeighbor.canBeReplaced());

                if (dir == Direction.UP) {
                    var beyondState = level.getBlockState(neighbor.relative(dir));
                    if (!beyondState.isAir())
                        continue;
                    level.setBlockAndUpdate(neighbor, randomFacingState(random));
                    return;
                }

                if (aboveIsPassable) {
                    level.setBlockAndUpdate(neighbor, randomFacingState(random));
                    if (random.nextFloat() < 0.3F && aboveNeighbor.isAir())
                        placeOvomorphOrCross(level, neighbor.above(), random);
                    return;
                }

            }

            if (
                dir != Direction.UP && (neighborState.isAir()
                    || (!neighborState.isAir() && neighborState.isFaceSturdy(level, neighbor, Direction.UP)
                        && level.getBlockState(neighbor.above()).isFaceSturdy(level, neighbor.above(), Direction.UP)))
            ) {
                var stepDown = neighbor.below();
                var stepDownState = level.getBlockState(stepDown);
                if (
                    !stepDownState.is(ModTags.RESIN) && !stepDownState.is(ModTags.ACID_RESISTANT_BLOCKS)
                        && !stepDownState.isAir() && stepDownState.getFluidState().isEmpty()
                        && stepDownState.isFaceSturdy(level, stepDown, Direction.UP)
                ) {
                    var aboveNeighborForDrop = level.getBlockState(neighbor.above());
                    if (
                        aboveNeighborForDrop.isAir() || (!aboveNeighborForDrop.isFaceSturdy(
                            level,
                            neighbor.above(),
                            Direction.UP
                        ) && aboveNeighborForDrop.canBeReplaced())
                    ) {
                        level.setBlockAndUpdate(stepDown, randomFacingState(random));
                        if (random.nextFloat() < 0.3F)
                            placeOvomorphOrCross(level, neighbor, random);
                        return;
                    }
                }

                for (var rise = 0; rise <= 8; rise++) {
                    var stepUp = neighbor.above(rise);
                    var stepUpState = level.getBlockState(stepUp);
                    if (stepUpState.is(ModTags.RESIN) || stepUpState.is(ModTags.ACID_RESISTANT_BLOCKS))
                        break;
                    if (stepUpState.isAir() && rise > 0)
                        break;
                    if (!stepUpState.isAir() && !stepUpState.getFluidState().isEmpty())
                        break;
                    if (!stepUpState.isAir() && stepUpState.isFaceSturdy(level, stepUp, Direction.UP)) {
                        var aboveStepUp = level.getBlockState(stepUp.above());
                        var abovePassable = aboveStepUp.isAir()
                            || (aboveStepUp.getFluidState().isEmpty()
                                && !aboveStepUp.isFaceSturdy(level, stepUp.above(), Direction.UP)
                                && aboveStepUp.canBeReplaced());
                        if (abovePassable) {
                            level.setBlockAndUpdate(stepUp, randomFacingState(random));
                            if (random.nextFloat() < 0.3F && aboveStepUp.isAir())
                                placeOvomorphOrCross(level, stepUp.above(), random);
                            return;
                        }
                    }
                }
            }
        }
    }

    private BlockState randomFacingState(RandomSource random) {
        var directions = new Direction[] { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST };
        return defaultBlockState().setValue(LAYERS, 8).setValue(FACING, directions[random.nextInt(directions.length)]);
    }

    private void placeOvomorphOrCross(ServerLevel level, BlockPos pos, RandomSource random) {
        if (random.nextFloat() < 0.1F) {
            var ovomorph = new OvomorphEntity(EntityRegistry.OVOMORPH.get(), level);
            ovomorph.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            level.addFreshEntity(ovomorph);
        } else {
            level.setBlockAndUpdate(pos, BlockRegistry.RESIN_WEB_CROSS.get().defaultBlockState());
        }
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LAYERS, FACING);
    }
}
