package mod.azure.ovomorphosis.ai.goap;

import net.minecraft.core.BlockPos;

import mod.azure.ovomorphosis.ai.core.AiKeys;

/**
 * Immutable snapshot written to the blackboard when an action finishes with a non-{@link PlanFailureReason#NONE}
 * reason.
 * <p>
 * Planners read this on the next planning interval to adjust goal scores. The record is intentionally lightweight — it
 * only captures information that is cheap to collect at action termination time.
 * <h3>Lifecycle</h3>
 * <ol>
 * <li>An action calls {@link #of} (or writes directly) on failure or notable termination.</li>
 * <li>It stores the result under {@link AiKeys#LAST_PLAN_FEEDBACK}.</li>
 * <li>The planner reads the feedback, applies score modifiers, then <b>clears</b> the key so stale feedback does not
 * bleed into future planning cycles.</li>
 * </ol>
 * <h3>Example — inside an action</h3>
 *
 * <pre>{@code
 * if (path == null) {
 *     blackboard.set(
 *         AiKeys.LAST_PLAN_FEEDBACK,
 *         PlanFeedback.of(PlanFailureReason.FAILED_NO_PATH, mob.blockPosition(), activeGoalType)
 *     );
 *     return ActionStatus.FAILURE;
 * }
 * }</pre>
 *
 * @param reason         Why the action failed or was interrupted.
 * @param recordedAtTick The game tick at which the failure was recorded. Planners use this to decide whether the
 *                       feedback is still fresh enough to act on (e.g. discard if more than 60 ticks old).
 * @param failurePos     World position where the failure occurred, or the mob's position at the time of failure. Useful
 *                       for INVESTIGATE goals that want to visit the last-known-failure site.
 * @param failedGoalType The goal type that was active when the failure occurred. Lets the planner suppress that goal
 *                       type specifically rather than penalizing all goals equally.
 */
public record PlanFeedback(
    PlanFailureReason reason,
    int recordedAtTick,
    BlockPos failurePos,
    AiGoalType failedGoalType
) {

    public static PlanFeedback of(
        PlanFailureReason reason,
        int currentTick,
        BlockPos failurePos,
        AiGoalType failedGoalType
    ) {
        return new PlanFeedback(reason, currentTick, failurePos, failedGoalType);
    }

    public boolean isFresh(int currentTick) {
        return (currentTick - recordedAtTick) <= 80;
    }

    public boolean isNone() {
        return reason == PlanFailureReason.NONE;
    }
}
