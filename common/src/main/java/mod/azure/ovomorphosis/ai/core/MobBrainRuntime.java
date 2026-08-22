package mod.azure.ovomorphosis.ai.core;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.ai.goap.PlanFailureReason;
import mod.azure.ovomorphosis.ai.goap.PlanFeedback;
import mod.azure.ovomorphosis.ai.util.HiveMemory;
import mod.azure.ovomorphosis.ai.util.TargetingSystem;
import mod.azure.ovomorphosis.entities.xenomorph.XenomorphEntity;

/**
 * The central AI driver for a single mob.
 * <p>
 * Each tick, the runtime advances cooldowns, runs the {@link TargetingSystem}, ticks the currently active
 * {@link Action}, and then evaluates the {@link BehaviorNode} tree to potentially start a new (higher-priority) action.
 * Non-interruptible actions block the tree evaluation until they complete.
 * <h3>Feedback is centralized here, not in individual actions</h3> Actions no longer write {@link PlanFeedback} to the
 * blackboard themselves. Instead, {@link Action#tick} returns an {@link ActionOutcome}, and this runtime is the single
 * place that translates a {@link ActionOutcome.Blocked} or {@link ActionOutcome.Failed} outcome into
 * {@link AiKeys#LAST_PLAN_FEEDBACK}. This means GOAP's feedback loop is a property of the runtime contract, not a
 * convention every action has to remember to follow.
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
     * <li>Tick the current action, translating its {@link ActionOutcome} into blackboard feedback and, for
     * {@link ActionOutcome.Success}/{@link ActionOutcome.Failed}, stopping it.</li>
     * <li>Evaluate the behavior tree; start a new action if one outranks the current.</li>
     * </ol>
     * <h3>Interrupt categories</h3> A running action's {@link InterruptCategory} governs how resistant it is to
     * preemption (see {@link #canPreempt}). Critically, even a {@link InterruptCategory#LOCKED} action does
     * <em>not</em> skip behavior-tree evaluation entirely — the tree is still consulted every tick so that an
     * {@link InterruptCategory#EMERGENCY} candidate (fire, imminent explosion, critical health, ...) can break through.
     * Only the actual <em>switch</em> is gated by {@link #canPreempt}; evaluating the tree itself is cheap, since
     * branches that aren't selected are never ticked.
     */
    public void tick() {
        if (mob.isNoAi())
            return;
        cooldowns.tick();

        var actionIsLocked = currentAction != null
            && currentAction.interruptCategory() != InterruptCategory.NORMAL;
        if (targetingSystem != null && !actionIsLocked)
            targetingSystem.tick(mob, blackboard);

        if (currentAction != null) {
            var stillActive = applyOutcome(currentAction.tick(mob, blackboard, cooldowns));

            if (stillActive) {
                if (currentAction.interruptCategory() == InterruptCategory.NORMAL) {
                    // Ordinary running/blocked action: fall through to the shared tree evaluation below so the
                    // usual priority-based preemption logic applies.
                } else {
                    // Resistant (LOCKED or EMERGENCY) action: still consult the tree so a genuine emergency can
                    // break through, but nothing else is allowed to touch it.
                    var candidate = root.tick(mob, blackboard, cooldowns);
                    if (canPreempt(currentAction, candidate)) {
                        currentAction.stop(mob, blackboard, cooldowns, ActionStatus.INTERRUPTED);
                        currentAction = candidate.action();
                        currentAction.start(mob, blackboard, cooldowns);
                    }
                    return;
                }
            }
            // If the action terminated (Success/Failed), applyOutcome already called stop() and cleared
            // currentAction, so we fall straight through to the shared tree evaluation below — same as the
            // historical "status != RUNNING" path.
        }

        if (mob instanceof XenomorphEntity xenomorph && cooldowns.ready(AiKeys.HIVE_SYNC_COOLDOWN)) {
            var memory = blackboard.get(AiKeys.HIVE_MEMORY, HiveMemory.class);
            if (memory != null) {
                memory.findNearestOwnedWebCross(xenomorph.level(), xenomorph.blockPosition(), 80D);
                cooldowns.set(AiKeys.HIVE_SYNC_COOLDOWN, 120);
            }
        }

        var result = root.tick(mob, blackboard, cooldowns);

        if (result.action() != null) {
            var shouldSwitch = currentAction == null || canPreempt(currentAction, result);

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
     * Translates a single {@link ActionOutcome} into blackboard feedback and, for terminal outcomes, stops
     * {@link #currentAction} and clears it.
     *
     * @param outcome the outcome {@link #currentAction}'s {@link Action#tick} just returned
     * @return {@code true} if the action is still active ({@link ActionOutcome.Running} or
     *         {@link ActionOutcome.Blocked}), {@code false} if it just terminated ({@link ActionOutcome.Success} or
     *         {@link ActionOutcome.Failed}, in which case {@link #currentAction} is now {@code null})
     */
    private boolean applyOutcome(ActionOutcome outcome) {
        return switch (outcome) {
            case ActionOutcome.Running ignored -> true;
            case ActionOutcome.Blocked blocked -> {
                // Still running — this is the "hit an obstacle but haven't given up" signal. The action keeps
                // executing; GOAP just gets an early, non-terminal heads-up.
                writePlanFeedback(blocked.reason(), blocked.at(), blocked.goalType(), blocked.blockingPositions());
                yield true;
            }
            case ActionOutcome.Success ignored -> {
                currentAction.stop(mob, blackboard, cooldowns, ActionStatus.SUCCESS);
                currentAction = null;
                yield false;
            }
            case ActionOutcome.Failed failed -> {
                writePlanFeedback(failed.reason(), failed.at(), failed.goalType(), failed.blockingPositions());
                currentAction.stop(mob, blackboard, cooldowns, ActionStatus.FAILURE);
                currentAction = null;
                yield false;
            }
        };
    }

    /**
     * Writes {@link AiKeys#LAST_PLAN_FEEDBACK} from a raw reason/position/goal-type/blocking-positions tuple,
     * defaulting the position to the mob's current block position when {@code at} is {@code null} and the goal-type
     * attribution to whatever {@link AiKeys#ACTIVE_GOAL_TYPE} currently is when {@code goalTypeOverride} is
     * {@code null}. A {@code null} reason or {@link PlanFailureReason#NONE} writes nothing, matching the historical
     * behavior of actions that failed without anything worth reporting.
     */
    private void writePlanFeedback(
        @Nullable PlanFailureReason reason,
        @Nullable BlockPos at,
        @Nullable AiGoalType goalTypeOverride,
        List<BlockPos> blockingPositions
    ) {
        if (reason == null || reason == PlanFailureReason.NONE)
            return;

        var goalType = goalTypeOverride != null
            ? goalTypeOverride
            : blackboard.get(AiKeys.ACTIVE_GOAL_TYPE, AiGoalType.class);

        blackboard.set(
            AiKeys.LAST_PLAN_FEEDBACK,
            PlanFeedback.of(
                reason,
                (int) mob.level().getGameTime(),
                at != null ? at : mob.blockPosition(),
                goalType != null ? goalType : AiGoalType.NONE,
                blockingPositions
            )
        );
    }

    /**
     * Decides whether {@code candidate} is allowed to preempt {@code current}, based on {@link InterruptCategory}
     * resolution rules (see {@link InterruptCategory}) layered on top of the historical priority comparison.
     *
     * @param current   the currently running action; must not be {@code null}
     * @param candidate the behavior-tree result being considered as a replacement
     * @return {@code true} if {@code candidate} should replace {@code current}
     */
    private boolean canPreempt(Action<E> current, BehaviorResult<E> candidate) {
        if (candidate.action() == null || candidate.action() == current)
            return false;

        var candidateCategory = candidate.effectiveCategory();

        return switch (current.interruptCategory()) {
            case LOCKED -> candidateCategory == InterruptCategory.EMERGENCY;
            case NORMAL -> candidateCategory == InterruptCategory.EMERGENCY
                || candidate.priority() > current.priority();
            case EMERGENCY -> candidateCategory == InterruptCategory.EMERGENCY
                && candidate.priority() > current.priority();
        };
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
