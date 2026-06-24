package mod.azure.ovomorphosis.entities.chestburster;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import mod.azure.ovomorphosis.ai.actions.chestburster.EatFoodAction;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.Blackboard;
import mod.azure.ovomorphosis.ai.core.Cooldowns;
import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.ai.goap.GoalApplicator;
import mod.azure.ovomorphosis.ai.goap.GoalPlanner;
import mod.azure.ovomorphosis.ai.goap.GoalUrgency;
import mod.azure.ovomorphosis.ai.goap.PlanFailureReason;
import mod.azure.ovomorphosis.ai.goap.PlanFeedback;
import mod.azure.ovomorphosis.ai.goap.PlannedGoal;

/**
 * GOAP planner for {@link ChestbursterEntity}.
 * <p>
 * Scores five goals each planning interval:
 * <ul>
 * <li>{@link AiGoalType#HIDE} — flee to safety when a threat is present.</li>
 * <li>{@link AiGoalType#FIND_FOOD} — seek and eat nearby food to accelerate growth.</li>
 * <li>{@link AiGoalType#GROW_SAFE} — no threat, no food; survive and grow.</li>
 * <li>{@link AiGoalType#INVESTIGATE} — check the last position a threat was seen from.</li>
 * <li>{@link AiGoalType#WANDER} — fallback idle roam.</li>
 * </ul>
 * <h3>Failure feedback</h3> Actions write a {@link PlanFeedback} to {@link AiKeys#LAST_PLAN_FEEDBACK} when they fail.
 * This planner reads that feedback <em>before</em> scoring and applies additive score modifiers:
 * <ul>
 * <li>{@link PlanFailureReason#FAILED_NO_PATH} / {@link PlanFailureReason#FAILED_STUCK} → penalize the failing goal;
 * nudge INVESTIGATE to try a different route.</li>
 * <li>{@link PlanFailureReason#FAILED_TARGET_LOST} → boost INVESTIGATE toward last-seen.</li>
 * <li>{@link PlanFailureReason#FAILED_DANGER} → hard boost HIDE.</li>
 * <li>{@link PlanFailureReason#FAILED_COOLDOWN} → suppress the goal that was on cooldown.</li>
 * </ul>
 * Feedback is cleared by {@link GoalApplicator#apply} after a new goal is committed.
 */
public final class ChestbursterGoalPlanner implements GoalPlanner<ChestbursterEntity> {

    private static final int MIN_COMMIT_TICKS = 20;

    private static final int MAX_COMMIT_TICKS = 160;

    private static final float HUNGER_SCORE_SCALE = 0.05f;

    private static final float BOOST_INVESTIGATE = 30f;

    private static final float BOOST_HIDE_DANGER = 50f;

    private static final float PENALTY_FAILED_GOAL = 35f;

    private final EatFoodAction eatFoodAction;

    public ChestbursterGoalPlanner(EatFoodAction eatFoodAction) {
        this.eatFoodAction = eatFoodAction;
    }

    @Override
    public PlannedGoal<ChestbursterEntity> chooseGoal(
        ChestbursterEntity mob,
        Blackboard blackboard,
        Cooldowns cooldowns
    ) {
        var tick = (int) mob.level().getGameTime();

        var feedback = readFeedback(blackboard, tick);

        var threat = blackboard.get(AiKeys.TARGET, LivingEntity.class);
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

        if (eatFoodAction.canStart(mob)) {
            var growthFraction = mob.getGrowth() / mob.getMaxGrowth();
            foodScore = 50f + (1f - growthFraction) * (1f / HUNGER_SCORE_SCALE);
        }

        var lastSeenPos = blackboard.get(AiKeys.LAST_SEEN_POS, BlockPos.class);
        if (lastSeenPos != null && !hasThreat) {
            investigateScore = 20f;
        }

        if (feedback != null && feedback.isFresh(tick)) {
            var reason = feedback.reason();
            var failedGoal = feedback.failedGoalType();

            switch (reason) {
                case FAILED_TARGET_LOST -> {
                    investigateScore += BOOST_INVESTIGATE;
                }
                case FAILED_NO_PATH, FAILED_STUCK, FAILED_BLOCKED -> {
                    if (failedGoal == AiGoalType.HIDE)
                        hideScore -= PENALTY_FAILED_GOAL;
                    if (failedGoal == AiGoalType.FIND_FOOD)
                        foodScore -= PENALTY_FAILED_GOAL;
                    if (failedGoal == AiGoalType.GROW_SAFE)
                        growScore -= PENALTY_FAILED_GOAL;
                    investigateScore += BOOST_INVESTIGATE * 0.5f;
                }
                case FAILED_DANGER -> {
                    hideScore += BOOST_HIDE_DANGER;
                    foodScore -= PENALTY_FAILED_GOAL;
                }
                case FAILED_COOLDOWN -> {
                    if (failedGoal == AiGoalType.FIND_FOOD)
                        foodScore -= PENALTY_FAILED_GOAL;
                    if (failedGoal == AiGoalType.HIDE)
                        hideScore -= PENALTY_FAILED_GOAL;
                }
                default -> { /* NONE, etc. — no adjustment */ }
            }
        }

        hideScore = Math.max(0f, hideScore);
        foodScore = Math.max(0f, foodScore);
        growScore = Math.max(0f, growScore);
        investigateScore = Math.max(0f, investigateScore);

        AiGoalType chosen;
        float chosenScore;
        GoalUrgency chosenUrgency;
        boolean interruptible;
        String reason;
        net.minecraft.core.BlockPos chosenDest = null;
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
                : feedback.failurePos();
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

    private static PlanFeedback readFeedback(Blackboard blackboard, int tick) {
        var full = blackboard.get(AiKeys.LAST_PLAN_FEEDBACK, PlanFeedback.class);
        if (full != null)
            return full;

        var raw = blackboard.get(AiKeys.LAST_FAILURE_REASON, PlanFailureReason.class);
        if (raw != null && raw != PlanFailureReason.NONE) {
            var activeGoalType = blackboard.get(AiKeys.ACTIVE_GOAL_TYPE, AiGoalType.class);
            return PlanFeedback.of(
                raw,
                tick,
                null,
                activeGoalType != null ? activeGoalType : AiGoalType.NONE
            );
        }
        return null;
    }

    private static String buildReason(String base, PlanFeedback feedback) {
        if (feedback == null || feedback.isNone())
            return base;
        return base + " [after " + feedback.reason().name() + "]";
    }
}
