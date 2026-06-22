package mod.azure.ovomorphosis.ai.util;

import net.minecraft.world.entity.LivingEntity;

import mod.azure.ovomorphosis.ai.core.Blackboard;

/**
 * Strategy interface for selecting the mob's current attack target.
 * <p>
 * Implementations are supplied to {@link TargetingSystem} and called periodically to refresh the target stored under
 * {@link mod.azure.ovomorphosis.ai.core.AiKeys#TARGET}.
 *
 * @param <E> the mob type performing the target search
 */
@FunctionalInterface
public interface TargetSelector<E> {

    /**
     * Returns the best target for {@code mob} given the current {@code blackboard} state, or {@code null} if no valid
     * target can be found.
     *
     * @param mob        the mob searching for a target
     * @param blackboard the mob's shared AI state store
     * @return the selected {@code LivingEntity}, or {@code null}
     */
    LivingEntity findTarget(E mob, Blackboard blackboard);
}
