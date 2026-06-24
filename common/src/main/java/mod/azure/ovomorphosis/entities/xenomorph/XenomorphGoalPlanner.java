package mod.azure.ovomorphosis.entities.xenomorph;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import mod.azure.ovomorphosis.ai.actions.xenomorph.FleeFireAction;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.Blackboard;
import mod.azure.ovomorphosis.ai.core.Cooldowns;
import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.ai.goap.GoalApplicator;
import mod.azure.ovomorphosis.ai.goap.GoalFailureCooldowns;
import mod.azure.ovomorphosis.ai.goap.GoalPlanner;
import mod.azure.ovomorphosis.ai.goap.GoalUrgency;
import mod.azure.ovomorphosis.ai.goap.PlanFailureReason;
import mod.azure.ovomorphosis.ai.goap.PlanFeedback;
import mod.azure.ovomorphosis.ai.goap.PlannedGoal;
import mod.azure.ovomorphosis.ai.util.HiveMemory;

/**
 * GOAP planner for {@link XenomorphEntity}.
 * <h3>Anti-thrash design</h3> Four mechanisms prevent goal oscillation:
 * <ol>
 * <li><b>Min-commit lock</b> — {@link GoalApplicator#shouldReplan} suppresses the planner until
 * {@link PlannedGoal#canReplan} is true (default 40 ticks). The entity's tick method must call {@code shouldReplan}
 * before invoking this planner.</li>
 * <li><b>Hysteresis bonus</b> — the currently active goal type receives a flat {@link #HYSTERESIS_BONUS} score
 * addition, raising the bar a challenger must clear to displace it. Emergency-tier situations still win because their
 * base scores are much higher than the bonus.</li>
 * <li><b>Per-goal failure cooldowns</b> — {@link GoalFailureCooldowns} tracks which goal types have recently failed and
 * applies a linearly decaying penalty to their scores, persisting well beyond the 80-tick {@link PlanFeedback}
 * freshness window.</li>
 * <li><b>Emergency override tier</b> — goals scored at or above {@link #EMERGENCY_SCORE_THRESHOLD} are tagged
 * {@link GoalUrgency#EMERGENCY}, which causes {@link GoalApplicator#shouldReplan} to bypass the min-commit lock on the
 * caller side.</li>
 * </ol>
 * <h3>Failure recording</h3> When the feedback switch fires for path/stuck/blocked failures the planner now calls
 * {@link GoalFailureCooldowns#recordFailure} in addition to the existing score penalty. This means a goal that fails
 * repeatedly is suppressed for a full {@link GoalFailureCooldowns#DEFAULT_DURATION} (200 ticks) rather than just the
 * 80-tick feedback window.
 */
public final class XenomorphGoalPlanner implements GoalPlanner<XenomorphEntity> {

    private static final float RETREAT_HEALTH_FRACTION = 0.30f;

    private static final float BOOST_BREAK = 55f;

    private static final float BOOST_INVEST = 40f;

    private static final float BOOST_LIGHTS = 50f;

    private static final float BOOST_HIVE = 45f;

    private static final float BOOST_RETREAT = 60f;

    private static final float PENALTY_FAILED = 40f;

    private static final float HYSTERESIS_BONUS = 20f;

    private static final float EMERGENCY_SCORE_THRESHOLD = 85f;

    @Override
    public PlannedGoal<XenomorphEntity> chooseGoal(
        XenomorphEntity mob,
        Blackboard blackboard,
        Cooldowns cooldowns
    ) {
        int tick = (int) mob.level().getGameTime();

        var gfc = GoalFailureCooldowns.getOrCreate(blackboard);
        gfc.evictExpired(tick);

        var feedback = readFeedback(blackboard, tick);

        var target = blackboard.get(AiKeys.TARGET, LivingEntity.class);
        var hasTarget = target != null && target.isAlive();

        var healthFraction = mob.getHealth() / mob.getMaxHealth();
        var lowHealth = healthFraction <= RETREAT_HEALTH_FRACTION;

        var memory = blackboard.get(AiKeys.HIVE_MEMORY, HiveMemory.class);
        var nearWeb = memory != null
            && memory.findNearestWebCross(mob.level(), mob.blockPosition(), 20.0D).isPresent();
        var hasWebInRange = memory != null
            && memory.findNearestWebCross(mob.level(), mob.blockPosition(), 80.0D).isPresent();

        var ambientLight = mob.level().getMaxLocalRawBrightness(mob.blockPosition());
        var tooBright = ambientLight > 4;

        var huntScore = 0f;
        var ambushScore = 0f;
        var breakScore = 0f;
        var lightsScore = tooBright ? 35f : 0f;
        var hiveScore = 10f;
        var defendScore = 0f;
        var retreatScore = 0f;
        var investigateScore = 0f;
        var wanderScore = 5f;

        if (hasTarget) {
            var distSq = mob.distanceToSqr(target);
            var targetFacingMob = isTargetFacingMob(target, mob);

            huntScore = 70f + (targetFacingMob ? 20f : 0f);

            if (!targetFacingMob && distSq > 8.0 * 8.0) {
                ambushScore = 60f;
                huntScore -= 15f;
            }

            if (nearWeb) {
                defendScore = 85f;
            }

            if (distSq <= 12.0 * 12.0) {
                breakScore = 20f;
            }
        }

        if (lowHealth && hasWebInRange) {
            retreatScore = 80f;
        } else if (lowHealth) {
            retreatScore = 40f;
        }

        var lastSeenPos = blackboard.get(AiKeys.LAST_SEEN_POS, BlockPos.class);
        if (lastSeenPos != null && !hasTarget) {
            investigateScore = 35f;
        }

        if (!hasTarget && !tooBright) {
            hiveScore = 20f;
        }

        if (tooBright) {
            lightsScore = 20f + ambientLight * 3f;
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
                    if (failedGoal == AiGoalType.EXPAND_HIVE) {
                        gfc.recordFailure(AiGoalType.EXPAND_HIVE, tick, 100);
                    }
                }
                case FAILED_TARGET_LOST -> {
                    investigateScore += BOOST_INVEST;
                    huntScore -= PENALTY_FAILED * 0.5f;
                    gfc.recordFailure(AiGoalType.HUNT_TARGET, tick, 80);
                }
                case FAILED_TOO_BRIGHT -> {
                    lightsScore += BOOST_LIGHTS;
                    huntScore -= PENALTY_FAILED * 0.5f;
                    gfc.recordFailure(AiGoalType.HUNT_TARGET, tick, 60);
                }
                case FAILED_NO_WEB -> {
                    hiveScore += BOOST_HIVE;
                    gfc.recordFailure(AiGoalType.EXPAND_HIVE, tick, 120);
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
                    if (failedGoal == AiGoalType.EXPAND_HIVE) {
                        hiveScore -= PENALTY_FAILED * 0.5f;
                        gfc.recordFailure(AiGoalType.EXPAND_HIVE, tick, 40);
                    }
                }
                default -> {}
            }
        }

        huntScore -= gfc.getPenalty(AiGoalType.HUNT_TARGET, tick);
        ambushScore -= gfc.getPenalty(AiGoalType.AMBUSH_TARGET, tick);
        breakScore -= gfc.getPenalty(AiGoalType.BREAK_OBSTACLE, tick);
        hiveScore -= gfc.getPenalty(AiGoalType.EXPAND_HIVE, tick);
        lightsScore -= gfc.getPenalty(AiGoalType.KILL_LIGHTS, tick);
        retreatScore -= gfc.getPenalty(AiGoalType.RETREAT_TO_RESIN, tick);
        investigateScore -= gfc.getPenalty(AiGoalType.INVESTIGATE, tick);

        FleeFireAction.tickFireAttackerMemory(blackboard, tick);

        var fireAttacker = blackboard.get(AiKeys.LAST_FIRE_ATTACKER, LivingEntity.class);
        var fireUserIsTarget = Boolean.TRUE.equals(blackboard.get(AiKeys.TARGET_IS_FIRE_USER, Boolean.class));
        var fireDangerActive = FleeFireAction.isFireDangerActive(blackboard, tick);

        if (fireAttacker != null && target != null) {
            var isSame = fireAttacker == target;
            blackboard.set(AiKeys.TARGET_IS_FIRE_USER, isSame);
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
        hiveScore = Math.max(0f, hiveScore);
        defendScore = Math.max(0f, defendScore);
        retreatScore = Math.max(0f, retreatScore);
        investigateScore = Math.max(0f, investigateScore);

        var activeGoalType = blackboard.get(AiKeys.ACTIVE_GOAL_TYPE, AiGoalType.class);
        if (activeGoalType != null) {
            switch (activeGoalType) {
                case HUNT_TARGET -> huntScore += HYSTERESIS_BONUS;
                case AMBUSH_TARGET -> ambushScore += HYSTERESIS_BONUS;
                case BREAK_OBSTACLE -> breakScore += HYSTERESIS_BONUS;
                case KILL_LIGHTS -> lightsScore += HYSTERESIS_BONUS;
                case EXPAND_HIVE -> hiveScore += HYSTERESIS_BONUS;
                case DEFEND_HIVE -> defendScore += HYSTERESIS_BONUS;
                case RETREAT_TO_RESIN -> retreatScore += HYSTERESIS_BONUS;
                case INVESTIGATE -> investigateScore += HYSTERESIS_BONUS;
                case WANDER -> wanderScore += HYSTERESIS_BONUS;
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
                new Candidate(AiGoalType.EXPAND_HIVE, hiveScore),
                new Candidate(AiGoalType.DEFEND_HIVE, defendScore),
                new Candidate(AiGoalType.RETREAT_TO_RESIN, retreatScore),
                new Candidate(AiGoalType.INVESTIGATE, investigateScore),
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
            case EXPAND_HIVE -> {
                urgency = GoalUrgency.LOW;
                interruptible = true;
                reason = "Expanding hive";
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
                    var nearest = memory.findNearestWebCross(mob.level(), mob.blockPosition(), 80.0D);
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
            default -> {
                urgency = GoalUrgency.LOW;
                interruptible = true;
                reason = "Idle wandering";
                chosenTarget = null;
            }
        }

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

    private static PlanFeedback readFeedback(Blackboard blackboard, int tick) {
        var full = blackboard.get(AiKeys.LAST_PLAN_FEEDBACK, PlanFeedback.class);
        if (full != null)
            return full;
        var raw = blackboard.get(AiKeys.LAST_FAILURE_REASON, PlanFailureReason.class);
        if (raw != null && raw != PlanFailureReason.NONE) {
            var activeGoal = blackboard.get(AiKeys.ACTIVE_GOAL_TYPE, AiGoalType.class);
            return PlanFeedback.of(raw, tick, null, activeGoal != null ? activeGoal : AiGoalType.NONE);
        }
        return null;
    }

    private static boolean isTargetFacingMob(LivingEntity target, XenomorphEntity mob) {
        var toMob = mob.position().subtract(target.position()).normalize();
        return target.getLookAngle().dot(toMob) > 0.5D;
    }

    private static String buildReason(String base, PlanFeedback feedback) {
        if (feedback == null || feedback.isNone())
            return base;
        return base + " [after " + feedback.reason().name() + "]";
    }
}
