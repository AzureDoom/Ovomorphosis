package mod.azure.ovomorphosis.entities.xenomorph;

import com.azure.azurecortex.action.combat.AttackProfile;
import com.azure.azurecortex.action.combat.AttackSelector;
import com.azure.azurecortex.api.behavior.BehaviorNode;
import com.azure.azurecortex.api.behavior.BehaviorResult;
import com.azure.azurecortex.api.blackboard.Blackboard;
import com.azure.azurecortex.api.blackboard.CommonBlackboardKeys;
import com.azure.azurecortex.navigation.crawl.CrawlController;
import com.azure.azurecortex.runtime.InterruptCategory;
import net.minecraft.tags.FluidTags;

import java.util.List;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.ai.actions.*;
import mod.azure.ovomorphosis.ai.actions.xenomorph.*;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.ai.roles.XenoRole;
import mod.azure.ovomorphosis.ai.util.TargetingUtils;
import mod.azure.ovomorphosis.util.ModTags;

/**
 * Behavior tree for {@link XenomorphEntity}.
 * <h3>GOAP integration</h3> The tree reads {@link CommonBlackboardKeys#ACTIVE_GOAL_TYPE} set by
 * {@link XenomorphGoalPlanner} and uses it to unlock or bias branches that would otherwise be gated by simple
 * conditions. This means:
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

    /**
     * Health fraction at or below which health is treated as a life-threatening emergency capable of preempting a
     * {@link InterruptCategory#LOCKED} action. Deliberately lower than the planner's softer
     * {@code RETREAT_HEALTH_FRACTION} (30%) so the two don't fight: the planner's normal retreat goal handles the
     * common case, and this only engages when things are dire enough to justify breaking through a lock.
     */
    private static final float CRITICAL_HEALTH_FRACTION = 0.15f;

    /**
     * Max local light level a hive position can have and still count as a viable dark hideout. Mirrors
     * {@code XenomorphGoalPlanner.DARK_HAVEN_MAX_LIGHT} so the tree's emergency interrupt and the planner's ordinary
     * retreat goal agree on where "safety" actually is.
     */
    private static final int DARK_HAVEN_MAX_LIGHT = 4;

    /** Squared distance at which a mob heading for a vent entrance is considered to have arrived at it. */
    private static final double VENT_ARRIVAL_RANGE_SQR = 2.0 * 2.0;

    /**
     * Squared distance at which a mob heading for a known nest breach is considered close enough for PlaceResinAction's
     * own local candidate scan to pick it up (that scan's LOCAL_SCAN_RADIUS is 16, so this is comfortably inside that).
     */
    private static final double BREACH_ARRIVAL_RANGE_SQR = 10.0 * 10.0;

    public static BehaviorNode<XenomorphEntity, AiGoalType> create() {
        var dodge = new DodgeProjectileAction<XenomorphEntity, AiGoalType>(130);
        var fleeFire = new FleeFireAction<XenomorphEntity, AiGoalType>(125);
        var fleeExplosive = new ExplosiveFleeAction<XenomorphEntity, AiGoalType>(0.46D, 10.0D, 20.0D, 120);
        var destinationMove = new MoveToDestinationAction<XenomorphEntity, AiGoalType>(0.6D, 0.3D, 25, true);
        var moveToTargetCombat = new MoveToTargetAction<XenomorphEntity, AiGoalType>(1.2D, 0.53D, 20, true);
        var moveToTargetAmbush = new MoveToTargetAction<XenomorphEntity, AiGoalType>(0.6D, 0.22D, 18, true);
        var swim = new SwimAction<XenomorphEntity, AiGoalType>(95);

        var lunge = new LungeAction<XenomorphEntity, AiGoalType>(
            105,
            x -> x.animationDispatcher.serverWindUp(),
            x -> x.animationDispatcher.clientInAir()
        );
        var swipeCombo = new XenomorphCombatAction<XenomorphEntity, AiGoalType>(
            "swipe_combo",
            35,
            100,
            x -> x.animationDispatcher.serverAttack()
        );
        var tailPunish = new TimedAttackAction<XenomorphEntity, AiGoalType>(
            "tail_attack",
            45,
            8,
            5,
            110,
            x -> x.animationDispatcher.serverTailAttack()
        );
        var grabAndExecute = new GrabAndExecuteAction<XenomorphEntity, AiGoalType>(
            120,
            x -> x.animationDispatcher.serverExecute()
        );
        var carryToWeb = new CarryToWebAction<XenomorphEntity, AiGoalType>(
            115,
            x -> x.animationDispatcher.serverExecute(),
            x -> x.animationDispatcher.clientIdle()
        );

        var meleeAttacks = List.of(
            new AttackProfile<>("tail_attack", tailPunish, "tail_attack", 0.0D, 1.8D, 110),
            new AttackProfile<>("swipe_combo", swipeCombo, "swipe_combo", 0.0D, 2.5D, 100)
        );

        var placeResin = new PlaceResinAction<XenomorphEntity, AiGoalType>(3, 100);
        var ventTraversal = new VentTraversalAction<XenomorphEntity, AiGoalType>(90);
        var destroyLight = new DestroyLightSourceAction<XenomorphEntity, AiGoalType>(5);
        var breakToTarget = new BreakToTargetAction<XenomorphEntity>();
        var wander = new WanderAction<XenomorphEntity, AiGoalType>(0.06D, 10, 6.0D, 60, 160, true);
        var idle = new IdleAction<XenomorphEntity, AiGoalType>(40, 100, 2);

        return (xenomorph, blackboard, cooldowns) -> {

            var currentTarget = blackboard.get(CommonBlackboardKeys.TARGET);
            if (currentTarget != null && !currentTarget.isAlive()) {
                blackboard.set(CommonBlackboardKeys.TARGET, null);
                xenomorph.setTarget(null);
                currentTarget = null;
            }

            var goalType = blackboard.get(CommonBlackboardKeys.ACTIVE_GOAL_TYPE);
            if (goalType == null)
                goalType = AiGoalType.WANDER;

            var role = blackboard.get(AiKeys.XENO_ROLE);
            if (role == null)
                role = XenoRole.IDLE;

            if (cooldowns.ready(CommonBlackboardKeys.DODGE_COOLDOWN) && dodge.hasIncomingProjectile(xenomorph)) {
                return BehaviorResult.run(dodge, 130);
            }

            {
                var fleeCooldownExpiry = blackboard.get(CommonBlackboardKeys.FIRE_FLEE_COOLDOWN);
                var currentTick = (int) xenomorph.level().getGameTime();
                var fleeOnCooldown = fleeCooldownExpiry != null && currentTick < fleeCooldownExpiry;

                if (!fleeOnCooldown) {
                    var fireTolerance = blackboard.get(CommonBlackboardKeys.FIRE_TOLERANCE);
                    var fireToleranceVal = fireTolerance != null ? fireTolerance : 0f;
                    var hasNearbyFire = FleeFireAction.shouldFleefire(xenomorph);

                    if (xenomorph.isOnFire()) {
                        fireToleranceVal = FleeFireAction.MAX_TOLERANCE;
                        blackboard.set(CommonBlackboardKeys.FIRE_TOLERANCE, fireToleranceVal);
                        blackboard.set(CommonBlackboardKeys.FIRE_FLEE_COOLDOWN, null);
                        return BehaviorResult.run(fleeFire, 125);
                    } else if (hasNearbyFire) {
                        fireToleranceVal = Math.min(
                            FleeFireAction.MAX_TOLERANCE,
                            fireToleranceVal + FleeFireAction.TOLERANCE_GAIN_RATE
                        );
                        blackboard.set(CommonBlackboardKeys.FIRE_TOLERANCE, fireToleranceVal);

                        if (fireToleranceVal >= FleeFireAction.TOLERANCE_THRESHOLD) {
                            return BehaviorResult.run(fleeFire, 125);
                        }
                    } else if (fireToleranceVal > 0f) {
                        fireToleranceVal = Math.max(
                            0f,
                            fireToleranceVal - FleeFireAction.TOLERANCE_DRAIN_RATE
                        );
                        blackboard.set(CommonBlackboardKeys.FIRE_TOLERANCE, fireToleranceVal);
                    }
                }
            }

            if (fleeExplosive.hasNearbyExplosive(xenomorph)) {
                return BehaviorResult.run(fleeExplosive, 120);
            }

            if (
                xenomorph.getMaxHealth() > 0f
                    && xenomorph.getHealth() <= xenomorph.getMaxHealth() * CRITICAL_HEALTH_FRACTION
            ) {
                var memory = blackboard.get(AiKeys.HIVE_MEMORY);
                var safeHaven = memory != null
                    ? memory.findNearestDarkOwnedWebCross(
                        xenomorph.level(),
                        xenomorph.blockPosition(),
                        80.0D,
                        DARK_HAVEN_MAX_LIGHT
                    )
                        .or(() -> memory.findNearestOwnedWebCross(xenomorph.level(), xenomorph.blockPosition(), 80.0D))
                        .orElse(null)
                    : null;
                if (safeHaven != null) {
                    blackboard.set(CommonBlackboardKeys.DESTINATION, safeHaven);
                    return BehaviorResult.runEmergency(destinationMove, 122);
                }
            }

            if (goalType == AiGoalType.RETREAT_TO_RESIN) {
                var dest = blackboard.get(CommonBlackboardKeys.GOAL_DESTINATION);
                if (dest != null) {
                    blackboard.set(CommonBlackboardKeys.DESTINATION, dest);
                    return BehaviorResult.run(destinationMove, 90);
                }
            }

            if (goalType == AiGoalType.LURE_TARGET) {
                var dest = blackboard.get(CommonBlackboardKeys.GOAL_DESTINATION);
                var pursuerCaughtUp = currentTarget != null
                    && currentTarget.isAlive()
                    && TargetingUtils.isInAttackRange(xenomorph, currentTarget, 2.0D);
                if (dest != null && !pursuerCaughtUp) {
                    blackboard.set(CommonBlackboardKeys.DESTINATION, dest);
                    return BehaviorResult.run(destinationMove, 90);
                }
            }

            if (goalType == AiGoalType.VENT_TRAVERSAL) {
                var entrance = blackboard.get(AiKeys.VENT_ENTRANCE);
                var exit = blackboard.get(AiKeys.VENT_EXIT);
                if (entrance != null && exit != null) {
                    var arrivedAtEntrance = xenomorph.blockPosition().distSqr(entrance) <= VENT_ARRIVAL_RANGE_SQR;
                    if (!arrivedAtEntrance) {
                        blackboard.set(CommonBlackboardKeys.DESTINATION, entrance);
                        return BehaviorResult.run(destinationMove, 90);
                    }
                    return BehaviorResult.run(ventTraversal, 90);
                }
            }

            if (
                goalType == AiGoalType.SEEK_DARKNESS
                    || (goalType == AiGoalType.AMBUSH_FROM_DARKNESS && currentTarget == null)
            ) {
                return BehaviorResult.run(wander, 11);
            }

            var destination = blackboard.get(CommonBlackboardKeys.DESTINATION);
            if (destination != null && currentTarget == null) {
                return BehaviorResult.run(destinationMove, 25);
            }

            if (goalType == AiGoalType.INVESTIGATE && currentTarget == null) {
                var dest = blackboard.get(CommonBlackboardKeys.GOAL_DESTINATION);
                if (dest != null) {
                    blackboard.set(CommonBlackboardKeys.DESTINATION, dest);
                    return BehaviorResult.run(destinationMove, 22);
                }
            }

            if (currentTarget != null && currentTarget.isAlive()) {
                var targetIsFireUser = Boolean.TRUE.equals(blackboard.get(CommonBlackboardKeys.TARGET_IS_FIRE_USER));
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

                if (canReachVert && hasMeleeLOS) {
                    var chosenAttack = AttackSelector.select(
                        xenomorph,
                        currentTarget,
                        cooldowns,
                        defending,
                        meleeAttacks
                    );
                    if (chosenAttack != null) {
                        return BehaviorResult.run(chosenAttack.action(), chosenAttack.priority());
                    }
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
                    (goalType == AiGoalType.BREAK_OBSTACLE
                        && !blackboard.has(AiKeys.BREAK_TO_TARGET_EXHAUSTED))
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

                if (xenomorph.isEyeInFluid(FluidTags.WATER) || xenomorph.isEyeInFluid(FluidTags.LAVA)) {
                    return BehaviorResult.run(swim, 200);
                }

                return BehaviorResult.run(moveToTargetCombat, 20);
            }

            if (xenomorph.isEyeInFluid(FluidTags.WATER) || xenomorph.isEyeInFluid(FluidTags.LAVA)) {
                return BehaviorResult.run(swim, 200);
            }

            if (CrawlController.wasRecentlyWallCrawling(xenomorph)) {
                if (blackboard.get(CommonBlackboardKeys.DESTINATION) == null) {
                    var groundPos = TargetingUtils.findNearbyGroundPos(xenomorph);
                    if (groundPos != null)
                        blackboard.set(CommonBlackboardKeys.DESTINATION, groundPos);
                }
                return BehaviorResult.run(destinationMove, 5);
            }

            if (goalType == AiGoalType.EXPAND_HIVE) {
                var breachDest = blackboard.get(AiKeys.HIVE_BREACH_DEST);
                if (breachDest != null) {
                    var closeEnough = xenomorph.blockPosition().distSqr(breachDest) <= BREACH_ARRIVAL_RANGE_SQR;
                    if (!closeEnough) {
                        blackboard.set(CommonBlackboardKeys.DESTINATION, breachDest);
                        return BehaviorResult.run(destinationMove, 9);
                    }
                }
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
                        && xenomorph.getRandom().nextFloat() < 0.75F
                        && !CrawlController.wasRecentlyWallCrawling(xenomorph))
            ) {
                return BehaviorResult.run(placeResin, 9);
            }

            if (!cooldowns.isOnCooldown(CommonBlackboardKeys.PASSIVE_DECISION)) {
                cooldowns.set(CommonBlackboardKeys.PASSIVE_DECISION, 180);
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
        var memory = blackboard.get(AiKeys.HIVE_MEMORY);
        var configCarryChance = CommonMod.getConfig().entityConfigs.xenomorphConfigs.xenoCarryToResinChance;

        var webDistSq = Double.MAX_VALUE;
        if (memory != null) {
            var nearest = memory.findNearestOwnedWebCross(xenomorph.level(), xenomorph.blockPosition(), 80.0D);
            if (nearest.isPresent())
                webDistSq = xenomorph.blockPosition().distSqr(nearest.get());
        }

        var carryChance = getCarryChance(webDistSq, configCarryChance);

        return xenomorph.getRandom().nextFloat() < carryChance ? CombatVerdict.CARRY : CombatVerdict.KILL;
    }

    private static float getCarryChance(double webDistSq, float configCarryChance) {
        float proximityBoost;
        if (webDistSq <= WEB_NEAR_SQ) {
            proximityBoost = 0.90f - (float) (webDistSq / WEB_NEAR_SQ) * 0.05f;
        } else if (webDistSq <= WEB_FAR_SQ) {
            var t = (webDistSq - WEB_NEAR_SQ) / (WEB_FAR_SQ - WEB_NEAR_SQ);
            proximityBoost = (0.90f - 0.05f) * (1f - (float) t);
        } else {
            proximityBoost = 0f;
        }

        return configCarryChance + (1f - configCarryChance) * proximityBoost;
    }
}
