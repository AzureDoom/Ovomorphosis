package mod.azure.xenogenesis.entities.ovomorph;

import net.minecraft.world.entity.LivingEntity;

import mod.azure.xenogenesis.CommonMod;
import mod.azure.xenogenesis.ai.actions.IdleAction;
import mod.azure.xenogenesis.ai.actions.ovomorph.HatchFacehuggerAction;
import mod.azure.xenogenesis.ai.core.AiKeys;
import mod.azure.xenogenesis.ai.core.BehaviorNode;
import mod.azure.xenogenesis.ai.core.BehaviorResult;
import mod.azure.xenogenesis.ai.util.TargetingUtils;

public class OvomorphTree {

    public static BehaviorNode<OvomorphEntity> create() {
        var hatch = new HatchFacehuggerAction();
        var idle = new IdleAction<OvomorphEntity>(40, 100, 1);

        return ((egg, blackboard, cooldowns) -> {
            int state = egg.getEggState();

            if (state == EggStates.HATCHED.ordinal() || !egg.hasFacehugger()) {
                return BehaviorResult.run(idle, 5);
            }

            if (state == EggStates.HATCHING.ordinal()) {
                return BehaviorResult.run(hatch, 100);
            }

            LivingEntity host = findHostNearEgg(egg);
            if (host != null) {
                blackboard.set(AiKeys.TARGET, host);
                egg.setEggState(EggStates.HATCHING.ordinal());
                return BehaviorResult.run(hatch, 100);
            }

            return BehaviorResult.run(idle, 5);
        });
    }

    /**
     * Returns the nearest {@link LivingEntity} within 6 blocks of the egg that passes the full facehugger validity
     * test, including line-of-sight.
     * <p>
     * The search uses an AABB centred on the egg inflated by the host range, then distance-filters against the squared
     * radius before the more expensive LOS check.
     *
     * @param egg the ovomorph doing the scanning
     * @return the nearest valid host, or {@code null} if none is found
     */
    private static LivingEntity findHostNearEgg(OvomorphEntity egg) {
        var searchRange = CommonMod.getConfig().entityConfigs.ovomorphConfigs.ovomorphSearchRange;
        var searchBox = egg.getBoundingBox().inflate(searchRange);

        var candidates = egg.level()
            .getEntitiesOfClass(
                LivingEntity.class,
                searchBox,
                candidate -> {
                    if (egg.distanceToSqr(candidate) > (searchRange * searchRange)) {
                        return false;
                    }
                    if (!TargetingUtils.faceHuggerTest(egg, candidate)) {
                        return false;
                    }
                    return egg.hasLineOfSight(candidate);
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
