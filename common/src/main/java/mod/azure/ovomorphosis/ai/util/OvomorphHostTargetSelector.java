package mod.azure.ovomorphosis.ai.util;

import com.azure.azurecortex.api.blackboard.Blackboard;
import com.azure.azurecortex.api.blackboard.CommonBlackboardKeys;
import com.azure.azurecortex.sensing.TargetSensor;
import net.minecraft.world.entity.LivingEntity;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.entities.ovomorph.OvomorphEntity;

public final class OvomorphHostTargetSelector<E extends OvomorphEntity> implements TargetSensor.Selector<E> {

    @Override
    public LivingEntity findTarget(E egg, Blackboard blackboard) {
        var searchRange = CommonMod.getConfig().entityConfigs.ovomorphConfigs.ovomorphSearchRange;
        var current = blackboard.get(CommonBlackboardKeys.TARGET);
        if (current != null && current.isAlive() && TargetingUtils.faceHuggerTest(egg, current)) {
            var effectiveRangeSqr = current.isCrouching() ? 4.0 : (searchRange * searchRange);
            if (egg.distanceToSqr(current) <= effectiveRangeSqr) {
                return current;
            }
        }

        var searchBox = egg.getBoundingBox().inflate(searchRange);
        var searchRangeSqr = searchRange * searchRange;

        var candidates = egg.level()
            .getEntitiesOfClass(
                LivingEntity.class,
                searchBox,
                candidate -> {
                    var distSq = egg.distanceToSqr(candidate);
                    var effectiveRangeSqr = candidate.isCrouching() ? 4.0 : searchRangeSqr;
                    return distSq <= effectiveRangeSqr
                        && TargetingUtils.faceHuggerTest(egg, candidate)
                        && egg.hasLineOfSight(candidate);
                }
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
