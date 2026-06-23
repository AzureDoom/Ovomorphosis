package mod.azure.ovomorphosis.ai.util;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.animal.AbstractFish;
import net.minecraft.world.phys.AABB;

import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.Blackboard;
import mod.azure.ovomorphosis.entities.AbstractAlienEntity;
import mod.azure.ovomorphosis.entities.facehugger.FacehuggerEntity;
import mod.azure.ovomorphosis.util.ModTags;

public final class FacehuggerHostileTargetSelector<E extends FacehuggerEntity> implements TargetSelector<E> {

    private final double range;

    public FacehuggerHostileTargetSelector(double range) {
        this.range = range;
    }

    @Override
    public LivingEntity findTarget(E mob, Blackboard blackboard) {
        var current = blackboard.get(AiKeys.TARGET, LivingEntity.class);

        if (
            current != null && current.isAlive()
                && !(current instanceof AbstractAlienEntity) && !(current instanceof AbstractFish)
                && TargetingUtils.faceHuggerTest(mob, current)
                && !hasOtherFacehuggerPassenger(mob, current)
                && mob.distanceToSqr(current) <= range * range
        ) {
            return current;
        }

        var searchBox = AABB.ofSize(mob.position(), range * 2, range * 2, range * 2);
        var nearby = mob.level()
            .getEntitiesOfClass(
                LivingEntity.class,
                searchBox,
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

        return closest;
    }

    private boolean isValidCandidateFullRange(E mob, LivingEntity candidate) {
        if (candidate instanceof AmbientCreature)
            return false;
        if (candidate instanceof AbstractAlienEntity)
            return false;
        if (candidate.getType().is(ModTags.FACEHUGGER_BLACKLIST))
            return false;
        if (hasOtherFacehuggerPassenger(mob, candidate))
            return false;
        if (candidate.getVehicle() instanceof AbstractAlienEntity)
            return false;
        if (candidate instanceof AbstractFish)
            return false;
        return TargetingUtils.baseValid(mob).test(candidate)
            && TargetingUtils.notAnnoyingMobs().test(candidate)
            && mob.distanceToSqr(candidate) <= range * range;
    }

    /**
     * Returns true if {@code candidate} has any facehugger passenger that is NOT {@code self}.
     */
    private static boolean hasOtherFacehuggerPassenger(FacehuggerEntity self, LivingEntity candidate) {
        return candidate.getPassengers()
            .stream()
            .anyMatch(p -> p instanceof FacehuggerEntity fh && fh != self);
    }

    public void onTargetKilled() {}
}
