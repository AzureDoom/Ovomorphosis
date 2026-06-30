package mod.azure.ovomorphosis.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import mod.azure.ovomorphosis.ai.util.TargetingUtils;
import mod.azure.ovomorphosis.infection.EggmorphTracker;
import mod.azure.ovomorphosis.registry.BlockEntityRegistry;

public class ResinWebBlockEntity extends BlockEntity {

    public ResinWebBlockEntity(BlockPos pos, BlockState state) {
        super(BlockEntityRegistry.RESIN_WEB_CROSS_BE.get(), pos, state);
    }

    @SuppressWarnings("unused")
    public static void serverTick(Level level, BlockPos pos, BlockState state, BlockEntity be) {
        if (level.isClientSide())
            return;
        var tracker = EggmorphTracker.getOrCreate(pos.immutable());

        var cx = pos.getX() + 0.5;
        var cy = pos.getY() + 0.5;
        var cz = pos.getZ() + 0.5;
        var aabb = new AABB(cx - 0.5, cy - 0.5, cz - 0.5, cx + 0.5, cy + 0.5, cz + 0.5);

        for (var entity : level.getEntitiesOfClass(LivingEntity.class, aabb)) {
            if (!TargetingUtils.eggmorphValid().test(entity))
                continue;
            tracker.onEntityInside(entity);
        }
    }
}
