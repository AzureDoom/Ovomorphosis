package mod.azure.xenogenesis.entities.xenomorph;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import mod.azure.xenogenesis.CommonMod;
import mod.azure.xenogenesis.ai.actions.*;
import mod.azure.xenogenesis.ai.actions.xenomorph.CarryToWebAction;
import mod.azure.xenogenesis.ai.actions.xenomorph.GrabAndExecuteAction;
import mod.azure.xenogenesis.ai.actions.xenomorph.PlaceResinAction;
import mod.azure.xenogenesis.ai.core.AiKeys;
import mod.azure.xenogenesis.ai.core.BehaviorNode;
import mod.azure.xenogenesis.ai.core.BehaviorResult;
import mod.azure.xenogenesis.ai.core.Blackboard;
import mod.azure.xenogenesis.ai.util.CrawlingManager;
import mod.azure.xenogenesis.ai.util.HiveMemory;
import mod.azure.xenogenesis.ai.util.TargetingUtils;
import mod.azure.xenogenesis.util.ModTags;

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
            0.85D,
            true
        );

        var moveToTargetCombat = new MoveToTargetAction<XenomorphEntity>(
            1.8D,
            0.53D,
            20,
            true
        );

        var normalAttack = new TimedAttackAction<XenomorphEntity>(
            "normal_attack",
            40,
            8,
            5,
            100,
            x -> x.animationDispatcher.serverAttack()
        );

        var heavyAttack = new TimedAttackAction<XenomorphEntity>(
            "tail_attack",
            40,
            8,
            5,
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
                return BehaviorResult.run(destinationMove, 25);
            }

            if (currentTarget != null && currentTarget.isAlive()) {
                var yGap = currentTarget.getY() - xenomorph.getY();
                var canReachVertically = Math.abs(yGap) <= 2.5D;
                var dangerTarget = currentTarget.getType().is(ModTags.DANGER_ENTITIES);

                if (
                    canReachVertically
                        && TargetingUtils.isInAttackRange(xenomorph, currentTarget, 2.0D)
                        && cooldowns.ready(AiKeys.CARRY_COOLDOWN)
                        && cooldowns.ready(AiKeys.GRAB_COOLDOWN)
                        && cooldowns.ready("normal_attack")
                        && hasNearbyWebCross(blackboard, xenomorph)
                        && xenomorph.getRandom().nextFloat() < CommonMod
                            .getConfig().entityConfigs.xenomorphConfigs.xenoCarryToResinChance
                ) {
                    return BehaviorResult.run(carryToWeb, 115);
                }

                if (
                    !dangerTarget
                        && canReachVertically
                        && TargetingUtils.isInAttackRange(xenomorph, currentTarget, 2.0D)
                        && cooldowns.ready(AiKeys.GRAB_COOLDOWN)
                        && cooldowns.ready("normal_attack")
                        && cooldowns.ready("heavy_attack")
                        && xenomorph.getRandom().nextFloat() < CommonMod
                            .getConfig().entityConfigs.xenomorphConfigs.xenoExecuteChance
                ) {
                    return BehaviorResult.run(grabAndExecute, 120);
                }

                if (
                    canReachVertically
                        && TargetingUtils.isInAttackRange(xenomorph, currentTarget, 1.8D)
                        && cooldowns.ready("tail_attack")
                ) {
                    return BehaviorResult.run(heavyAttack, 110);
                }

                if (
                    canReachVertically
                        && TargetingUtils.isInAttackRange(xenomorph, currentTarget, 1.8D)
                        && cooldowns.ready("normal_attack")
                ) {
                    return BehaviorResult.run(normalAttack, 100);
                }

                return BehaviorResult.run(moveToTargetCombat, 20);
            }

            if (
                cooldowns.ready(AiKeys.RESIN_PLACE_COOLDOWN)
                    && xenomorph.getRandom().nextFloat() < 0.4F
                    && !CrawlingManager.wasRecentlyWallCrawling(xenomorph)
            ) {
                return BehaviorResult.run(placeResin, 3);
            }

            if (CrawlingManager.wasRecentlyWallCrawling(xenomorph)) {
                if (blackboard.get(AiKeys.DESTINATION, BlockPos.class) == null) {
                    var groundPos = findNearbyGroundPos(xenomorph);
                    if (groundPos != null) {
                        blackboard.set(AiKeys.DESTINATION, groundPos);
                    }
                }
                return BehaviorResult.run(destinationMove, 5);
            }

            if (xenomorph.getRandom().nextFloat() < 0.3F) {
                return BehaviorResult.run(wander, 5);
            }

            return BehaviorResult.run(idle, 5);
        };
    }

    private static boolean hasNearbyWebCross(
        Blackboard blackboard,
        XenomorphEntity xenomorph
    ) {
        var memory = blackboard.get(AiKeys.HIVE_MEMORY, HiveMemory.class);
        if (memory == null)
            return false;
        return memory.findNearestWebCross(xenomorph.level(), xenomorph.blockPosition(), 80.0D).isPresent();
    }

    /**
     * Finds a ground-level block position near the mob suitable for descending to after losing a target while
     * wall-crawling. Searches downward from the mob's current position.
     */
    private static BlockPos findNearbyGroundPos(XenomorphEntity mob) {
        var level = mob.level();
        var origin = mob.blockPosition();

        for (var dy = 1; dy <= 16; dy++) {
            var candidate = origin.below(dy);
            var below = candidate.below();
            var stateCandidate = level.getBlockState(candidate);
            var stateBelow = level.getBlockState(below);

            if (
                stateCandidate.getCollisionShape(level, candidate).isEmpty()
                    && !stateBelow.getCollisionShape(level, below).isEmpty()
            ) {
                return candidate;
            }
        }

        int[][] lateralDirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
        for (var radius = 1; radius <= 4; radius++) {
            for (var dir : lateralDirs) {
                var lateral = origin.offset(dir[0] * radius, 0, dir[1] * radius);
                for (var dy = 0; dy <= 16; dy++) {
                    var candidate = lateral.below(dy);
                    var below = candidate.below();
                    var stateCandidate = level.getBlockState(candidate);
                    var stateBelow = level.getBlockState(below);

                    if (
                        stateCandidate.getCollisionShape(level, candidate).isEmpty()
                            && !stateBelow.getCollisionShape(level, below).isEmpty()
                    ) {
                        return candidate;
                    }
                }
            }
        }

        return null;
    }
}
