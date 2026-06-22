package mod.azure.ovomorphosis.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

import mod.azure.ovomorphosis.entities.AbstractAlienEntity;

public class ResinWebFullBlock extends AbstractResinBlock {

    public ResinWebFullBlock(Properties settings) {
        super(settings);
    }

    @Override
    public void entityInside(
        @NotNull BlockState state,
        @NotNull Level world,
        @NotNull BlockPos pos,
        @NotNull Entity entity
    ) {
        if (entity instanceof AbstractAlienEntity)
            return;

        if (entity instanceof Player player) {
            if (player.isCreative() || player.isSpectator())
                return;
        }

        if (!(entity instanceof LivingEntity living))
            return;

        EggmorphTracker.getOrCreate(pos.immutable()).onEntityInside(living);
    }

    @Override
    public void onRemove(
        @NotNull BlockState state,
        @NotNull Level world,
        @NotNull BlockPos pos,
        @NotNull BlockState newState,
        boolean movedByPiston
    ) {
        if (!world.isClientSide()) {
            EggmorphTracker.remove(pos);
        }
        super.onRemove(state, world, pos, newState, movedByPiston);
    }

    @Override
    public @NotNull VoxelShape getCollisionShape(
        @NotNull BlockState state,
        @NotNull BlockGetter world,
        @NotNull BlockPos pos,
        @NotNull CollisionContext context
    ) {
        if (
            context instanceof EntityCollisionContext entityCtx
                && entityCtx.getEntity() instanceof AbstractAlienEntity
        ) {
            return Block.box(0, 0, 0, 0, 0, 0);
        }
        return super.getCollisionShape(state, world, pos, context);
    }
}
