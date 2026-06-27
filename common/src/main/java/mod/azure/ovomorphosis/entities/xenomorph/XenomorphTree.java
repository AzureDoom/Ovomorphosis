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
import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.ai.goap.XenoRole;
import mod.azure.ovomorphosis.ai.util.CrawlingMovementManager;
import mod.azure.ovomorphosis.ai.util.HiveMemory;
import mod.azure.ovomorphosis.ai.util.TargetingUtils;
import mod.azure.ovomorphosis.util.ModTags;

/**
 * Behavior tree for {@link XenomorphEntity}.
 * <h3>GOAP integration</h3> The tree reads {@link AiKeys#ACTIVE_GOAL_TYPE} set by {@link XenomorphGoalPlanner} and uses
 * it to unlock or bias branches that would otherwise be gated by simple conditions. This means:
 * <ul>
 * <li>{@link AiGoalType#BREAK_OBSTACLE} explicitly enables the break-to-target branch even if the mob hasn't registered
 * stuck ticks yet — the planner decided breaking is needed based on failure feedback, and the tree honors that.</li>
 * <li>{@link AiGoalType#KILL_LIGHTS} enables the destroy-light branch at full priority.</li>
 * <li>{@link AiGoalType#EXPAND_HIVE} enables resin placement at higher than usual frequency.</li>
 * <li>{@link AiGoalType#RETREAT_TO_RESIN} routes to a destination-move toward the nearest web.</li>
 * <li>{@link AiGoalType#AMBUSH_TARGET} uses lower-speed cautious movement instead of sprint.</li>
 * <li>{@link AiGoalType#DEFEND_HIVE} skips all cooldown checks and goes straight to combat.</li>
 * </ul>
 * <h3>New actions</h3>
 * <ul>
 * <li>{@link DodgeProjectileAction} — highest priority reactive branch, fires before everything else.</li>
 * <li>{@link FleeFireAction} — fires after explosive flee, before combat.</li>
 * <li>{@link LungeAction} — fires during combat when target is kiting (mid-range, moving away).</li>
 * </ul>
 */
public class XenomorphTree {

    private static final double WEB_NEAR_SQ = 20.0 * 20.0;

    private static final double WEB_FAR_SQ = 50.0 * 50.0;

    public static BehaviorNode<XenomorphEntity> create() {
        var dodge = new DodgeProjectileAction<XenomorphEntity>(130);
        var fleeFire = new FleeFireAction<XenomorphEntity>(125);
        var fleeExplosive = new ExplosiveFleeAction<XenomorphEntity>(0.46D, 10.0D, 20.0D, 120);
        var destinationMove = new MoveToDestinationAction<XenomorphEntity>(0.6D, 0.3D, 25, true);
        var moveToTargetCombat = new MoveToTargetAction<XenomorphEntity>(1.2D, 0.53D, 20, true);
        var moveToTargetAmbush = new MoveToTargetAction<XenomorphEntity>(0.6D, 0.22D, 18, false);
        var swim = new SwimAction<XenomorphEntity>(200);

        var lunge = new LungeAction<XenomorphEntity>(
            105,
            x -> x.animationDispatcher.serverWindUp(),
            x -> x.animationDispatcher.clientInAir()
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
        var carryToWeb = new CarryToWebAction<XenomorphEntity>(
            115,
            x -> x.animationDispatcher.serverExecute(),
            x -> x.animationDispatcher.clientIdle()
        );

        var placeResin = new PlaceResinAction<XenomorphEntity>(3, 200);
        var destroyLight = new DestroyLightSourceAction<XenomorphEntity>(5);
        var breakToTarget = new BreakToTargetAction<XenomorphEntity>();
        var wander = new WanderAction<XenomorphEntity>(0.06D, 10, 6.0D, 60, 160, true);
        var idle = new IdleAction<XenomorphEntity>(40, 100, 2);

        return (xenomorph, blackboard, cooldowns) -> {

            var currentTarget = blackboard.get(AiKeys.TARGET, LivingEntity.class);
            if (currentTarget != null && !currentTarget.isAlive()) {
                blackboard.set(AiKeys.TARGET, null);
                xenomorph.setTarget(null);
                currentTarget = null;
            }

            var goalType = blackboard.get(AiKeys.ACTIVE_GOAL_TYPE, AiGoalType.class);
            if (goalType == null)
                goalType = AiGoalType.WANDER;

            var role = blackboard.get(AiKeys.XENO_ROLE, XenoRole.class);
            if (role == null)
                role = XenoRole.IDLE;

            if (cooldowns.ready(AiKeys.DODGE_COOLDOWN) && dodge.hasIncomingProjectile(xenomorph)) {
                return BehaviorResult.run(dodge, 130);
            }

            {
                var fleeCooldownExpiry = blackboard.get(AiKeys.FIRE_FLEE_COOLDOWN, Integer.class);
                var currentTick = (int) xenomorph.level().getGameTime();
                var fleeOnCooldown = fleeCooldownExpiry != null && currentTick < fleeCooldownExpiry;

                if (!fleeOnCooldown) {
                    var fireTolerance = blackboard.get(AiKeys.FIRE_TOLERANCE, Float.class);
                    var fireToleranceVal = fireTolerance != null ? fireTolerance : 0f;
                    var hasNearbyFire = FleeFireAction.shouldFleefire(xenomorph);

                    if (xenomorph.isOnFire()) {
                        fireToleranceVal = FleeFireAction.MAX_TOLERANCE;
                        blackboard.set(AiKeys.FIRE_TOLERANCE, fireToleranceVal);
                        blackboard.set(AiKeys.FIRE_FLEE_COOLDOWN, null);
                        return BehaviorResult.run(fleeFire, 125);
                    } else if (hasNearbyFire) {
                        fireToleranceVal = Math.min(
                            FleeFireAction.MAX_TOLERANCE,
                            fireToleranceVal + FleeFireAction.TOLERANCE_GAIN_RATE
                        );
                        blackboard.set(AiKeys.FIRE_TOLERANCE, fireToleranceVal);

                        if (fireToleranceVal >= FleeFireAction.TOLERANCE_THRESHOLD) {
                            return BehaviorResult.run(fleeFire, 125);
                        }
                    } else if (fireToleranceVal > 0f) {
                        fireToleranceVal = Math.max(
                            0f,
                            fireToleranceVal - FleeFireAction.TOLERANCE_DRAIN_RATE
                        );
                        blackboard.set(AiKeys.FIRE_TOLERANCE, fireToleranceVal);
                    }
                }
            }

            if (fleeExplosive.hasNearbyExplosive(xenomorph)) {
                return BehaviorResult.run(fleeExplosive, 120);
            }

            if (goalType == AiGoalType.RETREAT_TO_RESIN) {
                var dest = blackboard.get(AiKeys.GOAL_DESTINATION, BlockPos.class);
                if (dest != null) {
                    blackboard.set(AiKeys.DESTINATION, dest);
                    return BehaviorResult.run(destinationMove, 90);
                }
            }

            if (goalType == AiGoalType.SEEK_DARKNESS || goalType == AiGoalType.AMBUSH_FROM_DARKNESS) {
                return BehaviorResult.run(wander, 11);
            }

            var destination = blackboard.get(AiKeys.DESTINATION, BlockPos.class);
            if (destination != null && currentTarget == null) {
                return BehaviorResult.run(destinationMove, 25);
            }

            if (goalType == AiGoalType.INVESTIGATE && currentTarget == null) {
                var dest = blackboard.get(AiKeys.GOAL_DESTINATION, BlockPos.class);
                if (dest != null) {
                    blackboard.set(AiKeys.DESTINATION, dest);
                    return BehaviorResult.run(destinationMove, 22);
                }
            }

            if (currentTarget != null && currentTarget.isAlive()) {
                var targetIsFireUser = Boolean.TRUE.equals(blackboard.get(AiKeys.TARGET_IS_FIRE_USER, Boolean.class));
                var fireDangerActive = FleeFireAction.isFireDangerActive(
                    blackboard,
                    (int) xenomorph.level().getGameTime()
                );
                var yGap = currentTarget.getY() - xenomorph.getY();
                var canReachVert = Math.abs(yGap) <= 2.5D;
                var dangerTarget = currentTarget.getType().is(ModTags.DANGER_ENTITIES);
                var hasMeleeLOS = TargetingUtils.hasMeleeLineOfSight(xenomorph, currentTarget);
                var cannotGrab = currentTarget.getType().is(ModTags.XENO_GRAB_BLACKLIST);
                var inMeleeRange = TargetingUtils.isInAttackRange(xenomorph, currentTarget, 2.0D);
                var combatCoolsFree = cooldowns.ready("swipe_combo") && cooldowns.ready("tail_attack");
                var defending = goalType == AiGoalType.DEFEND_HIVE;

                if (
                    canReachVert && inMeleeRange && !dangerTarget
                        && (defending || (cooldowns.ready(AiKeys.CARRY_COOLDOWN)
                            && cooldowns.ready(AiKeys.GRAB_COOLDOWN)
                            && cooldowns.ready("swipe_combo")))
                ) {
                    var verdict = evaluateCarryOrKill(xenomorph, blackboard);
                    if (verdict == CombatVerdict.CARRY) {
                        return BehaviorResult.run(carryToWeb, 115);
                    }
                }

                if (
                    !dangerTarget && !cannotGrab && canReachVert && inMeleeRange
                        && (defending || (cooldowns.ready(AiKeys.GRAB_COOLDOWN) && combatCoolsFree))
                        && xenomorph.getRandom().nextFloat() < CommonMod
                            .getConfig().entityConfigs.xenomorphConfigs.xenoExecuteChance
                ) {
                    return BehaviorResult.run(grabAndExecute, 120);
                }

                if (
                    canReachVert && hasMeleeLOS
                        && TargetingUtils.isInAttackRange(xenomorph, currentTarget, 1.8D)
                        && (defending || cooldowns.ready("tail_attack"))
                ) {
                    return BehaviorResult.run(tailPunish, 110);
                }

                if (
                    canReachVert && hasMeleeLOS
                        && TargetingUtils.isInAttackRange(xenomorph, currentTarget, 2.5D)
                        && (defending || cooldowns.ready("swipe_combo"))
                ) {
                    return BehaviorResult.run(swipeCombo, 100);
                }

                if (
                    canReachVert
                        && LungeAction.canLunge(xenomorph, currentTarget, cooldowns)
                        && cooldowns.ready("swipe_combo")
                        && !(targetIsFireUser && fireDangerActive)
                ) {
                    return BehaviorResult.run(lunge, 105);
                }

                if (
                    goalType == AiGoalType.BREAK_OBSTACLE
                        || blackboard.has(AiKeys.BREAK_TO_TARGET_TRIGGER)
                ) {
                    return BehaviorResult.run(breakToTarget, 15);
                }

                if (goalType == AiGoalType.AMBUSH_TARGET) {
                    return BehaviorResult.run(moveToTargetAmbush, 18);
                }

                if (targetIsFireUser && fireDangerActive) {
                    return BehaviorResult.run(moveToTargetAmbush, 19);
                }

                if (role == XenoRole.STALKER) {
                    return BehaviorResult.run(moveToTargetAmbush, 19);
                }

                return BehaviorResult.run(moveToTargetCombat, 20);
            }

            if (xenomorph.isInWater() || xenomorph.isInLava()) {
                return BehaviorResult.run(swim, 200);
            }

            if (CrawlingMovementManager.wasRecentlyWallCrawling(xenomorph)) {
                if (blackboard.get(AiKeys.DESTINATION, BlockPos.class) == null) {
                    var groundPos = TargetingUtils.findNearbyGroundPos(xenomorph);
                    if (groundPos != null)
                        blackboard.set(AiKeys.DESTINATION, groundPos);
                }
                return BehaviorResult.run(destinationMove, 5);
            }

            if (
                goalType == AiGoalType.KILL_LIGHTS
                    || (cooldowns.ready(AiKeys.LIGHT_SCAN_COOLDOWN)
                        && xenomorph.getRandom().nextFloat() < 0.9F)
            ) {
                return BehaviorResult.run(destroyLight, 10);
            }

            if (
                goalType == AiGoalType.EXPAND_HIVE
                    || (cooldowns.ready(AiKeys.RESIN_PLACE_COOLDOWN)
                        && xenomorph.getRandom().nextFloat() < 0.4F
                        && !CrawlingMovementManager.wasRecentlyWallCrawling(xenomorph))
            ) {
                return BehaviorResult.run(placeResin, 9);
            }

            if (!cooldowns.isOnCooldown(AiKeys.PASSIVE_DECISION)) {
                cooldowns.set(AiKeys.PASSIVE_DECISION, 180);
                if (xenomorph.getRandom().nextFloat() < 0.65F) {
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

    private static CombatVerdict evaluateCarryOrKill(XenomorphEntity xenomorph, Blackboard blackboard) {
        var memory = blackboard.get(AiKeys.HIVE_MEMORY, HiveMemory.class);
        var configCarryChance = CommonMod
            .getConfig().entityConfigs.xenomorphConfigs.xenoCarryToResinChance;

        var webDistSq = Double.MAX_VALUE;
        if (memory != null) {
            var nearest = memory.findNearestWebCross(xenomorph.level(), xenomorph.blockPosition(), 80.0D);
            if (nearest.isPresent())
                webDistSq = xenomorph.blockPosition().distSqr(nearest.get());
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
