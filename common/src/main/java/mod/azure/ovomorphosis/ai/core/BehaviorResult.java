package mod.azure.ovomorphosis.ai.core;

/**
 * The value returned by a {@link BehaviorNode} after each tick.
 * <p>
 * Wraps the winning {@link Action} (if any), its priority, and whether the node succeeded. The
 * {@link mod.azure.ovomorphosis.ai.core.MobBrainRuntime} uses the priority to decide whether to preempt the currently
 * running action.
 *
 * @param <E>      the mob type this result targets
 * @param action   the action the node wants to run, or {@code null} if no action was selected
 * @param priority the numeric priority of the selected action; higher values preempt lower ones
 * @param success  {@code true} if the node produced a valid action
 */
public record BehaviorResult<E>(
    Action<E> action,
    int priority,
    boolean success,
    InterruptCategory categoryOverride
) {

    /**
     * Returns an empty result indicating that no action was selected.
     *
     * @param <E> the mob type
     * @return a result with a {@code null} action and zero priority
     */
    public static <E> BehaviorResult<E> none() {
        return new BehaviorResult<>(null, 0, false, null);
    }

    /**
     * Returns a successful result that requests the given {@code action} be started.
     * <p>
     * The result's effective {@link InterruptCategory} is inherited from {@link Action#interruptCategory()}. Use
     * {@link #runEmergency} when the tree itself has determined the situation is an emergency (e.g. critical health)
     * even though the action instance is an otherwise-ordinary one shared with non-emergency branches.
     *
     * @param <E>      the mob type
     * @param action   the action to run
     * @param priority the priority of this action; higher values can preempt lower-priority actions
     * @return a result wrapping the action
     */
    public static <E> BehaviorResult<E> run(Action<E> action, int priority) {
        return new BehaviorResult<>(action, priority, true, null);
    }

    /**
     * Returns a successful result that requests {@code action} be started with an {@link InterruptCategory#EMERGENCY}
     * override, regardless of what {@link Action#interruptCategory()} would otherwise report.
     * <p>
     * Use this when the tree has detected a genuinely critical situation (e.g. health below a critical threshold) that
     * should be able to preempt a {@link InterruptCategory#LOCKED} action, but is driving the mob with an action
     * instance that is also used for ordinary, non-emergency movement (so permanently tagging the action class itself
     * as {@link InterruptCategory#EMERGENCY} would be wrong).
     *
     * @param <E>      the mob type
     * @param action   the action to run
     * @param priority the priority of this action
     * @return a result wrapping the action, tagged as an emergency candidate
     */
    public static <E> BehaviorResult<E> runEmergency(Action<E> action, int priority) {
        return new BehaviorResult<>(action, priority, true, InterruptCategory.EMERGENCY);
    }

    /**
     * Returns the category this result should be treated as when deciding preemption: {@link #categoryOverride} if
     * present, otherwise the action's own {@link Action#interruptCategory()}.
     *
     * @return the effective interrupt category, or {@link InterruptCategory#NORMAL} if there is no action
     */
    public InterruptCategory effectiveCategory() {
        if (action == null)
            return InterruptCategory.NORMAL;
        return categoryOverride != null ? categoryOverride : action.interruptCategory();
    }
}
