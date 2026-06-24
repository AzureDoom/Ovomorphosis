package mod.azure.ovomorphosis.entities.chestburster;

import net.minecraft.world.entity.LivingEntity;

import mod.azure.ovomorphosis.ai.actions.chestburster.EatFoodAction;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.Blackboard;
import mod.azure.ovomorphosis.ai.core.Cooldowns;
import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.ai.goap.GoalPlanner;
import mod.azure.ovomorphosis.ai.goap.GoalUrgency;
import mod.azure.ovomorphosis.ai.goap.PlannedGoal;

/**
 * GOAP planner for {@link ChestbursterEntity}.
 * <p>
 * Scores four goals each planning interval:
 * <ul>
 * <li>{@link AiGoalType#HIDE} — flee to safety when a threat is present.</li>
 * <li>{@link AiGoalType#FIND_FOOD} — seek and eat nearby food to accelerate growth.</li>
 * <li>{@link AiGoalType#GROW_SAFE} — no threat, no food; just survive and grow.</li>
 * <li>{@link AiGoalType#WANDER} — fallback idle roam.</li>
 * </ul>
 */
public final class ChestbursterGoalPlanner implements GoalPlanner<ChestbursterEntity> {

    private static final int MIN_COMMIT_TICKS = 20;

    private static final int MAX_COMMIT_TICKS = 160;

    private static final float HUNGER_SCORE_SCALE = 0.05f;

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
        int tick = (int) mob.level().getGameTime();

        var threat = blackboard.get(AiKeys.TARGET, LivingEntity.class);
        var hasThreat = threat != null && threat.isAlive();

        var hideScore = 0f;
        var foodScore = 0f;
        var growScore = 5f;

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

        AiGoalType chosen;
        float chosenScore;
        GoalUrgency chosenUrgency;
        boolean interruptible;
        String reason;

        if (hideScore >= foodScore && hideScore >= growScore) {
            chosen = AiGoalType.HIDE;
            chosenScore = hideScore;
            chosenUrgency = hideScore >= 100f ? GoalUrgency.EMERGENCY : GoalUrgency.HIGH;
            interruptible = false;
            reason = "Threat nearby, hiding";
        } else if (foodScore >= growScore) {
            chosen = AiGoalType.FIND_FOOD;
            chosenScore = foodScore;
            chosenUrgency = GoalUrgency.NORMAL;
            interruptible = true;
            reason = "Food detected, going to eat";
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
            hasThreat && chosen == AiGoalType.HIDE ? threat : null,
            null,
            chosenUrgency,
            interruptible,
            reason
        );
    }
}
