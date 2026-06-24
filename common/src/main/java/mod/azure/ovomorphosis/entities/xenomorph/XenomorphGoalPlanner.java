package mod.azure.ovomorphosis.entities.xenomorph;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.Blackboard;
import mod.azure.ovomorphosis.ai.core.Cooldowns;
import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.ai.goap.GoalPlanner;
import mod.azure.ovomorphosis.ai.goap.GoalUrgency;
import mod.azure.ovomorphosis.ai.goap.PlanFailureReason;
import mod.azure.ovomorphosis.ai.goap.PlanFeedback;
import mod.azure.ovomorphosis.ai.goap.PlannedGoal;
import mod.azure.ovomorphosis.ai.util.HiveMemory;

/**
 * GOAP planner for {@link XenomorphEntity}.
 * <h3>Goals scored</h3>
 * <ul>
 * <li>{@link AiGoalType#HUNT_TARGET} — active pursuit of a confirmed target.</li>
 * <li>{@link AiGoalType#AMBUSH_TARGET} — stalk a target without being detected (target facing away, far).</li>
 * <li>{@link AiGoalType#BREAK_OBSTACLE} — break a block obstructing the path to target.</li>
 * <li>{@link AiGoalType#KILL_LIGHTS} — destroy light sources to darken the area.</li>
 * <li>{@link AiGoalType#EXPAND_HIVE} — place resin / carry victim to web.</li>
 * <li>{@link AiGoalType#DEFEND_HIVE} — target is within the hive area, defend it aggressively.</li>
 * <li>{@link AiGoalType#RETREAT_TO_RESIN}— low health, retreat toward resin web.</li>
 * <li>{@link AiGoalType#INVESTIGATE} — check last known position after target loss.</li>
 * <li>{@link AiGoalType#WANDER} — default idle.</li>
 * </ul>
 * <h3>Failure feedback integration</h3> The planner reads {@link AiKeys#LAST_PLAN_FEEDBACK} before scoring and applies
 * modifiers:
 * <ul>
 * <li>{@link PlanFailureReason#FAILED_NO_PATH} / {@link PlanFailureReason#FAILED_STUCK} /
 * {@link PlanFailureReason#FAILED_BLOCKED} → raise {@code BREAK_OBSTACLE} score; penalise the failing goal type.</li>
 * <li>{@link PlanFailureReason#FAILED_TARGET_LOST} → raise {@code INVESTIGATE}.</li>
 * <li>{@link PlanFailureReason#FAILED_TOO_BRIGHT} → raise {@code KILL_LIGHTS}.</li>
 * <li>{@link PlanFailureReason#FAILED_NO_WEB} → raise {@code EXPAND_HIVE}.</li>
 * <li>{@link PlanFailureReason#FAILED_DANGER} → raise {@code RETREAT_TO_RESIN}.</li>
 * </ul>
 */
public final class XenomorphGoalPlanner implements GoalPlanner<XenomorphEntity> {

    private static final float RETREAT_HEALTH_FRACTION = 0.30f;

    private static final float BOOST_BREAK = 55f;

    private static final float BOOST_INVEST = 40f;

    private static final float BOOST_LIGHTS = 50f;

    private static final float BOOST_HIVE = 45f;

    private static final float BOOST_RETREAT = 60f;

    private static final float PENALTY_FAILED = 40f;

    @Override
    public PlannedGoal<XenomorphEntity> chooseGoal(
        XenomorphEntity mob,
        Blackboard blackboard,
        Cooldowns cooldowns
    ) {
        int tick = (int) mob.level().getGameTime();
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
                    if (failedGoal == AiGoalType.HUNT_TARGET)
                        huntScore -= PENALTY_FAILED;
                    if (failedGoal == AiGoalType.AMBUSH_TARGET)
                        ambushScore -= PENALTY_FAILED;
                }
                case FAILED_TARGET_LOST -> {
                    investigateScore += BOOST_INVEST;
                    huntScore -= PENALTY_FAILED * 0.5f;
                }
                case FAILED_TOO_BRIGHT -> {
                    lightsScore += BOOST_LIGHTS;
                    huntScore -= PENALTY_FAILED * 0.5f;
                }
                case FAILED_NO_WEB -> {
                    hiveScore += BOOST_HIVE;
                }
                case FAILED_DANGER -> {
                    retreatScore += BOOST_RETREAT;
                    huntScore -= PENALTY_FAILED;
                    ambushScore -= PENALTY_FAILED;
                }
                case FAILED_COOLDOWN -> {
                    if (failedGoal == AiGoalType.HUNT_TARGET)
                        huntScore -= PENALTY_FAILED * 0.5f;
                    if (failedGoal == AiGoalType.EXPAND_HIVE)
                        hiveScore -= PENALTY_FAILED * 0.5f;
                }
                default -> {}
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
        net.minecraft.core.BlockPos chosenDest = null;

        switch (chosen) {
            case HUNT_TARGET -> {
                urgency = GoalUrgency.HIGH;
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
