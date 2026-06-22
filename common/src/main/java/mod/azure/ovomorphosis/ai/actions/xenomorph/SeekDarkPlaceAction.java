package mod.azure.ovomorphosis.ai.actions.xenomorph;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;

import java.util.ArrayList;
import java.util.List;

import mod.azure.ovomorphosis.ai.core.Action;
import mod.azure.ovomorphosis.ai.core.ActionStatus;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.Blackboard;
import mod.azure.ovomorphosis.ai.core.Cooldowns;
import mod.azure.ovomorphosis.ai.util.HiveMemory;
import mod.azure.ovomorphosis.entities.xenomorph.XenomorphEntity;

/**
 * Out-of-combat wandering behavior that steers the xenomorph toward dark, enclosed spaces suitable for hive
 * construction.
 * <p>
 * This is a single-tick planner: it scores candidate positions and writes the best one to {@link AiKeys#DESTINATION},
 * then returns {@link ActionStatus#SUCCESS} so {@code MoveToDestinationAction} handles locomotion on the next tree
 * evaluation.
 * <p>
 * If no qualifying dark spot is found (e.g. outdoors in full sunlight) the action returns {@link ActionStatus#FAILURE}
 * without arming {@link AiKeys#SEEK_COOLDOWN}, so the tree falls through to wander/idle immediately rather than locking
 * the passive branch for hundreds of ticks.
 *
 * @param <E> xenomorph entity type
 */
public class SeekDarkPlaceAction<E extends XenomorphEntity> implements Action<E> {

    private static final double MIN_DEST_DIST_SQ = 8.0 * 8.0; // 8 blocks

    private final int replantCooldownTicks;

    public SeekDarkPlaceAction(int replantCooldownTicks) {
        this.replantCooldownTicks = replantCooldownTicks;
    }

    @Override
    public void start(E mob, Blackboard blackboard, Cooldowns cooldowns) {}

    @Override
    public ActionStatus tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        if (blackboard.has(AiKeys.DESTINATION) && blackboard.get(AiKeys.DESTINATION, BlockPos.class) != null) {
            return ActionStatus.FAILURE;
        }

        var dest = findDarkDestination(mob, blackboard);
        if (dest == null) {
            return ActionStatus.FAILURE;
        }

        blackboard.set(AiKeys.DESTINATION, dest);
        cooldowns.set(AiKeys.SEEK_COOLDOWN, replantCooldownTicks);
        return ActionStatus.SUCCESS;
    }

    @Override
    public void stop(E mob, Blackboard blackboard, Cooldowns cooldowns, ActionStatus reason) {}

    @Override
    public boolean isInterruptible() {
        return true;
    }

    @Override
    public int priority() {
        return 5;
    }

    private static BlockPos findDarkDestination(XenomorphEntity mob, Blackboard blackboard) {
        var level = mob.level();
        var origin = mob.blockPosition();
        var random = mob.getRandom();

        BlockPos resinCentre = null;
        var memory = blackboard.get(AiKeys.HIVE_MEMORY, HiveMemory.class);
        if (memory != null) {
            var nearest = memory.findNearestWebCross(level, origin, 80.0);
            if (nearest.isPresent()) {
                resinCentre = nearest.get();
            }
        }

        List<ScoredPos> candidates = new ArrayList<>(64);

        for (var attempt = 0; attempt < 64 * 3 && candidates.size() < 64; attempt++) {
            var ox = (random.nextInt(24 * 2 + 1)) - 24;
            var oy = (random.nextInt(12 * 2 + 1)) - 12;
            var oz = (random.nextInt(24 * 2 + 1)) - 24;

            var pos = origin.offset(ox, oy, oz);

            if (origin.distSqr(pos) < MIN_DEST_DIST_SQ)
                continue;

            if (!level.getBlockState(pos).isAir())
                continue;

            var floor = pos.below();
            if (level.getBlockState(floor).getCollisionShape(level, floor).isEmpty())
                continue;

            var clearAbove = true;
            for (var cy = 1; cy < 2; cy++) {
                if (!level.getBlockState(pos.above(cy)).isAir()) {
                    clearAbove = false;
                    break;
                }
            }
            if (!clearAbove)
                continue;

            var skyLight = level.getBrightness(LightLayer.SKY, pos);
            var blockLight = level.getMaxLocalRawBrightness(pos);
            var totalLight = Math.max(skyLight, blockLight);
            if (totalLight > 4)
                continue;

            var score = scorePosition(pos, origin, resinCentre, totalLight);
            candidates.add(new ScoredPos(pos.immutable(), score));
        }

        if (candidates.isEmpty())
            return null;

        candidates.sort((a, b) -> Double.compare(b.score, a.score));
        return candidates.getFirst().pos;
    }

    private static double scorePosition(BlockPos pos, BlockPos mobPos, BlockPos resinCentre, int totalLight) {
        var score = (4 - totalLight) * 10.0;
        if (totalLight == 0)
            score += 20.0;

        if (resinCentre != null) {
            var distToResin = resinCentre.distSqr(pos);
            if (distToResin >= 16D * 16D) {
                score += 15.0;
            }
        }

        return score;
    }

    private record ScoredPos(
        BlockPos pos,
        double score
    ) {}
}
