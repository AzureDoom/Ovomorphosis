package mod.azure.ovomorphosis.ai.util;

import net.minecraft.world.entity.LivingEntity;

import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.Blackboard;

/**
 * Periodically evaluates a {@link TargetSelector} and writes the result to the {@link Blackboard} so that actions can
 * read it via {@link mod.azure.ovomorphosis.ai.core.AiKeys#TARGET}.
 * <p>
 * The selector is called every {@code retargetInterval} ticks, or immediately when the current target is no longer
 * alive. The last known position of a living target is always kept up to date under
 * {@link mod.azure.ovomorphosis.ai.core.AiKeys#LAST_KNOWN_TARGET_POS}.
 *
 * @param <E> the mob type this system serves
 */
public final class TargetingSystem<E> {

    private final TargetSelector<E> selector;

    private final int retargetInterval;

    private int age;

    /**
     * Creates a new targeting system.
     *
     * @param selector         the strategy used to find a target
     * @param retargetInterval number of ticks between forced re-evaluations of the target
     */
    public TargetingSystem(TargetSelector<E> selector, int retargetInterval) {
        this.selector = selector;
        this.retargetInterval = retargetInterval;
    }

    /**
     * Advances the targeting system by one tick.
     * <p>
     * Updates {@link mod.azure.ovomorphosis.ai.core.AiKeys#LAST_KNOWN_TARGET_POS} whenever the current target is alive,
     * then re-evaluates the target if the interval has elapsed or the current target has died.
     *
     * @param mob        the mob whose targeting is being managed
     * @param blackboard the mob's shared AI state store
     */
    public void tick(E mob, Blackboard blackboard) {
        age++;

        var current = blackboard.get(AiKeys.TARGET, LivingEntity.class);

        if (current != null && current.isAlive()) {
            blackboard.set(AiKeys.LAST_KNOWN_TARGET_POS, current.blockPosition());
        }

        if (age % retargetInterval != 0 && current != null && current.isAlive()) {
            return;
        }

        var target = selector.findTarget(mob, blackboard);

        if (target != null) {
            blackboard.set(AiKeys.TARGET, target);
        } else {
            blackboard.remove(AiKeys.TARGET);
        }
    }
}
