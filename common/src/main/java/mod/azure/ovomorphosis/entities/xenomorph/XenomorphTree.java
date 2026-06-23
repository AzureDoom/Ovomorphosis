package mod.azure.ovomorphosis.entities.xenomorph;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.ai.actions.*;
import mod.azure.ovomorphosis.ai.actions.xenomorph.*;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.BehaviorNode;
import mod.azure.ovomorphosis.ai.core.BehaviorResult;
import mod.azure.ovomorphosis.ai.util.CrawlingManager;
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
            10,
            6.0D,
            60,
            160,
            true
        );

        var idle = new IdleAction<XenomorphEntity>(40, 100, 2);

        var destinationMove = new MoveToDestinationAction<XenomorphEntity>(
            2.5D,
            0.3D,
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

        var breakToTarget = new BreakToTargetAction<>();

        var destroyLight = new DestroyLightSourceAction<>(5);

        return (xenomorph, blackboard, cooldowns) -> {

            var currentTarget = blackboard.get(AiKeys.TARGET, LivingEntity.class);
            if (currentTarget != null && !currentTarget.isAlive()) {
                blackboard.set(AiKeys.TARGET, null);
                xenomorph.setTarget(null);
            }

            if (fleeExplosive.hasNearbyExplosive(xenomorph)) {
                return BehaviorResult.run(fleeExplosive, 120);
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
                var hasMeleeLOS = TargetingUtils.hasMeleeLineOfSight(xenomorph, currentTarget);
                var canGrab = currentTarget.getType().is(ModTags.XENO_GRAB_BLACKLIST);
                var inMeleeRange = TargetingUtils.isInAttackRange(xenomorph, currentTarget, 2.0D);

                if (
                    canReachVert
                        && inMeleeRange
                        && cooldowns.ready(AiKeys.CARRY_COOLDOWN)
                        && cooldowns.ready(AiKeys.GRAB_COOLDOWN)
                        && cooldowns.ready("swipe_combo")
                        && TargetingUtils.hasNearbyWebCross(blackboard, xenomorph)
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

                if (blackboard.has(AiKeys.BREAK_TO_TARGET_TRIGGER)) {
                    return BehaviorResult.run(breakToTarget, 15);
                }

                return BehaviorResult.run(moveToTargetCombat, 20);
            }

            if (xenomorph.isInWater() || xenomorph.isInLava()) {
                return BehaviorResult.run(swim, 200);
            }

            if (CrawlingManager.wasRecentlyWallCrawling(xenomorph)) {
                if (blackboard.get(AiKeys.DESTINATION, BlockPos.class) == null) {
                    var groundPos = TargetingUtils.findNearbyGroundPos(xenomorph);
                    if (groundPos != null) {
                        blackboard.set(AiKeys.DESTINATION, groundPos);
                    }
                }
                return BehaviorResult.run(destinationMove, 5);
            }

            if (
                cooldowns.ready(AiKeys.RESIN_PLACE_COOLDOWN)
                    && xenomorph.getRandom().nextFloat() < 0.4F
                    && !CrawlingManager.wasRecentlyWallCrawling(xenomorph)
            ) {
                return BehaviorResult.run(placeResin, 9);
            }

            if (!cooldowns.isOnCooldown(AiKeys.PASSIVE_DECISION)) {
                cooldowns.set(AiKeys.PASSIVE_DECISION, 180);

                var roll = xenomorph.getRandom().nextFloat();
                if (roll < 0.9F && cooldowns.ready(AiKeys.LIGHT_SCAN_COOLDOWN)) {
                    return BehaviorResult.run(destroyLight, 10);
                }
                if (roll < 0.65F) {
                    return BehaviorResult.run(wander, 10);
                }
                return BehaviorResult.run(idle, 8);
            }

            return BehaviorResult.run(idle, 8);
        };
    }
}
