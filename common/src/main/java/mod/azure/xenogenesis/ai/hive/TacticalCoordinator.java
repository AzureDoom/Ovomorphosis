package mod.azure.xenogenesis.ai.hive;

/**
 * Strategy interface for assigning a {@link TacticalOrder} to a mob based on its squad's current state.
 * <p>
 * Implementations decide role allocation (frontline, flanker, etc.) and choose which target each mob should pursue.
 * Called once per mob per tick by the squad action that drives hive coordination.
 *
 * @param <E> the mob type being coordinated
 */
@FunctionalInterface
public interface TacticalCoordinator<E> {

    /**
     * Computes and returns the tactical order for {@code mob} given the shared {@code squad} state.
     *
     * @param mob   the mob requesting an order
     * @param squad the shared blackboard for the mob's squad
     * @return the order to follow; use {@link TacticalOrder#none()} when no order applies
     */
    TacticalOrder getOrder(E mob, SquadBlackboard squad);
}
