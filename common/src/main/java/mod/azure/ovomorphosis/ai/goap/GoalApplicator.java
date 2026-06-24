package mod.azure.ovomorphosis.ai.goap;

import net.minecraft.world.entity.Mob;

import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.Blackboard;

/**
 * Writes a committed {@link PlannedGoal} to the blackboard and provides the {@link #shouldReplan} gate that controls
 * when the planner is allowed to run.
 * <h3>Replan policy</h3>
 * <ol>
 * <li><b>Emergency override</b> — a new plan whose urgency is {@link GoalUrgency#EMERGENCY} always replaces the current
 * goal immediately, regardless of commit timers.</li>
 * <li><b>Min-commit lock</b> — the planner is suppressed until {@link PlannedGoal#canReplan(int)} returns {@code true}
 * (i.e. at least {@code minCommitTicks} have elapsed since the goal started). This prevents per-tick thrashing when two
 * goals score closely.</li>
 * <li><b>Max-commit expiry</b> — once {@link PlannedGoal#isExpired(int)} is true the planner is forced to run
 * regardless of other conditions, preventing a goal from running forever if the mob gets stuck in a state that never
 * self-terminates.</li>
 * <li><b>Normal replan</b> — once the min-commit window has passed the planner runs on its normal cadence (gated by
 * {@link AiKeys#GOAL_REPLAN} cooldown in the entity).</li>
 * </ol>
 */
public final class GoalApplicator {

    private GoalApplicator() {}

    /**
     * Returns {@code true} when the planner should be invoked this tick.
     * <p>
     * Call this from the entity's planner-invocation site <em>before</em> calling {@code planner.chooseGoal()}:
     *
     * <pre>{@code
     * if (GoalApplicator.shouldReplan(blackboard, currentTick, candidateUrgency)) {
     *     var newGoal = planner.chooseGoal(mob, blackboard, cooldowns);
     *     GoalApplicator.apply(mob, blackboard, newGoal);
     * }
     * }</pre>
     *
     * @param blackboard       the mob's blackboard
     * @param currentTick      current game tick
     * @param candidateUrgency the urgency of the highest-priority candidate goal, if known; pass {@code null} to skip
     *                         the emergency-override check
     * @return {@code true} if replanning should proceed
     */
    public static boolean shouldReplan(
        Blackboard blackboard,
        int currentTick,
        GoalUrgency candidateUrgency
    ) {
        var active = blackboard.get(AiKeys.ACTIVE_GOAL, PlannedGoal.class);

        if (active == null)
            return true;

        if (active.isExpired(currentTick))
            return true;

        if (candidateUrgency == GoalUrgency.EMERGENCY)
            return true;

        return active.canReplan(currentTick);
    }

    /**
     * Convenience overload that skips the emergency-urgency check. Use when the caller has not yet scored candidates
     * and cannot know the urgency.
     */
    public static boolean shouldReplan(Blackboard blackboard, int currentTick) {
        return shouldReplan(blackboard, currentTick, null);
    }

    /**
     * Writes {@code goal} to the blackboard as the active goal and clears stale feedback keys.
     *
     * @param mob        the mob whose blackboard is being updated
     * @param blackboard the blackboard
     * @param goal       the newly chosen goal
     */
    public static <E extends Mob> void apply(E mob, Blackboard blackboard, PlannedGoal<E> goal) {
        if (mob.isNoAi())
            return;
        blackboard.set(AiKeys.ACTIVE_GOAL, goal);
        blackboard.set(AiKeys.ACTIVE_GOAL_TYPE, goal.type());
        blackboard.set(AiKeys.LAST_GOAL_REASON, goal.reason());

        goal.target()
            .ifPresent(target -> blackboard.set(AiKeys.GOAL_TARGET, target));

        goal.destination()
            .ifPresent(pos -> blackboard.set(AiKeys.GOAL_DESTINATION, pos));

        blackboard.set(AiKeys.LAST_PLAN_FEEDBACK, null);
        blackboard.set(AiKeys.LAST_FAILURE_REASON, null);
    }
}
