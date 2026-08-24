package mod.azure.ovomorphosis.entities.runner;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

import mod.azure.ovomorphosis.ai.actions.*;
import mod.azure.ovomorphosis.ai.actions.xenomorph.*;
import mod.azure.ovomorphosis.ai.combat.AttackProfile;
import mod.azure.ovomorphosis.ai.combat.AttackSelector;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.BehaviorNode;
import mod.azure.ovomorphosis.ai.core.BehaviorResult;
import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.ai.nav.CrawlingMovementManager;
import mod.azure.ovomorphosis.ai.roles.XenoRole;
import mod.azure.ovomorphosis.ai.util.HiveMemory;
import mod.azure.ovomorphosis.ai.util.TargetingUtils;
import mod.azure.ovomorphosis.util.ModTags;

/**
 * Behavior tree for {@link RunnerEntity}.
 * <p>
 * Mirrors {@link mod.azure.ovomorphosis.entities.xenomorph.XenomorphTree} with the following capability differences:
 * <ul>
 * <li>No carry-to-web — {@code CarryToWebAction} is omitted entirely.</li>
 * <li>No resin placement — {@code PlaceResinAction} is omitted entirely.</li>
 * <li>No grab-and-execute — {@code GrabAndExecuteAction} is omitted entirely.</li>
 * </ul>
 * All other branches (dodge, flee, explosive flee, combat, lunge, movement, darkness, light destruction, wander/idle)
 * are identical in structure and priority to the Xenomorph tree.
 */
public class RunnerTree {

    /**
     * Health fraction at or below which health is treated as a life-threatening emergency capable of preempting a
     * {@link mod.azure.ovomorphosis.ai.core.InterruptCategory#LOCKED} action (e.g. mid-{@code swipeCombo}/
     * {@code tailPunish}). Mirrors {@code XenomorphTree.CRITICAL_HEALTH_FRACTION} — deliberately lower than the
     * planner's softer low-health threshold so the two don't fight: the planner's normal RETREAT_TO_RESIN goal handles
     * the common case, and this only engages when things are dire enough to justify breaking through a lock.
     */
    private static final float CRITICAL_HEALTH_FRACTION = 0.15f;

    public static BehaviorNode<RunnerEntity> create() {
        var dodge = new DodgeProjectileAction<RunnerEntity>(130);
        var fleeFire = new FleeFireAction<RunnerEntity>(125);
        var fleeExplosive = new ExplosiveFleeAction<RunnerEntity>(0.46D, 10.0D, 20.0D, 120);
        var destinationMove = new MoveToDestinationAction<RunnerEntity>(0.6D, 0.3D, 25, true);
        var moveToTargetCombat = new MoveToTargetAction<RunnerEntity>(1.2D, 0.53D, 20, true);
        var moveToTargetAmbush = new MoveToTargetAction<RunnerEntity>(0.6D, 0.22D, 18, true);
        var swim = new SwimAction<RunnerEntity>(95);

        var lunge = new LungeAction<RunnerEntity>(
            105,
            x -> x.animationDispatcher.serverWindUp(),
            x -> x.animationDispatcher.clientInAir()
        );
        var swipeCombo = new XenomorphCombatAction<RunnerEntity>(
            "swipe_combo",
            35,
            100,
            x -> x.animationDispatcher.serverAttack()
        );
        var tailPunish = new TimedAttackAction<RunnerEntity>(
            "tail_attack",
            45,
            8,
            5,
            110,
            x -> x.animationDispatcher.serverTailAttack()
        );

        var destroyLight = new DestroyLightSourceAction<RunnerEntity>(5);
        var breakToTarget = new BreakToTargetAction<RunnerEntity>();
        var wander = new WanderAction<RunnerEntity>(0.06D, 10, 6.0D, 60, 160, true);
        var idle = new IdleAction<RunnerEntity>(40, 100, 2);

        var meleeAttacks = List.of(
            new AttackProfile<>("tail_attack", tailPunish, "tail_attack", 0.0D, 1.8D, 110),
            new AttackProfile<>("swipe_combo", swipeCombo, "swipe_combo", 0.0D, 2.5D, 100)
        );

        return (runner, blackboard, cooldowns) -> {

            var currentTarget = blackboard.get(AiKeys.TARGET, LivingEntity.class);
            if (currentTarget != null && !currentTarget.isAlive()) {
                blackboard.set(AiKeys.TARGET, null);
                runner.setTarget(null);
                currentTarget = null;
            }

            var goalType = blackboard.get(AiKeys.ACTIVE_GOAL_TYPE, AiGoalType.class);
            if (goalType == null)
                goalType = AiGoalType.WANDER;

            var role = blackboard.get(AiKeys.XENO_ROLE, XenoRole.class);
            if (role == null)
                role = XenoRole.IDLE;

            if (cooldowns.ready(AiKeys.DODGE_COOLDOWN) && dodge.hasIncomingProjectile(runner)) {
                return BehaviorResult.run(dodge, 130);
            }

            {
                var fleeCooldownExpiry = blackboard.get(AiKeys.FIRE_FLEE_COOLDOWN, Integer.class);
                var currentTick = (int) runner.level().getGameTime();
                var fleeOnCooldown = fleeCooldownExpiry != null && currentTick < fleeCooldownExpiry;

                if (!fleeOnCooldown) {
                    var fireTolerance = blackboard.get(AiKeys.FIRE_TOLERANCE, Float.class);
                    var fireToleranceVal = fireTolerance != null ? fireTolerance : 0f;
                    var hasNearbyFire = FleeFireAction.shouldFleefire(runner);

                    if (runner.isOnFire()) {
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

            if (fleeExplosive.hasNearbyExplosive(runner)) {
                return BehaviorResult.run(fleeExplosive, 120);
            }

            // Critical health emergency: reuse the ordinary destination-move action (also used for the softer,
            // non-emergency RETREAT_TO_RESIN goal below and for other non-emergency goals), but tag this particular
            // selection as InterruptCategory.EMERGENCY so it can preempt a LOCKED action (e.g. mid-swipeCombo/
            // tailPunish) instead of waiting for the goal's max-commit expiry or the attack action to finish on its
            // own. Runner has no CarryToWebAction, but it still belongs to the hive (HiveMemory is populated the same
            // way Xenomorph's is), so a nearby web cross is a legitimate safe haven to flee toward.
            if (
                runner.getMaxHealth() > 0f
                    && runner.getHealth() <= runner.getMaxHealth() * CRITICAL_HEALTH_FRACTION
            ) {
                var memory = blackboard.get(AiKeys.HIVE_MEMORY, HiveMemory.class);
                var safeHaven = memory != null
                    ? memory.findNearestOwnedWebCross(runner.level(), runner.blockPosition(), 80.0D)
                        .orElse(null)
                    : null;
                if (safeHaven != null) {
                    blackboard.set(AiKeys.DESTINATION, safeHaven);
                    return BehaviorResult.runEmergency(destinationMove, 122);
                }
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
                    (int) runner.level().getGameTime()
                );
                var yGap = currentTarget.getY() - runner.getY();
                var canReachVert = Math.abs(yGap) <= 2.5D;
                var dangerTarget = currentTarget.getType().is(ModTags.DANGER_ENTITIES);
                var hasMeleeLOS = TargetingUtils.hasMeleeLineOfSight(runner, currentTarget);
                var inMeleeRange = TargetingUtils.isInAttackRange(runner, currentTarget, 2.0D);
                var combatCoolsFree = cooldowns.ready("swipe_combo") && cooldowns.ready("tail_attack");
                var defending = goalType == AiGoalType.DEFEND_HIVE;

                if (canReachVert && hasMeleeLOS) {
                    var chosenAttack = AttackSelector.select(
                        runner,
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
                        && LungeAction.canLunge(runner, currentTarget, cooldowns)
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

                if (runner.isEyeInFluid(FluidTags.WATER) || runner.isEyeInFluid(FluidTags.LAVA)) {
                    return BehaviorResult.run(swim, 200);
                }

                return BehaviorResult.run(moveToTargetCombat, 20);
            }

            if (runner.isEyeInFluid(FluidTags.WATER) || runner.isEyeInFluid(FluidTags.LAVA)) {
                return BehaviorResult.run(swim, 200);
            }

            if (CrawlingMovementManager.wasRecentlyWallCrawling(runner)) {
                if (blackboard.get(AiKeys.DESTINATION, BlockPos.class) == null) {
                    var groundPos = TargetingUtils.findNearbyGroundPos(runner);
                    if (groundPos != null)
                        blackboard.set(AiKeys.DESTINATION, groundPos);
                }
                return BehaviorResult.run(destinationMove, 5);
            }

            if (
                goalType == AiGoalType.KILL_LIGHTS
                    || (cooldowns.ready(AiKeys.LIGHT_SCAN_COOLDOWN)
                        && runner.getRandom().nextFloat() < 0.9F)
            ) {
                return BehaviorResult.run(destroyLight, 10);
            }

            if (!cooldowns.isOnCooldown(AiKeys.PASSIVE_DECISION)) {
                cooldowns.set(AiKeys.PASSIVE_DECISION, 180);
                if (runner.getRandom().nextFloat() < 0.65F) {
                    return BehaviorResult.run(wander, 10);
                }
                return BehaviorResult.run(idle, 8);
            }

            return BehaviorResult.run(idle, 8);
        };
    }
}
