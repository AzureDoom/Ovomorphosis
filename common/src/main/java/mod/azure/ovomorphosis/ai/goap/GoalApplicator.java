package mod.azure.ovomorphosis.ai.goap;

import net.minecraft.world.entity.Mob;

import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.Blackboard;

public final class GoalApplicator {

    private GoalApplicator() {}

    public static <E extends Mob> void apply(E mob, Blackboard blackboard, PlannedGoal<E> goal) {
        blackboard.set(AiKeys.ACTIVE_GOAL, goal);
        blackboard.set(AiKeys.ACTIVE_GOAL_TYPE, goal.type());
        blackboard.set(AiKeys.LAST_GOAL_REASON, goal.reason());

        goal.target()
            .ifPresent(
                target -> blackboard.set(AiKeys.GOAL_TARGET, target)
            );

        goal.destination()
            .ifPresent(
                pos -> blackboard.set(AiKeys.GOAL_DESTINATION, pos)
            );
    }
}
