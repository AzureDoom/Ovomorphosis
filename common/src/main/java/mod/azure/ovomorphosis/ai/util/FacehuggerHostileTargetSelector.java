package mod.azure.ovomorphosis.ai.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.animal.AbstractFish;
import net.minecraft.world.phys.Vec3;

import java.util.*;

import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.Blackboard;
import mod.azure.ovomorphosis.entities.AbstractAlienEntity;
import mod.azure.ovomorphosis.entities.facehugger.FacehuggerEntity;
import mod.azure.ovomorphosis.util.ModTags;

public final class FacehuggerHostileTargetSelector<E extends FacehuggerEntity> implements TargetSelector<E> {

    private final double range;

    private final ArrayDeque<BlockPos> heardQueue = new ArrayDeque<>();

    public FacehuggerHostileTargetSelector(double range) {
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

        if (
            current != null && current.isAlive()
                && !(current instanceof AbstractAlienEntity) && !(current instanceof AbstractFish)
                && TargetingUtils.faceHuggerTest(mob, current)
                && mob.distanceToSqr(current) <= range * range
        ) {
            return current;
        }

        var level = mob.level();

        var nearby = level.getEntitiesOfClass(
            LivingEntity.class,
            mob.getBoundingBox().inflate(range),
            candidate -> isValidCandidateFullRange(mob, candidate)
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

    private boolean isValidCandidateFullRange(E mob, LivingEntity candidate) {
        if (candidate instanceof AmbientCreature)
            return false;
        if (candidate instanceof AbstractAlienEntity)
            return false;
        if (candidate.getType().is(ModTags.FACEHUGGER_BLACKLIST))
            return false;
        if (TargetingUtils.isFacehuggerAttached(candidate))
            return false;
        if (candidate.getVehicle() instanceof AbstractAlienEntity)
            return false;
        if (candidate instanceof AbstractFish)
            return false;
        return TargetingUtils.baseValid(mob).test(candidate)
            && TargetingUtils.notAnnoyingMobs().test(candidate)
            && mob.distanceToSqr(candidate) <= range * range;
    }

    public void onTargetKilled() {
        heardQueue.clear();
    }

}
