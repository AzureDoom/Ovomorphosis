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
    boolean success
) {

    /**
     * Returns an empty result indicating that no action was selected.
     *
     * @param <E> the mob type
     * @return a result with a {@code null} action and zero priority
     */
    public static <E> BehaviorResult<E> none() {
        return new BehaviorResult<>(null, 0, false);
    }

    /**
     * Returns a successful result that requests the given {@code action} be started.
     *
     * @param <E>      the mob type
     * @param action   the action to run
     * @param priority the priority of this action; higher values can preempt lower-priority actions
     * @return a result wrapping the action
     */
    public static <E> BehaviorResult<E> run(Action<E> action, int priority) {
        return new BehaviorResult<>(action, priority, true);
    }
}
