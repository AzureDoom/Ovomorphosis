package mod.azure.ovomorphosis.ai.core;

/**
 * Represents a discrete, stateful behavior that a mob can perform over one or more ticks.
 * <p>
 * Actions are selected by {@link BehaviorNode}s and driven by {@link MobBrainRuntime}. The lifecycle is: {@link #start}
 * → repeated {@link #tick} calls → {@link #stop}.
 *
 * @param <E> the mob type this action operates on
 */
public interface Action<E> {

    /**
     * Called once when the action is first activated.
     *
     * @param mob        the mob executing this action
     * @param blackboard the mob's shared AI state store
     * @param cooldowns  the mob's cooldown tracker
     */
    void start(E mob, Blackboard blackboard, Cooldowns cooldowns);

    /**
     * Called every game tick while this action is active.
     *
     * @param mob        the mob executing this action
     * @param blackboard the mob's shared AI state store
     * @param cooldowns  the mob's cooldown tracker
     * @return {@link ActionStatus#RUNNING} to continue, {@link ActionStatus#SUCCESS} or {@link ActionStatus#FAILURE} to
     *         end the action
     */
    ActionStatus tick(E mob, Blackboard blackboard, Cooldowns cooldowns);

    /**
     * Called once when the action ends, either naturally or via interruption.
     *
     * @param mob        the mob that was executing this action
     * @param blackboard the mob's shared AI state store
     * @param cooldowns  the mob's cooldown tracker
     * @param reason     why the action stopped ({@code SUCCESS}, {@code FAILURE}, or {@code INTERRUPTED})
     */
    void stop(E mob, Blackboard blackboard, Cooldowns cooldowns, ActionStatus reason);

    /**
     * Returns {@code true} if a higher-priority action is allowed to preempt this one while it is still
     * {@link ActionStatus#RUNNING}.
     *
     * @return {@code true} if the action can be interrupted mid-execution
     */
    boolean isInterruptible();

    /**
     * Returns the numeric priority of this action. Higher values take precedence over lower ones when the brain
     * evaluates competing actions on the same tick.
     *
     * @return this action's priority
     */
    int priority();
}
