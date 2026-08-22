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
     * @return an {@link ActionOutcome} describing what happened this tick: {@link ActionOutcome.Running} or
     *         {@link ActionOutcome.Blocked} to continue (the latter also surfacing a reason to GOAP without stopping),
     *         or {@link ActionOutcome.Success} / {@link ActionOutcome.Failed} to end the action
     */
    ActionOutcome tick(E mob, Blackboard blackboard, Cooldowns cooldowns);

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
     * Returns {@code true} if a higher-priority action is allowed to preempt this one while it is still running (i.e.
     * while {@link #tick} keeps returning {@link ActionOutcome.Running} or {@link ActionOutcome.Blocked}).
     * <p>
     * Kept for backward compatibility and as the basis of the default {@link #interruptCategory()}. New code that needs
     * finer-grained control (e.g. "resistant to normal preemption but not to emergencies") should override
     * {@link #interruptCategory()} instead of relying on this alone.
     *
     * @return {@code true} if the action can be interrupted mid-execution
     */
    boolean isInterruptible();

    /**
     * Returns this action's {@link InterruptCategory}, governing both how resistant it is to preemption while running
     * and, when it is a candidate returned by the behavior tree, what authority it has to preempt whatever is currently
     * running.
     * <p>
     * The default derives from {@link #isInterruptible()} for backward compatibility: {@code true} maps to
     * {@link InterruptCategory#NORMAL}, {@code false} maps to {@link InterruptCategory#LOCKED}. Actions representing
     * genuine emergencies (on fire, imminent explosion, critical health, ...) should override this to return
     * {@link InterruptCategory#EMERGENCY} so they are never trapped behind a {@link InterruptCategory#LOCKED} action
     * and so that, once running, they resist everything except a higher-priority emergency.
     *
     * @return this action's interrupt category
     */
    default InterruptCategory interruptCategory() {
        return isInterruptible() ? InterruptCategory.NORMAL : InterruptCategory.LOCKED;
    }

    /**
     * Returns the numeric priority of this action. Higher values take precedence over lower ones when the brain
     * evaluates competing actions on the same tick.
     *
     * @return this action's priority
     */
    int priority();

    /**
     * A short, stable name for this action used by diagnostics (see {@code AiDiagnostics}) and log output. Defaults to
     * the simple class name, which is fine for most actions; override when a class is reused generically for several
     * conceptually different attacks/behaviors (e.g. {@code TimedAttackAction} backing both a tail punish and, later, a
     * bite) and a more specific label would make diagnostic output actually useful.
     *
     * @return a short human-readable identifier for this action
     */
    default String debugName() {
        return getClass().getSimpleName();
    }
}
