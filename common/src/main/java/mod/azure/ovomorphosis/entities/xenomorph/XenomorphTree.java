package mod.azure.ovomorphosis.entities.xenomorph;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.ai.actions.*;
import mod.azure.ovomorphosis.ai.actions.xenomorph.*;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.BehaviorNode;
import mod.azure.ovomorphosis.ai.core.BehaviorResult;
import mod.azure.ovomorphosis.ai.core.Blackboard;
import mod.azure.ovomorphosis.ai.hive.*;
import mod.azure.ovomorphosis.ai.util.CrawlingManager;
import mod.azure.ovomorphosis.ai.util.HiveMemory;
import mod.azure.ovomorphosis.ai.util.TargetingUtils;
import mod.azure.ovomorphosis.util.ModTags;

public class XenomorphTree {

    public static BehaviorNode<XenomorphEntity> create() {
        var fleeExplosive = new ExplosiveFleeAction<XenomorphEntity>(
            0.46D,
            10.0D,
            20.0D,
            120
        );

        var wander = new WanderAction<XenomorphEntity>(
            0.06D,
            5,
            6.0D,
            60,
            160
        );

        var idle = new IdleAction<XenomorphEntity>(40, 100, 2);

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
            1.2D,
            0.53D,
            20,
            true
        );

        var squadMove = new SquadMoveAction<XenomorphEntity>(
            0.55D,
            2.5D,
            50,
            22
        );

        var swipeCombo = new XenomorphCombatAction<XenomorphEntity>(
            "swipe_combo",
            35,
            100,
            x -> x.animationDispatcher.serverAttack()
        );

        var tailPunish = new TimedAttackAction<XenomorphEntity>(
            "tail_attack",
            45,
            8,
            5,
            110,
            x -> x.animationDispatcher.serverTailAttack()
        );

        var grabAndExecute = new GrabAndExecuteAction<>(
            120,
            x -> x.animationDispatcher.serverExecute()
        );

        var placeResin = new PlaceResinAction<XenomorphEntity>(3, 200);

        var carryToWeb = new CarryToWebAction<XenomorphEntity>(
            115,
            x -> x.animationDispatcher.serverExecute(),
            x -> {}
        );

        var swim = new SwimAction<XenomorphEntity>(200);

        var coordinator = new SimpleTacticalCoordinator<XenomorphEntity>();

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

            var squad = SquadRegistry.get().getOrJoinSquad(xenomorph);

            if (squad != null) {
                var squadTarget = squad.primaryTarget();
                if (squadTarget != null && squadTarget.isAlive()) {
                    if (
                        currentTarget == null || !currentTarget.isAlive()
                            || !currentTarget.getUUID().equals(squadTarget.getUUID())
                    ) {
                        blackboard.set(AiKeys.TARGET, squadTarget);
                        xenomorph.setTarget(squadTarget);
                        currentTarget = squadTarget;
                    }
                }

                var order = coordinator.getOrder(xenomorph, squad);
                blackboard.set(AiKeys.TACTICAL_ORDER, order);

                if (order.hasTarget()) {
                    var orderedTarget = order.target();
                    if (
                        currentTarget == null || !currentTarget.isAlive()
                            || !currentTarget.getUUID().equals(orderedTarget.getUUID())
                    ) {
                        blackboard.set(AiKeys.TARGET, orderedTarget);
                        xenomorph.setTarget(orderedTarget);
                    }
                }
            } else {
                blackboard.set(AiKeys.TACTICAL_ORDER, null);
            }

            currentTarget = blackboard.get(AiKeys.TARGET, LivingEntity.class);

            var destination = blackboard.get(AiKeys.DESTINATION, BlockPos.class);
            if (destination != null && currentTarget == null) {
                return BehaviorResult.run(destinationMove, 25);
            }

            if (currentTarget != null && currentTarget.isAlive()) {
                var yGap = currentTarget.getY() - xenomorph.getY();
                var canReachVert = Math.abs(yGap) <= 2.5D;
                var dangerTarget = currentTarget.getType().is(ModTags.DANGER_ENTITIES);
                var hasMeleeLOS = hasMeleeLineOfSight(xenomorph, currentTarget);
                var canGrab = currentTarget.getType().is(ModTags.XENO_GRAB_BLACKLIST);
                var inMeleeRange = TargetingUtils.isInAttackRange(xenomorph, currentTarget, 2.0D);

                if (
                    canReachVert
                        && inMeleeRange
                        && cooldowns.ready(AiKeys.CARRY_COOLDOWN)
                        && cooldowns.ready(AiKeys.GRAB_COOLDOWN)
                        && cooldowns.ready("swipe_combo")
                        && hasNearbyWebCross(blackboard, xenomorph)
                        && xenomorph.getRandom().nextFloat() < CommonMod
                            .getConfig().entityConfigs.xenomorphConfigs.xenoCarryToResinChance
                ) {
                    return BehaviorResult.run(carryToWeb, 115);
                }

                if (
                    !dangerTarget && !canGrab
                        && canReachVert
                        && inMeleeRange
                        && cooldowns.ready(AiKeys.GRAB_COOLDOWN)
                        && cooldowns.ready("swipe_combo")
                        && cooldowns.ready("tail_attack")
                        && xenomorph.getRandom().nextFloat() < CommonMod
                            .getConfig().entityConfigs.xenomorphConfigs.xenoExecuteChance
                ) {
                    return BehaviorResult.run(grabAndExecute, 120);
                }

                if (
                    canReachVert
                        && hasMeleeLOS
                        && TargetingUtils.isInAttackRange(xenomorph, currentTarget, 1.8D)
                        && cooldowns.ready("tail_attack")
                ) {
                    return BehaviorResult.run(tailPunish, 110);
                }

                if (
                    canReachVert
                        && hasMeleeLOS
                        && TargetingUtils.isInAttackRange(xenomorph, currentTarget, 2.5D)
                        && cooldowns.ready("swipe_combo")
                ) {
                    return BehaviorResult.run(swipeCombo, 100);
                }

                var order = blackboard.get(AiKeys.TACTICAL_ORDER, mod.azure.ovomorphosis.ai.hive.TacticalOrder.class);
                if (order != null && order.hasDestination()) {
                    if (!inMeleeRange) {
                        return BehaviorResult.run(squadMove, 22);
                    }
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

            if (xenomorph.isInWater() || xenomorph.isInLava()) {
                return BehaviorResult.run(swim, 200);
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

            if (!cooldowns.isOnCooldown(AiKeys.PASSIVE_DECISION)) {
                cooldowns.set(AiKeys.PASSIVE_DECISION, 180);
                if (xenomorph.getRandom().nextFloat() < 0.1F) {
                    return BehaviorResult.run(wander, 5);
                }
                return BehaviorResult.run(idle, 5);
            }

            return BehaviorResult.run(idle, 5);
        };
    }

    private static boolean hasNearbyWebCross(Blackboard blackboard, XenomorphEntity xenomorph) {
        var memory = blackboard.get(AiKeys.HIVE_MEMORY, HiveMemory.class);
        if (memory == null)
            return false;
        return memory.findNearestWebCross(xenomorph.level(), xenomorph.blockPosition(), 80.0D).isPresent();
    }

    private static BlockPos findNearbyGroundPos(XenomorphEntity mob) {
        var level = mob.level();
        var origin = mob.blockPosition();

        for (var dy = 1; dy <= 16; dy++) {
            var candidate = origin.below(dy);
            var below = candidate.below();
            if (
                level.getBlockState(candidate).getCollisionShape(level, candidate).isEmpty()
                    && !level.getBlockState(below).getCollisionShape(level, below).isEmpty()
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
                    if (
                        level.getBlockState(candidate).getCollisionShape(level, candidate).isEmpty()
                            && !level.getBlockState(below).getCollisionShape(level, below).isEmpty()
                    ) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private static boolean hasMeleeLineOfSight(Mob mob, LivingEntity target) {
        var hit = mob.level()
            .clip(
                new ClipContext(
                    mob.getEyePosition(),
                    target.getEyePosition(),
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    mob
                )
            );
        return hit.getType() == HitResult.Type.MISS;
    }
}
