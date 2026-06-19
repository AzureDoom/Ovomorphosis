package mod.azure.xenogenesis.ai.core;

/**
 * A single node in the mob's behavior tree, evaluated every tick by {@link MobBrainRuntime}.
 * <p>
 * Nodes are composable: a node may be a leaf that wraps an {@link Action}, or an interior node (selector, sequence,
 * priority queue) that delegates to child nodes. The node returns a {@link BehaviorResult} that either carries the
 * winning action or indicates no action was chosen.
 *
 * @param <E> the mob type this node operates on
 */
@FunctionalInterface
public interface BehaviorNode<E> {

    /**
     * Evaluates this node for the given mob and returns the action the node wants to run.
     *
     * @param mob        the mob being evaluated
     * @param blackboard the mob's shared AI state store
     * @param cooldowns  the mob's cooldown tracker
     * @return a {@link BehaviorResult} containing the selected action, or {@link BehaviorResult#none()} if no action
     *         was chosen
     */
    BehaviorResult<E> tick(E mob, Blackboard blackboard, Cooldowns cooldowns);
}
