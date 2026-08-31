package mod.azure.ovomorphosis.entities.runner;

import com.azure.azurecortex.api.blackboard.Blackboard;
import com.azure.azurecortex.api.blackboard.CommonBlackboardKeys;
import com.azure.azurecortex.api.goal.GoalUrgency;
import com.azure.azurecortex.goap.*;
import com.azure.azurecortex.runtime.CooldownTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.LightLayer;

import mod.azure.ovomorphosis.ai.actions.FleeFireAction;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.ai.roles.XenoRole;
import mod.azure.ovomorphosis.ai.util.TargetClassifier;

/**
 * GOAP planner for {@link RunnerEntity}.
 * <p>
 * Mirrors {@link mod.azure.ovomorphosis.entities.xenomorph.XenomorphGoalPlanner} with the following capability
 * differences:
 * <ul>
 * <li>No carry-to-web — {@link AiGoalType#EXPAND_HIVE} scoring and the {@code HIVE_SPREADER} role are omitted.</li>
 * <li>No resin placement — no {@code FAILED_NO_WEB} feedback branch.</li>
 * <li>No grab-and-execute — does not affect goal scoring directly, but the tree omits the action.</li>
 * </ul>
 * <h3>Anti-thrash design</h3> Identical to the Xenomorph planner:
 * <ol>
 * <li>Min-commit lock via {@link GoalExecutor#shouldReplan}.</li>
 * <li>Hysteresis bonus on the active goal.</li>
 * <li>Per-goal failure cooldowns via {@link GoalFailureCooldowns}.</li>
 * <li>Emergency override tier at {@link #EMERGENCY_SCORE_THRESHOLD}.</li>
 * </ol>
 */
public final class RunnerGoalPlanner implements GoalPlanner<RunnerEntity, AiGoalType> {

    private static final float RETREAT_HEALTH_FRACTION = 0.30f;

    private static final float BOOST_BREAK = 55f;

    private static final float BOOST_INVEST = 40f;

    private static final float BOOST_LIGHTS = 50f;

    private static final float BOOST_RETREAT = 60f;

    private static final float PENALTY_FAILED = 40f;

    private static final float HYSTERESIS_BONUS = 20f;

    private static final float EMERGENCY_SCORE_THRESHOLD = 85f;

    @Override
    public PlannedGoal<RunnerEntity, AiGoalType> chooseGoal(
        RunnerEntity mob,
        Blackboard blackboard,
        CooldownTracker cooldowns
    ) {
        int tick = (int) mob.level().getGameTime();

        var gfc = GoalFailureCooldowns.getOrCreate(blackboard);
        gfc.evictExpired(tick);

        var feedback = readFeedback(blackboard, tick);

        var target = blackboard.get(CommonBlackboardKeys.TARGET);
        var hasTarget = target != null && target.isAlive();

        var healthFraction = mob.getHealth() / mob.getMaxHealth();
        var lowHealth = healthFraction <= RETREAT_HEALTH_FRACTION;

        var memory = blackboard.get(AiKeys.HIVE_MEMORY);
        var nearWeb = memory != null
            && memory.findNearestOwnedWebCross(mob.level(), mob.blockPosition(), 20.0D).isPresent();
        var hasWebInRange = memory != null
            && memory.findNearestOwnedWebCross(mob.level(), mob.blockPosition(), 80.0D).isPresent();

        var ambientLight = mob.level().getMaxLocalRawBrightness(mob.blockPosition());
        var tooBright = ambientLight > 4;

        var nearbyLightBlock = mob.level().getBrightness(LightLayer.BLOCK, mob.blockPosition()) > 4;

        TargetClassifier.classify(mob, blackboard);
        var targetIsRanged = Boolean.TRUE.equals(blackboard.get(CommonBlackboardKeys.TARGET_IS_RANGED));
        var targetIsNearHive = Boolean.TRUE.equals(blackboard.get(AiKeys.TARGET_IS_NEAR_HIVE));

        var huntScore = 0f;
        var ambushScore = 0f;
        var breakScore = 0f;
        var lightsScore = tooBright && nearbyLightBlock ? 35f : 0f;
        var defendScore = 0f;
        var retreatScore = 0f;
        var investigateScore = 0f;
        var seekDarknessScore = 0f;
        var ambushFromDarknessScore = 0f;
        var wanderScore = 5f;

        if (hasTarget) {
            var distSq = mob.distanceToSqr(target);
            var targetFacingMob = isTargetFacingMob(target, mob);

            huntScore = 70f + (targetFacingMob ? 20f : 0f);

            if (targetIsRanged) {
                huntScore -= 20f;
                ambushScore += 25f;
            }
            if (targetIsNearHive || nearWeb) {
                defendScore = 85f;
            }
            if (!targetFacingMob && distSq > 8.0 * 8.0) {
                ambushScore += 25f;
                huntScore -= 15f;
            }
            if (distSq <= 12.0 * 12.0) {
                breakScore = 20f;
            }
        }

        if (tooBright && !hasTarget) {
            seekDarknessScore = 30f + ambientLight * 2f;
        }
        if (lowHealth && ambientLight > 2) {
            seekDarknessScore += 25f;
        }
        if (hasTarget && tooBright) {
            ambushFromDarknessScore = 45f + ambientLight * 2f;
            huntScore -= 20f;
        }
        if (tooBright && nearbyLightBlock) {
            lightsScore = 20f + ambientLight * 3f;
        }

        if (lowHealth && hasWebInRange) {
            retreatScore = 80f;
        } else if (lowHealth) {
            retreatScore = 40f;
        }

        var lastSeenPos = blackboard.get(CommonBlackboardKeys.LAST_SEEN_POS);
        if (lastSeenPos != null && !hasTarget) {
            investigateScore = 35f;
        }

        if (feedback != null && feedback.isFresh(tick)) {
            var reason = feedback.reason();
            var failedGoal = feedback.failedGoalType();

            switch (reason) {
                case FAILED_NO_PATH, FAILED_STUCK, FAILED_BLOCKED -> {
                    breakScore += BOOST_BREAK;
                    investigateScore += BOOST_INVEST * 0.5f;
                    if (failedGoal == AiGoalType.HUNT_TARGET) {
                        huntScore -= PENALTY_FAILED;
                        gfc.recordFailure(AiGoalType.HUNT_TARGET, tick, 160);
                    }
                    if (failedGoal == AiGoalType.AMBUSH_TARGET) {
                        ambushScore -= PENALTY_FAILED;
                        gfc.recordFailure(AiGoalType.AMBUSH_TARGET, tick, 120);
                    }
                }
                case FAILED_TARGET_LOST -> {
                    investigateScore += BOOST_INVEST;
                    huntScore -= PENALTY_FAILED * 0.5f;
                    gfc.recordFailure(AiGoalType.HUNT_TARGET, tick, 80);
                }
                case FAILED_UNSUITABLE_CONDITIONS -> {
                    lightsScore += BOOST_LIGHTS;
                    huntScore -= PENALTY_FAILED * 0.5f;
                    gfc.recordFailure(AiGoalType.HUNT_TARGET, tick, 60);
                }
                case FAILED_DANGER -> {
                    retreatScore += BOOST_RETREAT;
                    huntScore -= PENALTY_FAILED;
                    ambushScore -= PENALTY_FAILED;
                    gfc.recordFailure(AiGoalType.HUNT_TARGET, tick, 200);
                    gfc.recordFailure(AiGoalType.AMBUSH_TARGET, tick, 200);
                }
                case FAILED_COOLDOWN -> {
                    if (failedGoal == AiGoalType.HUNT_TARGET) {
                        huntScore -= PENALTY_FAILED * 0.5f;
                        gfc.recordFailure(AiGoalType.HUNT_TARGET, tick, 40);
                    }
                }
                case FAILED_PRECONDITION -> {
                    if (failedGoal == AiGoalType.KILL_LIGHTS) {
                        lightsScore -= PENALTY_FAILED;
                        gfc.recordFailure(AiGoalType.KILL_LIGHTS, tick, 200);
                    }
                    if (failedGoal == AiGoalType.BREAK_OBSTACLE) {
                        breakScore -= PENALTY_FAILED;
                        gfc.recordFailure(AiGoalType.BREAK_OBSTACLE, tick, 200);
                    }
                }
                case FAILED_OBSTACLE_UNBREAKABLE -> {
                    investigateScore += BOOST_INVEST;
                    ambushScore += 20f;
                    seekDarknessScore += 25f;
                    breakScore -= PENALTY_FAILED;
                    gfc.recordFailure(AiGoalType.BREAK_OBSTACLE, tick, 200);
                    if (failedGoal == AiGoalType.HUNT_TARGET) {
                        huntScore -= PENALTY_FAILED;
                        gfc.recordFailure(AiGoalType.HUNT_TARGET, tick, 120);
                    }
                }
                default -> {}
            }
        }

        huntScore -= gfc.getPenalty(AiGoalType.HUNT_TARGET, tick);
        ambushScore -= gfc.getPenalty(AiGoalType.AMBUSH_TARGET, tick);
        breakScore -= gfc.getPenalty(AiGoalType.BREAK_OBSTACLE, tick);
        lightsScore -= gfc.getPenalty(AiGoalType.KILL_LIGHTS, tick);
        retreatScore -= gfc.getPenalty(AiGoalType.RETREAT_TO_RESIN, tick);
        investigateScore -= gfc.getPenalty(AiGoalType.INVESTIGATE, tick);
        seekDarknessScore -= gfc.getPenalty(AiGoalType.SEEK_DARKNESS, tick);
        ambushFromDarknessScore -= gfc.getPenalty(AiGoalType.AMBUSH_FROM_DARKNESS, tick);

        FleeFireAction.tickFireAttackerMemory(blackboard, tick);

        var fireAttacker = blackboard.get(CommonBlackboardKeys.LAST_FIRE_ATTACKER);
        var fireUserIsTarget = Boolean.TRUE.equals(blackboard.get(CommonBlackboardKeys.TARGET_IS_FIRE_USER));
        var fireDangerActive = FleeFireAction.isFireDangerActive(blackboard, tick);

        if (fireAttacker != null && target != null) {
            var isSame = fireAttacker == target;
            blackboard.set(CommonBlackboardKeys.TARGET_IS_FIRE_USER, isSame);
            fireUserIsTarget = isSame;
        }

        if (fireDangerActive && hasTarget) {
            if (fireUserIsTarget) {
                huntScore -= 25f;
                ambushScore += 35f;
                lightsScore += 15f;
                if (nearWeb) {
                    defendScore = Math.max(0f, defendScore - 20f);
                    retreatScore += 20f;
                }
            } else {
                retreatScore += 15f;
                ambushScore += 10f;
            }
        }

        huntScore = Math.max(0f, huntScore);
        ambushScore = Math.max(0f, ambushScore);
        breakScore = Math.max(0f, breakScore);
        lightsScore = Math.max(0f, lightsScore);
        defendScore = Math.max(0f, defendScore);
        retreatScore = Math.max(0f, retreatScore);
        investigateScore = Math.max(0f, investigateScore);
        seekDarknessScore = Math.max(0f, seekDarknessScore);
        ambushFromDarknessScore = Math.max(0f, ambushFromDarknessScore);

        var activeGoalType = blackboard.get(CommonBlackboardKeys.ACTIVE_GOAL_TYPE);
        if (activeGoalType != null) {
            switch (activeGoalType) {
                case AiGoalType.HUNT_TARGET -> huntScore += HYSTERESIS_BONUS;
                case AiGoalType.AMBUSH_TARGET -> ambushScore += HYSTERESIS_BONUS;
                case AiGoalType.BREAK_OBSTACLE -> breakScore += HYSTERESIS_BONUS;
                case AiGoalType.KILL_LIGHTS -> lightsScore += HYSTERESIS_BONUS;
                case AiGoalType.DEFEND_HIVE -> defendScore += HYSTERESIS_BONUS;
                case AiGoalType.RETREAT_TO_RESIN -> retreatScore += HYSTERESIS_BONUS;
                case AiGoalType.INVESTIGATE -> investigateScore += HYSTERESIS_BONUS;
                case AiGoalType.WANDER -> wanderScore += HYSTERESIS_BONUS;
                case AiGoalType.SEEK_DARKNESS -> seekDarknessScore += HYSTERESIS_BONUS;
                case AiGoalType.AMBUSH_FROM_DARKNESS -> ambushFromDarknessScore += HYSTERESIS_BONUS;
                default -> {}
            }
        }

        record Candidate(
            AiGoalType type,
            float score
        ) {}

        var best = new Candidate(AiGoalType.WANDER, wanderScore);
        for (
            var c : new Candidate[] {
                new Candidate(AiGoalType.HUNT_TARGET, huntScore),
                new Candidate(AiGoalType.AMBUSH_TARGET, ambushScore),
                new Candidate(AiGoalType.BREAK_OBSTACLE, breakScore),
                new Candidate(AiGoalType.KILL_LIGHTS, lightsScore),
                new Candidate(AiGoalType.DEFEND_HIVE, defendScore),
                new Candidate(AiGoalType.RETREAT_TO_RESIN, retreatScore),
                new Candidate(AiGoalType.INVESTIGATE, investigateScore),
                new Candidate(AiGoalType.SEEK_DARKNESS, seekDarknessScore),
                new Candidate(AiGoalType.AMBUSH_FROM_DARKNESS, ambushFromDarknessScore),
            }
        ) {
            if (c.score() > best.score())
                best = c;
        }

        var chosen = best.type();
        var chosenScore = best.score();

        GoalUrgency urgency;
        boolean interruptible;
        String reason;
        LivingEntity chosenTarget = hasTarget ? target : null;
        BlockPos chosenDest = null;

        switch (chosen) {
            case HUNT_TARGET -> {
                urgency = chosenScore >= EMERGENCY_SCORE_THRESHOLD ? GoalUrgency.EMERGENCY : GoalUrgency.HIGH;
                interruptible = false;
                reason = buildReason("Hunting target", feedback);
            }
            case AMBUSH_TARGET -> {
                urgency = GoalUrgency.NORMAL;
                interruptible = true;
                reason = "Ambushing — target unaware";
            }
            case BREAK_OBSTACLE -> {
                urgency = GoalUrgency.HIGH;
                interruptible = true;
                reason = buildReason("Breaking obstacle to reach target", feedback);
            }
            case KILL_LIGHTS -> {
                urgency = GoalUrgency.NORMAL;
                interruptible = true;
                reason = "Destroying light sources";
                chosenTarget = null;
            }
            case DEFEND_HIVE -> {
                urgency = GoalUrgency.EMERGENCY;
                interruptible = false;
                reason = "Target in hive — defending";
            }
            case RETREAT_TO_RESIN -> {
                urgency = lowHealth ? GoalUrgency.EMERGENCY : GoalUrgency.HIGH;
                interruptible = false;
                reason = "Low health, retreating to resin";
                chosenTarget = null;
                if (memory != null) {
                    var nearest = memory.findNearestOwnedWebCross(mob.level(), mob.blockPosition(), 80.0D);
                    chosenDest = nearest.orElse(null);
                }
            }
            case INVESTIGATE -> {
                urgency = GoalUrgency.NORMAL;
                interruptible = true;
                chosenDest = lastSeenPos != null
                    ? lastSeenPos
                    : (feedback != null ? feedback.failurePos() : null);
                reason = buildReason("Investigating last known position", feedback);
                chosenTarget = null;
            }
            case SEEK_DARKNESS -> {
                urgency = GoalUrgency.NORMAL;
                interruptible = true;
                reason = "Exposed or hurt — seeking darkness";
                chosenTarget = null;
            }
            case AMBUSH_FROM_DARKNESS -> {
                urgency = GoalUrgency.NORMAL;
                interruptible = true;
                reason = "Repositioning to dark ambush position before engaging";
            }
            default -> {
                urgency = GoalUrgency.LOW;
                interruptible = true;
                reason = "Idle wandering";
                chosenTarget = null;
            }
        }

        var role = deriveRole(chosen, hasTarget);
        blackboard.set(AiKeys.XENO_ROLE, role);

        return PlannedGoal.of(
            chosen,
            chosenScore,
            tick,
            40,
            200,
            chosenTarget,
            chosenDest,
            urgency,
            interruptible,
            reason
        );
    }

    private static XenoRole deriveRole(AiGoalType chosen, boolean hasTarget) {
        return switch (chosen) {
            case HUNT_TARGET -> XenoRole.HUNTER;
            case AMBUSH_TARGET, SEEK_DARKNESS,
                AMBUSH_FROM_DARKNESS -> XenoRole.STALKER;
            case DEFEND_HIVE -> XenoRole.DEFENDER;
            case RETREAT_TO_RESIN -> XenoRole.RETREATER;
            case BREAK_OBSTACLE, INVESTIGATE -> hasTarget ? XenoRole.HUNTER : XenoRole.STALKER;
            default -> XenoRole.IDLE;
        };
    }

    @SuppressWarnings("unchecked")
    private static PlanFeedback<AiGoalType> readFeedback(Blackboard blackboard, int tick) {
        var full = (PlanFeedback<AiGoalType>) blackboard.get(CommonBlackboardKeys.LAST_PLAN_FEEDBACK);
        if (full != null)
            return full;

        var raw = (PlanFailureReason) blackboard.get(CommonBlackboardKeys.LAST_FAILURE_REASON);
        if (raw != null && raw != PlanFailureReason.NONE) {
            var activeGoal = (AiGoalType) blackboard.get(CommonBlackboardKeys.ACTIVE_GOAL_TYPE);
            return PlanFeedback.of(raw, tick, null, activeGoal != null ? activeGoal : AiGoalType.NONE);
        }
        return null;
    }

    private static boolean isTargetFacingMob(LivingEntity target, RunnerEntity mob) {
        var toMob = mob.position().subtract(target.position()).normalize();
        return target.getLookAngle().dot(toMob) > 0.5D;
    }

    private static String buildReason(String base, PlanFeedback<AiGoalType> feedback) {
        if (feedback == null || feedback.isNone())
            return base;
        return base + " [after " + feedback.reason().name() + "]";
    }
}
