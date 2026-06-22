package mod.azure.ovomorphosis.ai.util;

import net.minecraft.world.entity.LivingEntity;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.Blackboard;
import mod.azure.ovomorphosis.entities.ovomorph.OvomorphEntity;

public final class OvomorphHostTargetSelector<E extends OvomorphEntity> implements TargetSelector<E> {

    @Override
    public LivingEntity findTarget(E egg, Blackboard blackboard) {
        var current = blackboard.get(AiKeys.TARGET, LivingEntity.class);
        if (current != null && current.isAlive() && TargetingUtils.faceHuggerTest(egg, current)) {
            return current;
        }

        var searchRange = CommonMod.getConfig().entityConfigs.ovomorphConfigs.ovomorphSearchRange;
        var searchBox = egg.getBoundingBox().inflate(searchRange);
        var searchRangeSqr = searchRange * searchRange;

        var candidates = egg.level()
            .getEntitiesOfClass(
                LivingEntity.class,
                searchBox,
                candidate -> egg.distanceToSqr(candidate) <= searchRangeSqr
                    && TargetingUtils.faceHuggerTest(egg, candidate)
                    && egg.hasLineOfSight(candidate)
            );

        if (candidates.isEmpty()) {
            return null;
        }

        LivingEntity nearest = null;
        var nearestDist = Double.MAX_VALUE;
        for (var candidate : candidates) {
            var dist = egg.distanceToSqr(candidate);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = candidate;
            }
        }
        return nearest;
    }
}
