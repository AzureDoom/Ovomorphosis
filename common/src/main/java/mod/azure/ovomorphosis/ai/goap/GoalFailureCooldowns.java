package mod.azure.ovomorphosis.ai.goap;

import java.util.EnumMap;
import java.util.Map;

import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.Blackboard;

/**
 * Tracks per-{@link AiGoalType} failure cooldowns so the planner can suppress goal types that have recently failed,
 * beyond the 80-tick window of {@link PlanFeedback}.
 * <h3>Usage</h3> Store one instance on the blackboard under {@link AiKeys#GOAL_FAILURE_COOLDOWNS}. The planner reads it
 * at the start of {@code chooseGoal} and calls {@link #getPenalty} to get an additive score penalty for each goal type,
 * then records failures via {@link #recordFailure}.
 *
 * <pre>{@code
 * // In chooseGoal:
 * var gfc = GoalFailureCooldowns.getOrCreate(blackboard);
 * gfc.tick(currentTick);
 *
 * huntScore -= gfc.getPenalty(AiGoalType.HUNT_TARGET);
 * ambushScore -= gfc.getPenalty(AiGoalType.AMBUSH_TARGET);
 * // ... etc.
 *
 * // When a goal fails (in the action or feedback switch):
 * gfc.recordFailure(AiGoalType.HUNT_TARGET, currentTick, 100); // suppress for 100 ticks
 * }</pre>
 *
 * <h3>Penalty decay</h3> The penalty is not binary on/off. It scales linearly from 60 at the moment of failure down to
 * {@code 0} at expiry. This means a goal that failed recently is heavily suppressed but the suppression fades
 * gradually, allowing the planner to naturally re-select it once circumstances change enough to overcome the decaying
 * penalty.
 */
public final class GoalFailureCooldowns {

    public static final int DEFAULT_DURATION = 200;

    private record Entry(
        int failedAtTick,
        int durationTicks
    ) {

        boolean isActive(int currentTick) {
            return (currentTick - failedAtTick) < durationTicks;
        }

        float penalty(int currentTick) {
            if (!isActive(currentTick))
                return 0f;
            var elapsed = currentTick - failedAtTick;
            var fraction = 1f - (float) elapsed / durationTicks;
            return 60f * fraction;
        }
    }

    private final Map<AiGoalType, Entry> entries = new EnumMap<>(AiGoalType.class);

    /**
     * Retrieves the instance stored on {@code blackboard}, creating and storing a new one if none exists yet.
     */
    public static GoalFailureCooldowns getOrCreate(
        Blackboard blackboard
    ) {
        var existing = blackboard.get(
            AiKeys.GOAL_FAILURE_COOLDOWNS,
            GoalFailureCooldowns.class
        );
        if (existing != null)
            return existing;
        var fresh = new GoalFailureCooldowns();
        blackboard.set(AiKeys.GOAL_FAILURE_COOLDOWNS, fresh);
        return fresh;
    }

    /**
     * Records a failure for {@code goalType}, suppressing it for {@code durationTicks}. If the goal type was already
     * suppressed, the new record replaces the old one only if it would produce a longer suppression window.
     *
     * @param goalType      the goal type that failed
     * @param currentTick   current game tick
     * @param durationTicks how long to suppress this goal type
     */
    public void recordFailure(AiGoalType goalType, int currentTick, int durationTicks) {
        var existing = entries.get(goalType);
        if (existing != null) {
            var existingExpiry = existing.failedAtTick() + existing.durationTicks();
            var newExpiry = currentTick + durationTicks;
            if (newExpiry <= existingExpiry)
                return;
        }
        entries.put(goalType, new Entry(currentTick, durationTicks));
    }

    public void recordFailure(AiGoalType goalType, int currentTick) {
        recordFailure(goalType, currentTick, 200);
    }

    /**
     * Removes all expired entries to keep the map small. Call once per planning cycle before reading penalties.
     */
    public void evictExpired(int currentTick) {
        entries.entrySet().removeIf(e -> !e.getValue().isActive(currentTick));
    }

    /**
     * Returns the current additive score penalty for {@code goalType}. Returns {@code 0} if the goal type has no active
     * failure record.
     *
     * @param goalType    the goal type to query
     * @param currentTick current game tick
     * @return a non-negative penalty value in {@code [0, MAX_PENALTY]}
     */
    public float getPenalty(AiGoalType goalType, int currentTick) {
        var entry = entries.get(goalType);
        return entry == null ? 0f : entry.penalty(currentTick);
    }

    /** Returns {@code true} if {@code goalType} currently has an active failure record. */
    public boolean isSuppressed(AiGoalType goalType, int currentTick) {
        var entry = entries.get(goalType);
        return entry != null && entry.isActive(currentTick);
    }
}
