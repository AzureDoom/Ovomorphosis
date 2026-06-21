package mod.azure.xenogenesis.entities.facehugger;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import mod.azure.xenogenesis.ai.actions.*;
import mod.azure.xenogenesis.ai.actions.facehugger.LeapAndAttachAction;
import mod.azure.xenogenesis.ai.core.AiKeys;
import mod.azure.xenogenesis.ai.core.BehaviorNode;
import mod.azure.xenogenesis.ai.core.BehaviorResult;
import mod.azure.xenogenesis.ai.util.CrawlingManager;
import mod.azure.xenogenesis.ai.util.TargetingUtils;

public class FacehuggerTree {

    public static BehaviorNode<FacehuggerEntity> create() {
        var targetSelector = new FacehuggerTargetSelector<>();

        var wander = new WanderAction<FacehuggerEntity>(
            0.14D, // slower, skittery speed
            5, // priority
            6.0D, // wander radius
            60, // min duration ticks
            160 // max duration ticks
        );

        var idle = new IdleAction<FacehuggerEntity>(40, 100, 1);

        var moveToDestination = new MoveToDestinationAction<FacehuggerEntity>(
            1.5D, // stop distance
            0.22D, // speed
            10, // priority
            3.0D, // max leap height
            0.5D, // min leap height
            0.45D, // leap vertical power
            0.55D // leap horizontal power
        );

        var crawlToDestination = new CrawlToDestinationAction<FacehuggerEntity>(
            1.5D,
            0.22D,
            10,
            3.0D,
            0.5D,
            0.45D,
            0.55D
        );

        var moveToTarget = new MoveToTargetAction<FacehuggerEntity>(
            0.6D,
            0.28D,
            20
        );

        var crawlToTarget = new CrawlToTargetAction<FacehuggerEntity>(
            0.6D,
            0.28D,
            20
        );

        var leapAndAttach = new LeapAndAttachAction<>();

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

            var newTarget = targetSelector.findTarget(facehugger, blackboard);
            if (newTarget != null) {
                blackboard.set(AiKeys.TARGET, newTarget);
                facehugger.setTarget(newTarget);
                currentTarget = newTarget;
            }

            if (currentTarget != null && currentTarget.isAlive()) {

                if (CrawlingManager.shouldUseWallCrawlingToTarget(facehugger, currentTarget)) {
                    return BehaviorResult.run(crawlToTarget, 20);
                }

                var distSqr = facehugger.distanceToSqr(currentTarget);
                if (distSqr <= 4.0D * 4.0D) {
                    return BehaviorResult.run(leapAndAttach, 30);
                }

                return BehaviorResult.run(moveToTarget, 20);
            }

            var destination = blackboard.get(AiKeys.DESTINATION, BlockPos.class);
            if (destination != null) {
                if (CrawlingManager.shouldUseWallCrawlingTo(facehugger, destination)) {
                    return BehaviorResult.run(crawlToDestination, 10);
                }
                return BehaviorResult.run(moveToDestination, 10);
            }

            if (facehugger.getRandom().nextFloat() < 0.3F) {
                return BehaviorResult.run(wander, 5);
            }

            return BehaviorResult.run(idle, 5);
        };
    }
}
