package mod.azure.ovomorphosis.ai.actions.xenomorph;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import mod.azure.ovomorphosis.ai.core.*;
import mod.azure.ovomorphosis.ai.util.HiveMemory;
import mod.azure.ovomorphosis.blocks.ResinBlock;
import mod.azure.ovomorphosis.registry.BlockRegistry;

public final class PlaceResinAction<E extends Mob> implements Action<E> {

    private static final int MAX_LIGHT_LEVEL = 4;

    private static final int RADIUS = 4;

    private final int priority;

    private final int placementCooldownTicks;

    private int settleTicks = 0;

    private boolean placed = false;

    public PlaceResinAction(int priority, int placementCooldownTicks) {
        this.priority = priority;
        this.placementCooldownTicks = placementCooldownTicks;
    }

    @Override
    public void start(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        cooldowns.set(AiKeys.PASSIVE_DECISION, 1);
        settleTicks = 0;
        placed = false;
    }

    @Override
    public ActionStatus tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        if (mob.getHealth() <= 0)
            return ActionStatus.INTERRUPTED;

        var mobPos = mob.blockPosition();
        if (mob.level().getMaxLocalRawBrightness(mobPos) > MAX_LIGHT_LEVEL)
            return ActionStatus.FAILURE;

        if (!placed) {
            var candidates = findCircleCandidates(mob);
            if (candidates.isEmpty())
                return ActionStatus.FAILURE;

            Collections.shuffle(candidates, new Random(mob.getRandom().nextLong()));

            var hiveMemory = getOrCreateHiveMemory(blackboard);
            var count = 0;

            for (var pos : candidates) {
                if (count >= 12)
                    break;
                if (mob.getRandom().nextFloat() > 0.35F)
                    continue;

                var placeResinCross = mob.getRandom().nextFloat() < 0.125F;
                var newState = placeResinCross
                    ? BlockRegistry.RESIN_WEB_CROSS.get().defaultBlockState()
                    : BlockRegistry.RESIN.get()
                        .defaultBlockState()
                        .setValue(ResinBlock.LAYERS, 1 + mob.getRandom().nextInt(8));

                mob.level().setBlockAndUpdate(pos, newState);
                hiveMemory.trackBlock(pos);
                count++;
            }

            if (count == 0)
                return ActionStatus.FAILURE;

            cooldowns.set(AiKeys.RESIN_PLACE_COOLDOWN, placementCooldownTicks);
            placed = true;
        }

        if (settleTicks++ >= 20)
            return ActionStatus.SUCCESS;

        return ActionStatus.RUNNING;
    }

    @Override
    public void stop(E mob, Blackboard blackboard, Cooldowns cooldowns, ActionStatus reason) {}

    @Override
    public boolean isInterruptible() {
        return true;
    }

    @Override
    public int priority() {
        return priority;
    }

    private List<BlockPos> findCircleCandidates(E mob) {
        var level = mob.level();
        var origin = mob.blockPosition();
        var rSq = RADIUS * RADIUS;
        var innerSq = (RADIUS - 1) * (RADIUS - 1);

        List<BlockPos> candidates = new ArrayList<>();

        for (var x = -RADIUS; x <= RADIUS; x++) {
            for (var z = -RADIUS; z <= RADIUS; z++) {
                var distSq = x * x + z * z;
                if (distSq > rSq || distSq < innerSq)
                    continue;

                for (var y = -1; y <= 1; y++) {
                    var pos = origin.offset(x, y, z);

                    if (level.getMaxLocalRawBrightness(pos) > MAX_LIGHT_LEVEL)
                        continue;

                    if (!level.getBlockState(pos).canBeReplaced())
                        continue;

                    if (hasAdjacentSolid(level.getBlockState(pos.below()), pos.below(), level, pos))
                        candidates.add(pos.immutable());
                }
            }
        }

        return candidates;
    }

    private boolean hasAdjacentSolid(
        BlockState below,
        BlockPos belowPos,
        net.minecraft.world.level.Level level,
        BlockPos target
    ) {
        if (below.isFaceSturdy(level, belowPos, Direction.UP))
            return true;

        for (var dir : Direction.Plane.HORIZONTAL) {
            var adj = target.relative(dir);
            var adjState = level.getBlockState(adj);
            if (adjState.isFaceSturdy(level, adj, dir.getOpposite()))
                return true;
        }
        return false;
    }

    private HiveMemory getOrCreateHiveMemory(Blackboard blackboard) {
        var existing = blackboard.get(AiKeys.HIVE_MEMORY, HiveMemory.class);
        if (existing != null)
            return existing;
        var fresh = new HiveMemory();
        blackboard.set(AiKeys.HIVE_MEMORY, fresh);
        return fresh;
    }
}
