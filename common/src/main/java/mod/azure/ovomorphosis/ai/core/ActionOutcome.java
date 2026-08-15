package mod.azure.ovomorphosis.ai.core;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.ai.goap.PlanFailureReason;

/**
 * The result of a single {@link Action#tick} call.
 * <h3>Why this replaces the old {@code ActionStatus} return value</h3> Previously every action decided for itself
 * whether and how to write {@link mod.azure.ovomorphosis.ai.goap.PlanFeedback} to the blackboard on failure — a
 * convention that was easy to follow inconsistently and easy for a new action to forget entirely. {@link ActionOutcome}
 * makes "why did this not go well" part of the return value itself, so {@link MobBrainRuntime} can extract it and write
 * {@link mod.azure.ovomorphosis.ai.goap.PlanFeedback} in exactly one place, for every action, unconditionally.
 * <h3>The four cases</h3>
 * <ul>
 * <li>{@link Running} — nothing noteworthy this tick; keep executing, no feedback written.</li>
 * <li>{@link Success} — the action achieved its goal; it is stopped with {@link ActionStatus#SUCCESS}, no feedback
 * written.</li>
 * <li>{@link Blocked} — the action hit a recoverable obstacle <em>and is still trying</em> (e.g. a repath attempt
 * failed, but the action hasn't given up carrying/moving/placing yet). The runtime writes {@link PlanFeedback} from
 * {@code reason}/{@code at} <em>without</em> stopping the action, so GOAP can start biasing scores away from the
 * current strategy in real time, well before any hard failure cap is reached.</li>
 * <li>{@link Failed} — the action has given up entirely. The runtime writes {@link PlanFeedback} (unless {@code reason}
 * is {@link PlanFailureReason#NONE}) and stops the action with {@link ActionStatus#FAILURE}.</li>
 * </ul>
 * <h3>Goal-type attribution</h3> By default the runtime attributes {@link Blocked}/{@link Failed} feedback to whatever
 * {@code AiKeys.ACTIVE_GOAL_TYPE} currently is. Some actions fire opportunistically outside the goal their failure is
 * conceptually "about" (e.g. resin placement can run as a side effect of an unrelated goal, but a placement failure is
 * still specifically about {@code EXPAND_HIVE}). For those cases, {@code goalType} lets the action override the
 * attribution explicitly instead of it being silently misattributed to whatever goal happened to be active.
 * <p>
 * Note there is no "Interrupted" case here: an action never decides for itself that it was interrupted — only
 * {@link MobBrainRuntime} does that, when it preempts a running action in favor of a higher-priority or emergency
 * candidate. An action that can no longer proceed (target died, prerequisite vanished, mob health hit zero, ...) should
 * return {@link Failed} (with a reason if one applies, or {@link #failed()} if none does); the runtime is the only
 * source of {@link ActionStatus#INTERRUPTED}.
 */
public sealed interface ActionOutcome {

    /** Shared, allocation-free instance for the common "still running, nothing to report" case. */
    ActionOutcome RUNNING = new Running();

    /** Shared, allocation-free instance for the common "succeeded" case. */
    ActionOutcome SUCCESS = new Success();

    record Running() implements ActionOutcome {}

    record Success() implements ActionOutcome {}

    /**
     * The action hit a recoverable obstacle and is still trying — it keeps running, but the runtime surfaces
     * {@code reason} to GOAP as {@link PlanFeedback} this tick so the planner can start responding before any hard
     * failure cap forces a full stop.
     *
     * @param reason   why this tick didn't make the progress it wanted
     * @param at       where the obstruction was observed, or {@code null} to default to the mob's current position
     * @param goalType overrides which {@link AiGoalType} this feedback is attributed to, or {@code null} to use
     *                 whatever goal is currently active on the blackboard (the common case)
     */
    record Blocked(
        PlanFailureReason reason,
        @Nullable BlockPos at,
        @Nullable AiGoalType goalType
    ) implements ActionOutcome {}

    /**
     * The action has given up entirely and will be stopped with {@link ActionStatus#FAILURE}.
     *
     * @param reason   why the action failed, or {@link PlanFailureReason#NONE} if there's nothing worth reporting to
     *                 GOAP (the runtime skips writing {@link PlanFeedback} in that case, matching the historical
     *                 behavior of actions that failed silently)
     * @param at       where the failure occurred, or {@code null} to default to the mob's current position
     * @param goalType overrides which {@link AiGoalType} this feedback is attributed to, or {@code null} to use
     *                 whatever goal is currently active on the blackboard (the common case)
     */
    record Failed(
        PlanFailureReason reason,
        @Nullable BlockPos at,
        @Nullable AiGoalType goalType
    ) implements ActionOutcome {}

    /**
     * @param reason why this tick didn't make progress
     * @param at     where the obstruction was observed
     * @return a {@link Blocked} outcome
     */
    static ActionOutcome blocked(PlanFailureReason reason, BlockPos at) {
        return new Blocked(reason, at, null);
    }

    /**
     * @param reason why this tick didn't make progress
     * @return a {@link Blocked} outcome, defaulting the position to the mob's current position
     */
    static ActionOutcome blocked(PlanFailureReason reason) {
        return new Blocked(reason, null, null);
    }

    /**
     * @param reason   why this tick didn't make progress
     * @param at       where the obstruction was observed
     * @param goalType the goal type this feedback should be attributed to, regardless of what's currently active
     * @return a {@link Blocked} outcome with an explicit goal-type attribution
     */
    static ActionOutcome blocked(PlanFailureReason reason, BlockPos at, AiGoalType goalType) {
        return new Blocked(reason, at, goalType);
    }

    /**
     * @param reason why the action failed
     * @param at     where the failure occurred
     * @return a {@link Failed} outcome
     */
    static ActionOutcome failed(PlanFailureReason reason, BlockPos at) {
        return new Failed(reason, at, null);
    }

    /**
     * @param reason why the action failed
     * @return a {@link Failed} outcome, defaulting the position to the mob's current position
     */
    static ActionOutcome failed(PlanFailureReason reason) {
        return new Failed(reason, null, null);
    }

    /**
     * @param reason   why the action failed
     * @param at       where the failure occurred
     * @param goalType the goal type this feedback should be attributed to, regardless of what's currently active
     * @return a {@link Failed} outcome with an explicit goal-type attribution
     */
    static ActionOutcome failed(PlanFailureReason reason, BlockPos at, AiGoalType goalType) {
        return new Failed(reason, at, goalType);
    }

    /**
     * @param reason   why the action failed
     * @param goalType the goal type this feedback should be attributed to, regardless of what's currently active
     * @return a {@link Failed} outcome with an explicit goal-type attribution, defaulting the position to the mob's
     *         current position
     */
    static ActionOutcome failed(PlanFailureReason reason, AiGoalType goalType) {
        return new Failed(reason, null, goalType);
    }

    /**
     * @return a {@link Failed} outcome with no reason worth reporting to GOAP (no {@link PlanFeedback} is written) —
     *         use for genuinely uninteresting failures (e.g. a precondition that vanished mid-tick with no clean
     *         {@link PlanFailureReason} mapping), not as a default to avoid picking a real reason.
     */
    static ActionOutcome failed() {
        return new Failed(PlanFailureReason.NONE, null, null);
    }
}
