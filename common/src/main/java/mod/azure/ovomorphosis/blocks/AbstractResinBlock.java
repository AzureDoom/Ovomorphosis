package mod.azure.ovomorphosis.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;

import mod.azure.ovomorphosis.entities.AbstractAlienEntity;

public abstract class AbstractResinBlock extends Block {

    public AbstractResinBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void neighborChanged(
        @NotNull BlockState state,
        @NotNull Level level,
        @NotNull BlockPos pos,
        @NotNull Block neighborBlock,
        @NotNull BlockPos neighborPos,
        boolean movedByPiston
    ) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (level.isClientSide())
            return;

        var neighborState = level.getBlockState(neighborPos);
        if (!neighborState.is(Blocks.FIRE) && !neighborState.is(Blocks.SOUL_FIRE))
            return;

        var posHash = pos.asLong();
        if ((level.getGameTime() + posHash) % 200 != 0)
            return;

        if (level instanceof ServerLevel serverLevel) {
            var cloudExists = !serverLevel.getEntitiesOfClass(
                AreaEffectCloud.class,
                new AABB(pos).inflate(4.0)
            ).isEmpty();
            if (!cloudExists) {
                spawnToxicCloud(serverLevel, pos);
            }
        }
    }

    @Override
    public void randomTick(
        @NotNull BlockState state,
        @NotNull ServerLevel level,
        @NotNull BlockPos pos,
        @NotNull RandomSource random
    ) {
        var onFire = Direction.stream()
            .anyMatch(
                dir -> level.getBlockState(pos.relative(dir)).is(Blocks.FIRE)
                    || level.getBlockState(pos.relative(dir)).is(Blocks.SOUL_FIRE)
            );
        if (!onFire)
            return;

        level.getEntitiesOfClass(
            LivingEntity.class,
            new AABB(pos).inflate(3.5),
            e -> !(e instanceof AbstractAlienEntity)
        ).forEach(e -> {
            e.addEffect(new MobEffectInstance(MobEffects.POISON, 80, 0));
            e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 0));
        });
    }

    private static void spawnToxicCloud(ServerLevel level, BlockPos pos) {
        var cloud = new AreaEffectCloud(level, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        cloud.setRadius(3.5F);
        cloud.setRadiusOnUse(-0.1F);
        cloud.setWaitTime(10);
        cloud.setDuration(200);
        cloud.setRadiusPerTick(-cloud.getRadius() / cloud.getDuration());
        cloud.addEffect(new MobEffectInstance(MobEffects.POISON, 100, 1));
        cloud.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 100, 0));
        cloud.setParticle(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE);
        level.addFreshEntity(cloud);
    }
}
