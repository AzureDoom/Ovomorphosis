package mod.azure.ovomorphosis.entities.facehugger;

import com.azure.azurecortex.api.blackboard.Blackboard;
import com.azure.azurecortex.api.blackboard.CommonBlackboardKeys;
import com.azure.azurecortex.api.goal.GoalUrgency;
import com.azure.azurecortex.goap.*;
import com.azure.azurecortex.runtime.CooldownTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.ai.util.TargetingUtils;

/**
 * GOAP planner for {@link FacehuggerEntity}.
 * <h3>Anti-thrash design</h3>
 * <ul>
 * <li><b>Hysteresis</b> — active goal receives {@link #HYSTERESIS_BONUS} so minor score fluctuations don't cause goal
 * switching every cycle.</li>
 * <li><b>GoalFailureCooldowns</b> — per-goal decaying penalty persisting beyond the 80-tick {@link PlanFeedback}
 * window. A failed leap attempt suppresses INFECT_HOST for 160 ticks so the facehugger stalks before trying again
 * rather than immediately re-leaping.</li>
 * <li><b>Emergency threshold</b> — RETREAT_AND_HIDE when a threat is present scores ≥ 90 and is tagged
 * {@link GoalUrgency#EMERGENCY}, bypassing min-commit.</li>
 * </ul>
 * <h3>isSuppressed usage</h3> {@link GoalFailureCooldowns#isSuppressed} is read in
 * {@link FacehuggerEntity#tickGoalPlanner} to skip the reactive replan fast-path when INFECT_HOST is currently
 * suppressed — there is no point triggering an immediate replan if the planner will just score that goal near zero
 * anyway.
 */
public final class FacehuggerGoalPlanner implements GoalPlanner<FacehuggerEntity, AiGoalType> {

    private static final int MIN_COMMIT_TICKS = 40;

    private static final int MAX_COMMIT_TICKS = 200;

    private static final double INFECT_RANGE_SQ = 10.0 * 10.0;

    private static final float RETREAT_HEALTH_FRACTION = 0.35f;

    private static final float BOOST_INVESTIGATE = 45f;

    private static final float BOOST_RETREAT = 55f;

    private static final float PENALTY_FAILED_GOAL = 40f;

    private static final float HYSTERESIS_BONUS = 15f;

    @Override
    public PlannedGoal<FacehuggerEntity, AiGoalType> chooseGoal(
        FacehuggerEntity mob,
        Blackboard blackboard,
        CooldownTracker cooldowns
    ) {
        int tick = (int) mob.level().getGameTime();

        if (mob.isAttachedToHost()) {
            return PlannedGoal.of(
                AiGoalType.INFECT_HOST,
                100f,
                tick,
                MIN_COMMIT_TICKS,
                MAX_COMMIT_TICKS,
                null,
                null,
                GoalUrgency.EMERGENCY,
                false,
                "Already attached to host"
            );
        }

        var gfc = GoalFailureCooldowns.getOrCreate(blackboard);
        gfc.evictExpired(tick);

        var feedback = readFeedback(blackboard, tick);

        var target = blackboard.get(CommonBlackboardKeys.TARGET);
        if (target != null && (!target.isAlive() || !TargetingUtils.faceHuggerTest(mob, target))) {
            target = null;
            blackboard.set(CommonBlackboardKeys.TARGET, null);
            mob.setTarget(null);
        }

        var healthFraction = mob.getHealth() / mob.getMaxHealth();
        var lowHealth = healthFraction <= RETREAT_HEALTH_FRACTION;

        if (!lowHealth) {
            var staleFailCount = blackboard.get(CommonBlackboardKeys.FAILED_GOAL_COUNT);
            if (staleFailCount != null && staleFailCount != 0) {
                blackboard.set(CommonBlackboardKeys.FAILED_GOAL_COUNT, 0);
            }
        }

        var infectScore = 0f;
        var stalkScore = 0f;
        var retreatScore = 0f;
        var investigateScore = 0f;
        var wanderScore = 1f;

        LivingEntity infectTarget = null;
        LivingEntity stalkTarget = null;
        BlockPos stalkDest = null;
        BlockPos retreatDest = null;
        BlockPos investigateDest = null;

        if (target != null) {
            double distSq = mob.distanceToSqr(target);
            boolean hasLos = mob.hasLineOfSight(target);

            if (hasLos && distSq <= INFECT_RANGE_SQ) {
                infectScore = 80f + (float) (INFECT_RANGE_SQ - distSq) / (float) INFECT_RANGE_SQ * 15f;
                infectTarget = target;
            } else {
                stalkScore = 60f;
                stalkTarget = target;
                stalkDest = target.blockPosition();
            }
        }

        if (lowHealth) {
            retreatScore = target != null ? 90f : 50f;
            retreatDest = findHidePosition(mob);
        }

        var lastSeenPos = blackboard.get(CommonBlackboardKeys.LAST_SEEN_POS);
        if (lastSeenPos != null && target == null) {
            investigateScore = 30f;
            investigateDest = lastSeenPos;
        }

        if (feedback != null && feedback.isFresh(tick)) {
            var reason = feedback.reason();
            var failedGoal = feedback.failedGoalType();

            switch (reason) {
                case FAILED_TARGET_LOST -> {
                    investigateScore += BOOST_INVESTIGATE;
                    if (feedback.failurePos() != null)
                        investigateDest = feedback.failurePos();
                }
                case FAILED_NO_PATH, FAILED_STUCK, FAILED_BLOCKED -> {
                    if (failedGoal == AiGoalType.INFECT_HOST) {
                        infectScore -= PENALTY_FAILED_GOAL;
                        gfc.recordFailure(AiGoalType.INFECT_HOST, tick, 160);
                    }
                    if (failedGoal == AiGoalType.STALK_HOST) {
                        stalkScore -= PENALTY_FAILED_GOAL;
                        gfc.recordFailure(AiGoalType.STALK_HOST, tick, 100);
                    }
                    if (failedGoal == AiGoalType.RETREAT_AND_HIDE) {
                        retreatScore -= PENALTY_FAILED_GOAL;
                        var failCount = blackboard.get(CommonBlackboardKeys.FAILED_GOAL_COUNT);
                        var count = failCount != null ? Math.max(1, failCount) : 1;
                        var suppressDuration = Math.min(80 * count, 600);
                        gfc.recordFailure(AiGoalType.RETREAT_AND_HIDE, tick, suppressDuration);
                    }
                    investigateScore += BOOST_INVESTIGATE * 0.5f;
                }
                case FAILED_DANGER -> {
                    retreatScore += BOOST_RETREAT;
                    retreatDest = retreatDest != null ? retreatDest : findHidePosition(mob);
                    infectScore -= PENALTY_FAILED_GOAL;
                    stalkScore -= PENALTY_FAILED_GOAL;
                    gfc.recordFailure(AiGoalType.INFECT_HOST, tick, 200);
                    gfc.recordFailure(AiGoalType.STALK_HOST, tick, 200);
                }
                case FAILED_COOLDOWN -> {
                    if (failedGoal == AiGoalType.INFECT_HOST) {
                        infectScore -= PENALTY_FAILED_GOAL;
                        gfc.recordFailure(AiGoalType.INFECT_HOST, tick, 40);
                    }
                    if (failedGoal == AiGoalType.STALK_HOST) {
                        stalkScore -= PENALTY_FAILED_GOAL;
                        gfc.recordFailure(AiGoalType.STALK_HOST, tick, 40);
                    }
                }
                default -> {}
            }
        }

        infectScore -= gfc.getPenalty(AiGoalType.INFECT_HOST, tick);
        stalkScore -= gfc.getPenalty(AiGoalType.STALK_HOST, tick);
        retreatScore -= gfc.getPenalty(AiGoalType.RETREAT_AND_HIDE, tick);
        investigateScore -= gfc.getPenalty(AiGoalType.INVESTIGATE, tick);

        infectScore = Math.max(0f, infectScore);
        stalkScore = Math.max(0f, stalkScore);
        retreatScore = Math.max(0f, retreatScore);
        investigateScore = Math.max(0f, investigateScore);

        var activeGoalType = blackboard.get(CommonBlackboardKeys.ACTIVE_GOAL_TYPE);
        if (activeGoalType != null) {
            switch (activeGoalType) {
                case AiGoalType.INFECT_HOST -> infectScore += HYSTERESIS_BONUS;
                case AiGoalType.STALK_HOST -> stalkScore += HYSTERESIS_BONUS;
                case AiGoalType.RETREAT_AND_HIDE -> retreatScore += HYSTERESIS_BONUS;
                case AiGoalType.INVESTIGATE -> investigateScore += HYSTERESIS_BONUS;
                case AiGoalType.WANDER -> wanderScore += HYSTERESIS_BONUS;
                default -> {}
            }
        }

        AiGoalType chosen;
        float chosenScore;
        LivingEntity chosenTarget;
        BlockPos chosenDest;
        GoalUrgency chosenUrgency;
        boolean interruptible;
        String reason;

        if (
            retreatScore >= infectScore && retreatScore >= stalkScore
                && retreatScore >= investigateScore && retreatScore >= wanderScore
        ) {
            chosen = AiGoalType.RETREAT_AND_HIDE;
            chosenScore = retreatScore;
            chosenTarget = null;
            chosenDest = retreatDest;
            chosenUrgency = target != null ? GoalUrgency.EMERGENCY : GoalUrgency.HIGH;
            interruptible = false;
            reason = buildReason("Low health, retreating", feedback);
        } else if (infectScore >= stalkScore && infectScore >= investigateScore && infectScore >= wanderScore) {
            chosen = AiGoalType.INFECT_HOST;
            chosenScore = infectScore;
            chosenTarget = infectTarget;
            chosenDest = infectTarget != null ? infectTarget.blockPosition() : null;
            chosenUrgency = GoalUrgency.HIGH;
            interruptible = false;
            reason = "Target in range, attempting infection";
        } else if (stalkScore >= investigateScore && stalkScore >= wanderScore) {
            chosen = AiGoalType.STALK_HOST;
            chosenScore = stalkScore;
            chosenTarget = stalkTarget;
            chosenDest = stalkDest;
            chosenUrgency = GoalUrgency.NORMAL;
            interruptible = true;
            reason = "Stalking visible target";
        } else if (investigateScore >= wanderScore) {
            chosen = AiGoalType.INVESTIGATE;
            chosenScore = investigateScore;
            chosenTarget = null;
            chosenDest = investigateDest;
            chosenUrgency = GoalUrgency.NORMAL;
            interruptible = true;
            reason = buildReason("Investigating last known position", feedback);
        } else {
            chosen = AiGoalType.WANDER;
            chosenScore = wanderScore;
            chosenTarget = null;
            chosenDest = null;
            chosenUrgency = GoalUrgency.LOW;
            interruptible = true;
            reason = "Nothing of interest";
        }

        return PlannedGoal.of(
            chosen,
            chosenScore,
            tick,
            MIN_COMMIT_TICKS,
            MAX_COMMIT_TICKS,
            chosenTarget,
            chosenDest,
            chosenUrgency,
            interruptible,
            reason
        );
    }

    @SuppressWarnings("unchecked")
    private static PlanFeedback<AiGoalType> readFeedback(Blackboard blackboard, int tick) {
        var full = (PlanFeedback<AiGoalType>) blackboard.get(CommonBlackboardKeys.LAST_PLAN_FEEDBACK);
        if (full != null)
            return full;

        var raw = (PlanFailureReason) blackboard.get(CommonBlackboardKeys.LAST_FAILURE_REASON);
        if (raw != null && raw != PlanFailureReason.NONE) {
            var activeGoal = (AiGoalType) blackboard.get(CommonBlackboardKeys.ACTIVE_GOAL_TYPE);
            return PlanFeedback.of(
                raw,
                tick,
                null,
                activeGoal != null ? activeGoal : AiGoalType.NONE
            );
        }
        return null;
    }

    private static String buildReason(String base, PlanFeedback<AiGoalType> feedback) {
        if (feedback == null || feedback.isNone())
            return base;
        return base + " [after " + feedback.reason().name() + "]";
    }

    /**
     * Package-private (not {@code private}) so {@link FacehuggerTree} can reuse the same "find somewhere dark and
     * enclosed" logic for its own critical-health emergency check, without duplicating this scan.
     */
    static BlockPos findHidePosition(FacehuggerEntity mob) {
        var origin = mob.blockPosition();
        var level = mob.level();
        var rng = mob.getRandom();

        BlockPos best = null;
        var bestLight = Integer.MAX_VALUE;

        for (var i = 0; i < 12; i++) {
            var dx = rng.nextIntBetweenInclusive(-10, 10);
            var dy = rng.nextIntBetweenInclusive(-3, 3);
            var dz = rng.nextIntBetweenInclusive(-10, 10);
            var candidate = origin.offset(dx, dy, dz);

            if (!level.getBlockState(candidate).isAir())
                continue;
            if (!level.getBlockState(candidate.below()).isSolidRender(mob.level(), candidate))
                continue;

            var light = level.getLightEmission(candidate);
            if (light < bestLight) {
                bestLight = light;
                best = candidate;
            }
        }

        return best != null
            ? best
            : origin.offset(
                rng.nextIntBetweenInclusive(-6, 6),
                0,
                rng.nextIntBetweenInclusive(-6, 6)
            );
    }
}
