package mod.azure.ovomorphosis.entities.chestburster;

import com.azure.azurecortex.api.blackboard.Blackboard;
import com.azure.azurecortex.api.blackboard.CommonBlackboardKeys;
import com.azure.azurecortex.api.goal.GoalUrgency;
import com.azure.azurecortex.goap.*;
import com.azure.azurecortex.runtime.CooldownTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import mod.azure.ovomorphosis.ai.actions.chestburster.EatFoodAction;
import mod.azure.ovomorphosis.ai.goap.AiGoalType;

/**
 * GOAP planner for {@link ChestbursterEntity}.
 * <h3>Anti-thrash design</h3> Mirrors the Xenomorph planner structure:
 * <ul>
 * <li><b>Hysteresis</b> — active goal receives {@link #HYSTERESIS_BONUS} so a challenger must clearly outscore it to
 * displace it.</li>
 * <li><b>GoalFailureCooldowns</b> — per-goal decaying penalty that persists beyond the 80-tick {@link PlanFeedback}
 * freshness window.</li>
 * <li><b>Emergency threshold</b> — HIDE at close range scores ≥ 100 and is always tagged {@link GoalUrgency#EMERGENCY},
 * bypassing min-commit on the next cycle.</li>
 * </ul>
 */
public final class ChestbursterGoalPlanner implements GoalPlanner<ChestbursterEntity, AiGoalType> {

    private static final int MIN_COMMIT_TICKS = 20;

    private static final int MAX_COMMIT_TICKS = 160;

    private static final float HUNGER_SCORE_SCALE = 0.05f;

    private static final float BOOST_INVESTIGATE = 30f;

    private static final float BOOST_HIDE_DANGER = 50f;

    private static final float PENALTY_FAILED_GOAL = 35f;

    private static final float HYSTERESIS_BONUS = 15f;

    private final EatFoodAction<AiGoalType> eatFoodAction;

    public ChestbursterGoalPlanner(EatFoodAction<AiGoalType> eatFoodAction) {
        this.eatFoodAction = eatFoodAction;
    }

    @Override
    public PlannedGoal<ChestbursterEntity, AiGoalType> chooseGoal(
        ChestbursterEntity mob,
        Blackboard blackboard,
        CooldownTracker cooldowns
    ) {
        var tick = (int) mob.level().getGameTime();

        var gfc = GoalFailureCooldowns.getOrCreate(blackboard);
        gfc.evictExpired(tick);

        var feedback = readFeedback(blackboard, tick);

        var threat = blackboard.get(CommonBlackboardKeys.TARGET);
        var hasThreat = threat != null && threat.isAlive();

        var hideScore = 0f;
        var foodScore = 0f;
        var growScore = 5f;
        var investigateScore = 0f;

        if (hasThreat) {
            var distSq = mob.distanceToSqr(threat);
            hideScore = distSq <= 4.0 * 4.0
                ? 100f
                : Math.max(0f, 80f - (float) (distSq / (20.0 * 20.0)) * 80f);
        }

        if (eatFoodAction.canStart(mob, blackboard)) {
            var growthFraction = mob.getGrowth() / mob.getMaxGrowth();
            foodScore = 50f + (1f - growthFraction) * (1f / HUNGER_SCORE_SCALE);
        }

        var lastSeenPos = blackboard.get(CommonBlackboardKeys.LAST_SEEN_POS);
        if (lastSeenPos != null && !hasThreat) {
            investigateScore = 20f;
        }

        if (feedback != null && feedback.isFresh(tick)) {
            var reason = feedback.reason();
            var failedGoal = feedback.failedGoalType();

            switch (reason) {
                case FAILED_TARGET_LOST -> investigateScore += BOOST_INVESTIGATE;
                case FAILED_NO_PATH, FAILED_STUCK, FAILED_BLOCKED -> {
                    if (failedGoal == AiGoalType.HIDE) {
                        hideScore -= PENALTY_FAILED_GOAL;
                        gfc.recordFailure(AiGoalType.HIDE, tick, 120);
                    }
                    if (failedGoal == AiGoalType.FIND_FOOD) {
                        foodScore -= PENALTY_FAILED_GOAL;
                        gfc.recordFailure(AiGoalType.FIND_FOOD, tick, 100);
                    }
                    if (failedGoal == AiGoalType.GROW_SAFE) {
                        growScore -= PENALTY_FAILED_GOAL;
                        gfc.recordFailure(AiGoalType.GROW_SAFE, tick, 80);
                    }
                    investigateScore += BOOST_INVESTIGATE * 0.5f;
                }
                case FAILED_DANGER -> {
                    hideScore += BOOST_HIDE_DANGER;
                    foodScore -= PENALTY_FAILED_GOAL;
                    gfc.recordFailure(AiGoalType.FIND_FOOD, tick, 200);
                }
                case FAILED_COOLDOWN -> {
                    if (failedGoal == AiGoalType.FIND_FOOD) {
                        foodScore -= PENALTY_FAILED_GOAL;
                        gfc.recordFailure(AiGoalType.FIND_FOOD, tick, 40);
                    }
                    if (failedGoal == AiGoalType.HIDE) {
                        hideScore -= PENALTY_FAILED_GOAL;
                        gfc.recordFailure(AiGoalType.HIDE, tick, 40);
                    }
                }
                default -> {}
            }
        }

        hideScore -= gfc.getPenalty(AiGoalType.HIDE, tick);
        foodScore -= gfc.getPenalty(AiGoalType.FIND_FOOD, tick);
        growScore -= gfc.getPenalty(AiGoalType.GROW_SAFE, tick);
        investigateScore -= gfc.getPenalty(AiGoalType.INVESTIGATE, tick);

        hideScore = Math.max(0f, hideScore);
        foodScore = Math.max(0f, foodScore);
        growScore = Math.max(0f, growScore);
        investigateScore = Math.max(0f, investigateScore);

        var activeGoalType = blackboard.get(CommonBlackboardKeys.ACTIVE_GOAL_TYPE);
        if (activeGoalType != null) {
            if (activeGoalType.equals(AiGoalType.HIDE)) {
                hideScore += HYSTERESIS_BONUS;
            } else if (activeGoalType.equals(AiGoalType.FIND_FOOD)) {
                foodScore += HYSTERESIS_BONUS;
            } else if (activeGoalType.equals(AiGoalType.GROW_SAFE) || activeGoalType.equals(AiGoalType.WANDER)) {
                growScore += HYSTERESIS_BONUS;
            } else if (activeGoalType.equals(AiGoalType.INVESTIGATE)) {
                investigateScore += HYSTERESIS_BONUS;
            }
        }

        AiGoalType chosen;
        float chosenScore;
        GoalUrgency chosenUrgency;
        boolean interruptible;
        String reason;
        BlockPos chosenDest = null;
        LivingEntity chosenTarget = null;

        if (hideScore >= foodScore && hideScore >= growScore && hideScore >= investigateScore) {
            chosen = AiGoalType.HIDE;
            chosenScore = hideScore;
            chosenUrgency = hideScore >= 100f ? GoalUrgency.EMERGENCY : GoalUrgency.HIGH;
            interruptible = false;
            chosenTarget = hasThreat ? threat : null;
            reason = buildReason("Threat nearby, hiding", feedback);
        } else if (foodScore >= growScore && foodScore >= investigateScore) {
            chosen = AiGoalType.FIND_FOOD;
            chosenScore = foodScore;
            chosenUrgency = GoalUrgency.NORMAL;
            interruptible = true;
            reason = "Food detected, going to eat";
        } else if (investigateScore >= growScore) {
            chosen = AiGoalType.INVESTIGATE;
            chosenScore = investigateScore;
            chosenUrgency = GoalUrgency.NORMAL;
            interruptible = true;
            chosenDest = lastSeenPos != null
                ? lastSeenPos
                : (feedback != null ? feedback.failurePos() : null);
            reason = buildReason("Investigating last known position", feedback);
        } else {
            chosen = AiGoalType.GROW_SAFE;
            chosenScore = growScore;
            chosenUrgency = GoalUrgency.LOW;
            interruptible = true;
            reason = "Growing safely";
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
}
