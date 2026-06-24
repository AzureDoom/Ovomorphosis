package mod.azure.ovomorphosis.entities.xenomorph;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.ai.actions.*;
import mod.azure.ovomorphosis.ai.actions.xenomorph.*;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.BehaviorNode;
import mod.azure.ovomorphosis.ai.core.BehaviorResult;
import mod.azure.ovomorphosis.ai.core.Blackboard;
import mod.azure.ovomorphosis.ai.util.CrawlingManager;
import mod.azure.ovomorphosis.ai.util.HiveMemory;
import mod.azure.ovomorphosis.ai.util.TargetingUtils;
import mod.azure.ovomorphosis.util.ModTags;

public class XenomorphTree {

    private static final double WEB_NEAR_SQ = 20.0 * 20.0;

    private static final double WEB_FAR_SQ = 50.0 * 50.0;

    public static BehaviorNode<XenomorphEntity> create() {
        var fleeExplosive = new ExplosiveFleeAction<XenomorphEntity>(0.46D, 10.0D, 20.0D, 120);

        var wander = new WanderAction<XenomorphEntity>(0.06D, 10, 6.0D, 60, 160, true);

        var idle = new IdleAction<XenomorphEntity>(40, 100, 2);

        var destinationMove = new MoveToDestinationAction<XenomorphEntity>(2.5D, 0.3D, 25, true);

        var moveToTargetCombat = new MoveToTargetAction<XenomorphEntity>(1.2D, 0.53D, 20, true);

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
            x -> x.animationDispatcher.clientIdle()
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
                var cannotGrab = currentTarget.getType().is(ModTags.XENO_GRAB_BLACKLIST);
                var inMeleeRange = TargetingUtils.isInAttackRange(xenomorph, currentTarget, 2.0D);
                var combatCooldownsFree = cooldowns.ready("swipe_combo") && cooldowns.ready("tail_attack");

                if (
                    canReachVert
                        && inMeleeRange
                        && !dangerTarget
                        && cooldowns.ready(AiKeys.CARRY_COOLDOWN)
                        && cooldowns.ready(AiKeys.GRAB_COOLDOWN)
                        && cooldowns.ready("swipe_combo")
                ) {
                    var verdict = evaluateCarryOrKill(xenomorph, blackboard);

                    if (verdict == CombatVerdict.CARRY) {
                        return BehaviorResult.run(carryToWeb, 115);
                    }
                }

                if (
                    !dangerTarget && !cannotGrab
                        && canReachVert
                        && inMeleeRange
                        && cooldowns.ready(AiKeys.GRAB_COOLDOWN)
                        && combatCooldownsFree
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

    private enum CombatVerdict {
        CARRY,
        KILL
    }

    /**
     * Scores carrying the victim to a web cross against killing it outright, using distance to the nearest web and the
     * configured carry chance as inputs.
     * <h3>Scoring model</h3>
     * <ul>
     * <li><b>Near web (≤ {@link #WEB_NEAR_SQ}):</b> carry score is high (80–100). The trip is short, the hive benefits,
     * and carry is chosen unless the config chance roll fails.</li>
     * <li><b>Mid-range web (≤ {@link #WEB_FAR_SQ}):</b> carry score falls linearly toward ~40. Both options are
     * competitive; the config chance roll acts as the tiebreaker.</li>
     * <li><b>Far/no web (> {@link #WEB_FAR_SQ}):</b> carry score is low (≤ 20). Kill wins unless the config chance is
     * very high <em>and</em> the roll is favourable.</li>
     * </ul>
     * The kill score is fixed at 50 so that it behaves as the neutral baseline — carry only wins when the web is
     * genuinely close or the config strongly favors it.
     */
    private static CombatVerdict evaluateCarryOrKill(
        XenomorphEntity xenomorph,
        Blackboard blackboard
    ) {
        var memory = blackboard.get(AiKeys.HIVE_MEMORY, HiveMemory.class);
        var configCarryChance = CommonMod
            .getConfig().entityConfigs.xenomorphConfigs.xenoCarryToResinChance;

        var webDistSq = Double.MAX_VALUE;
        if (memory != null) {
            var nearest = memory.findNearestWebCross(
                xenomorph.level(),
                xenomorph.blockPosition(),
                80.0D
            );
            if (nearest.isPresent()) {
                webDistSq = xenomorph.blockPosition().distSqr(nearest.get());
            }
        }

        float carryScore;
        if (webDistSq <= WEB_NEAR_SQ) {
            carryScore = 100f - (float) (webDistSq / WEB_NEAR_SQ) * 20f;
        } else if (webDistSq <= WEB_FAR_SQ) {
            var t = (webDistSq - WEB_NEAR_SQ) / (WEB_FAR_SQ - WEB_NEAR_SQ);
            carryScore = 80f - (float) t * 40f;
        } else {
            carryScore = Math.max(0f, 20f * configCarryChance);
        }

        carryScore *= configCarryChance;

        var killScore = 50f + xenomorph.getRandom().nextFloat() * 10f;

        return carryScore >= killScore ? CombatVerdict.CARRY : CombatVerdict.KILL;
    }
}
