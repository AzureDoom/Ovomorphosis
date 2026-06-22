package mod.azure.ovomorphosis.ai.core;

import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

import mod.azure.ovomorphosis.ai.util.TargetingSystem;

/**
 * The central AI driver for a single mob.
 * <p>
 * Each tick, the runtime advances cooldowns, runs the {@link TargetingSystem}, ticks the currently active
 * {@link Action}, and then evaluates the {@link BehaviorNode} tree to potentially start a new (higher-priority) action.
 * Non-interruptible actions block the tree evaluation until they complete.
 *
 * @param <E> the mob type this brain controls
 */
public final class MobBrainRuntime<E extends Mob> {

    private final E mob;

    private final Blackboard blackboard = new Blackboard();

    private final Cooldowns cooldowns = new Cooldowns();

    private final TargetingSystem<E> targetingSystem;

    private final BehaviorNode<E> root;

    private Action<E> currentAction;

    /**
     * Creates a new brain runtime for {@code mob}.
     *
     * @param mob             the mob this brain controls
     * @param targetingSystem the system responsible for finding and updating the mob's target
     * @param root            the root behavior node evaluated each tick
     */
    public MobBrainRuntime(E mob, @Nullable TargetingSystem<E> targetingSystem, BehaviorNode<E> root) {
        this.mob = mob;
        this.targetingSystem = targetingSystem;
        this.root = root;
    }

    /**
     * Advances the brain by one game tick.
     * <p>
     * Order of operations:
     * <ol>
     * <li>Decrement all cooldowns.</li>
     * <li>Run the targeting system to refresh {@link AiKeys#TARGET}.</li>
     * <li>Tick the current action; stop it if it finished.</li>
     * <li>Evaluate the behavior tree; start a new action if one outranks the current.</li>
     * </ol>
     */
    public void tick() {
        cooldowns.tick();
        if (targetingSystem != null)
            targetingSystem.tick(mob, blackboard);

        if (currentAction != null) {
            var status = currentAction.tick(mob, blackboard, cooldowns);

            if (status == ActionStatus.RUNNING && !currentAction.isInterruptible()) {
                return;
            }

            if (status != ActionStatus.RUNNING) {
                currentAction.stop(mob, blackboard, cooldowns, status);
                currentAction = null;
            }
        }

        var result = root.tick(mob, blackboard, cooldowns);

        if (result.action() != null) {
            var shouldSwitch = currentAction == null
                || result.priority() > currentAction.priority();

            if (shouldSwitch) {
                if (currentAction != null) {
                    currentAction.stop(mob, blackboard, cooldowns, ActionStatus.INTERRUPTED);
                }
                currentAction = result.action();
                currentAction.start(mob, blackboard, cooldowns);
            }
        }
    }

    /**
     * Returns the {@link Blackboard} owned by this brain.
     *
     * @return the blackboard
     */
    public Blackboard getBlackboard() {
        return blackboard;
    }

    /**
     * Returns the {@link Cooldowns} tracker owned by this brain.
     *
     * @return the cooldowns tracker
     */
    public Cooldowns getCooldowns() {
        return cooldowns;
    }

    /**
     * Returns the {@link Action} that is currently executing, or {@code null} if no action is active.
     *
     * @return the current action, or {@code null}
     */
    public Action<E> getCurrentAction() {
        return currentAction;
    }
}
