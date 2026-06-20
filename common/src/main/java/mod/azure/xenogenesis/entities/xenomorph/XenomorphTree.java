package mod.azure.xenogenesis.entities.xenomorph;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import mod.azure.xenogenesis.ai.actions.*;
import mod.azure.xenogenesis.ai.actions.xenomorph.CarryToWebAction;
import mod.azure.xenogenesis.ai.actions.xenomorph.GrabAndExecuteAction;
import mod.azure.xenogenesis.ai.actions.xenomorph.PlaceResinAction;
import mod.azure.xenogenesis.ai.core.AiKeys;
import mod.azure.xenogenesis.ai.core.BehaviorNode;
import mod.azure.xenogenesis.ai.core.BehaviorResult;
import mod.azure.xenogenesis.ai.util.CrawlingManager;
import mod.azure.xenogenesis.ai.util.HiveMemory;
import mod.azure.xenogenesis.ai.util.TargetingUtils;

public class XenomorphTree {

    public static BehaviorNode<XenomorphEntity> create() {
        var fleeExplosive = new ExplosiveFleeAction<XenomorphEntity>(
            0.46D,
            10.0D,
            20.0D,
            120
        );

        var wander = new WanderAction<XenomorphEntity>(
            0.07D,
            5,
            6.0D,
            60,
            160
        );

        var idle = new IdleAction<XenomorphEntity>(40, 100, 1);

        var destinationMove = new MoveToDestinationAction<XenomorphEntity>(
            2.5D,
            0.1D,
            25,
            5.0D,
            1.0D,
            0.55D,
            0.85D
        );

        var crawlDestinationMove = new CrawlToDestinationAction<XenomorphEntity>(
            2.5D,
            0.1D,
            25,
            5.0D,
            1.0D,
            0.55D,
            0.85D
        );

        var moveToTargetCombat = new MoveToTargetAction<XenomorphEntity>(
            2.5D,
            0.47D,
            20
        );

        var crawlToTargetCombat = new CrawlToTargetAction<XenomorphEntity>(
            2.5D,
            0.47D,
            20
        );

        var normalAttack = new TimedAttackAction<XenomorphEntity>(
            "normal_attack",
            40,
            14,
            7,
            100,
            x -> x.animationDispatcher.serverAttack()
        );

        var heavyAttack = new TimedAttackAction<XenomorphEntity>(
            "tail_attack",
            40,
            14,
            7,
            110,
            x -> x.animationDispatcher.serverTailAttack()
        );

        var grabAndExecute = new GrabAndExecuteAction<XenomorphEntity>(
            120,
            x -> x.animationDispatcher.serverExecute()
        );

        var placeResin = new PlaceResinAction<XenomorphEntity>(3, 200);

        var carryToWeb = new CarryToWebAction<XenomorphEntity>(
            115,
            x -> x.animationDispatcher.serverExecute(),
            x -> {}
        );

        return (xenomorph, blackboard, cooldowns) -> {

            var currentTarget = blackboard.get(AiKeys.TARGET, LivingEntity.class);
            if (currentTarget != null && !currentTarget.isAlive()) {
                blackboard.set(AiKeys.TARGET, null);
                xenomorph.setTarget(null);
                currentTarget = null;
            }

            if (fleeExplosive.hasNearbyExplosive(xenomorph)) {
                return BehaviorResult.run(fleeExplosive, 120);
            }

            var destination = blackboard.get(AiKeys.DESTINATION, BlockPos.class);
            if (destination != null && currentTarget == null) {
                if (CrawlingManager.shouldUseWallCrawlingTo(xenomorph, destination)) {
                    return BehaviorResult.run(crawlDestinationMove, 25);
                }
                return BehaviorResult.run(destinationMove, 25);
            }

            if (currentTarget != null && currentTarget.isAlive()) {
                var yGap = currentTarget.getY() - xenomorph.getY();
                var canReachVertically = yGap <= 1.5D;

                if (
                    canReachVertically
                        && TargetingUtils.isInAttackRange(xenomorph, currentTarget, 1.5D)
                        && cooldowns.ready(AiKeys.CARRY_COOLDOWN)
                        && cooldowns.ready(AiKeys.GRAB_COOLDOWN)
                        && cooldowns.ready("normal_attack")
                        && hasNearbyWebCross(blackboard, xenomorph)
                        && xenomorph.getRandom().nextFloat() < 0.08F
                ) {
                    return BehaviorResult.run(carryToWeb, 115);
                }

                if (
                    canReachVertically
                        && TargetingUtils.isInAttackRange(xenomorph, currentTarget, 1.5D)
                        && cooldowns.ready(AiKeys.GRAB_COOLDOWN)
                        && cooldowns.ready("normal_attack")
                        && cooldowns.ready("heavy_attack")
                        && xenomorph.getRandom().nextFloat() < 0.05F
                ) {
                    return BehaviorResult.run(grabAndExecute, 120);
                }

                if (
                    canReachVertically
                        && TargetingUtils.isInAttackRange(xenomorph, currentTarget, 1.25D)
                        && cooldowns.ready("tail_attack")
                ) {
                    return BehaviorResult.run(heavyAttack, 110);
                }

                if (
                    canReachVertically
                        && TargetingUtils.isInAttackRange(xenomorph, currentTarget, 1.0D)
                        && cooldowns.ready("normal_attack")
                ) {
                    return BehaviorResult.run(normalAttack, 100);
                }

                if (CrawlingManager.shouldUseWallCrawlingToTarget(xenomorph, currentTarget)) {
                    return BehaviorResult.run(crawlToTargetCombat, 20);
                }
                return BehaviorResult.run(moveToTargetCombat, 20);
            }

            if (
                cooldowns.ready(AiKeys.RESIN_PLACE_COOLDOWN)
                    && xenomorph.getRandom().nextFloat() < 0.4F
            ) {
                return BehaviorResult.run(placeResin, 3);
            }

            if (xenomorph.getRandom().nextFloat() < 0.3F) {
                return BehaviorResult.run(wander, 5);
            }

            return BehaviorResult.run(idle, 5);
        };
    }

    private static boolean hasNearbyWebCross(
        mod.azure.xenogenesis.ai.core.Blackboard blackboard,
        XenomorphEntity xenomorph
    ) {
        var memory = blackboard.get(AiKeys.HIVE_MEMORY, HiveMemory.class);
        if (memory == null)
            return false;
        return memory.findNearestWebCross(xenomorph.level(), xenomorph.blockPosition(), 80.0D).isPresent();
    }
}
