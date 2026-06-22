package mod.azure.ovomorphosis.entities.facehugger;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import mod.azure.ovomorphosis.ai.actions.*;
import mod.azure.ovomorphosis.ai.actions.facehugger.LeapAndAttachAction;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.BehaviorNode;
import mod.azure.ovomorphosis.ai.core.BehaviorResult;
import mod.azure.ovomorphosis.ai.util.TargetingUtils;

public class FacehuggerTree {

    public static BehaviorNode<FacehuggerEntity> create() {
        var wander = new WanderAction<FacehuggerEntity>(
            0.14D,
            5,
            6.0D,
            60,
            160
        );

        var idle = new IdleAction<FacehuggerEntity>(40, 100, 1);

        var moveToDestination = new MoveToDestinationAction<FacehuggerEntity>(
            0.6D,
            0.22D,
            10,
            3.0D,
            0.5D,
            0.45D,
            0.55D,
            true
        );

        var moveToTarget = new MoveToTargetAction<FacehuggerEntity>(
            0.6D,
            0.28D,
            20,
            true
        );

        var leapAndAttach = new LeapAndAttachAction<>();

        var swim = new SwimAction<FacehuggerEntity>(200);

        return (facehugger, blackboard, cooldowns) -> {

            if (facehugger.isInfertile() || !facehugger.isAlive()) {
                return BehaviorResult.run(idle, 5);
            }

            if (facehugger.isAttachedToHost()) {
                return BehaviorResult.run(leapAndAttach, 30);
            }

            var currentTarget = blackboard.get(AiKeys.TARGET, LivingEntity.class);
            if (
                currentTarget != null && (!currentTarget.isAlive()
                    || !TargetingUtils.faceHuggerTest(facehugger, currentTarget))
            ) {
                blackboard.set(AiKeys.TARGET, null);
                facehugger.setTarget(null);
                currentTarget = null;
            }

            if (currentTarget != null && currentTarget.isAlive()) {
                var distSqr = facehugger.distanceToSqr(currentTarget);
                if (distSqr <= 4.0D * 4.0D || !facehugger.onGround()) {
                    return BehaviorResult.run(leapAndAttach, 30);
                }
            }

            if (facehugger.isInWater() || facehugger.isInLava()) {
                return BehaviorResult.run(swim, 200);
            }

            var destination = blackboard.get(AiKeys.DESTINATION, BlockPos.class);
            if (destination != null) {
                return BehaviorResult.run(moveToDestination, 10);
            }

            if (!cooldowns.isOnCooldown(AiKeys.PASSIVE_DECISION)) {
                cooldowns.set(AiKeys.PASSIVE_DECISION, 180);
                if (facehugger.getRandom().nextFloat() < 0.1F) {
                    return BehaviorResult.run(wander, 5);
                }
                return BehaviorResult.run(idle, 5);
            }

            return BehaviorResult.run(idle, 5);
        };
    }
}
