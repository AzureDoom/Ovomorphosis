package mod.azure.ovomorphosis.ai.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;

import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.Blackboard;
import mod.azure.ovomorphosis.entities.AbstractAlienEntity;

public final class XenomorphHostileTargetSelector<E extends AbstractAlienEntity> implements TargetSelector<E> {

    private final double range;

    private final ArrayDeque<BlockPos> heardQueue = new ArrayDeque<>();

    public XenomorphHostileTargetSelector(double range) {
        this.range = range;
    }

    public void hearSound(Vec3 pos) {
        var incoming = BlockPos.containing(pos);

        for (var queued : heardQueue) {
            var dx = queued.getX() - incoming.getX();
            var dy = queued.getY() - incoming.getY();
            var dz = queued.getZ() - incoming.getZ();
            if (dx * dx + dy * dy + dz * dz < 4.0 * 4.0) {
                return;
            }
        }

        if (heardQueue.size() >= 15) {
            heardQueue.pollFirst();
        }

        heardQueue.addLast(incoming);
    }

    @Override
    public LivingEntity findTarget(E mob, Blackboard blackboard) {
        var current = blackboard.get(AiKeys.TARGET, LivingEntity.class);

        var nearby = mob.level()
            .getEntitiesOfClass(
                LivingEntity.class,
                mob.getBoundingBox().inflate(range),
                candidate -> isValidCandidate(mob, candidate)
            );

        LivingEntity closest = null;
        double closestDistSq = Double.MAX_VALUE;
        for (var candidate : nearby) {
            var distSq = mob.distanceToSqr(candidate);
            if (distSq < closestDistSq) {
                closestDistSq = distSq;
                closest = candidate;
            }
        }

        if (
            current != null && current.isAlive()
                && TargetingUtils.validTarget(mob).test(current)
                && (mob.distanceToSqr(current) <= 32 * 32 || mob.hasLineOfSight(current))
        ) {

            var currentDistSq = mob.distanceToSqr(current);
            if (current.isInWater() && currentDistSq <= 24 * 24) {
                return current;
            }
            if (closest == null || currentDistSq * 0.6 <= closestDistSq) {
                return current;
            }
        }

        if (closest != null) {
            heardQueue.clear();
            return closest;
        }

        if (!heardQueue.isEmpty() && blackboard.get(AiKeys.DESTINATION, BlockPos.class) == null) {
            var nextPos = heardQueue.pollFirst();
            blackboard.set(AiKeys.DESTINATION, nextPos);
        }

        return null;
    }

    private boolean isValidCandidate(E mob, LivingEntity candidate) {
        if (!TargetingUtils.validTarget(mob).test(candidate)) {
            return false;
        }
        var distSq = mob.distanceToSqr(candidate);
        return distSq <= 32 * 32 || mob.hasLineOfSight(candidate);
    }

    public void onTargetKilled() {
        heardQueue.clear();
    }
}
