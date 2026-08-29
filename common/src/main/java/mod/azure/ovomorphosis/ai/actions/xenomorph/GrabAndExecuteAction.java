package mod.azure.ovomorphosis.ai.actions.xenomorph;

import com.azure.azurecortex.api.action.Action;
import com.azure.azurecortex.api.action.ActionOutcome;
import com.azure.azurecortex.api.action.ActionStatus;
import com.azure.azurecortex.api.blackboard.Blackboard;
import com.azure.azurecortex.api.blackboard.CommonBlackboardKeys;
import com.azure.azurecortex.goap.PlanFailureReason;
import com.azure.azurecortex.runtime.CooldownTracker;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.util.TargetingUtils;
import mod.azure.ovomorphosis.entities.xenomorph.XenomorphEntity;

public final class GrabAndExecuteAction<E extends XenomorphEntity, G> implements Action<E, G> {

    private static final int HOLD_DURATION_TICKS = 200;

    private static final int KILL_TICK = 180;

    private final int priority;

    private final Consumer<E> animationCallback;

    private int holdTicks = 0;

    private boolean grabbed = false;

    private boolean killed = false;

    public GrabAndExecuteAction(int priority, Consumer<E> animationCallback) {
        this.priority = priority;
        this.animationCallback = animationCallback;
    }

    @Override
    public void start(E mob, Blackboard blackboard, CooldownTracker cooldowns) {
        cooldowns.set(CommonBlackboardKeys.PASSIVE_DECISION, 1);
        holdTicks = 0;
        grabbed = false;
        killed = false;
        mob.setAggressive(true);
        mob.setIsExecuting(true);
    }

    @Override
    public ActionOutcome<G> tick(E mob, Blackboard blackboard, CooldownTracker cooldowns) {
        if (mob.getHealth() <= 0) {
            return ActionOutcome.failed();
        }

        var target = blackboard.get(CommonBlackboardKeys.TARGET);
        if (target == null || !target.isAlive()) {
            return ActionOutcome.success();
        }

        if (!grabbed) {
            if (!TargetingUtils.isInAttackRange(mob, target, 1.5D)) {
                return ActionOutcome.failed(PlanFailureReason.FAILED_PRECONDITION);
            }

            if (!TargetingUtils.hasMeleeLineOfSight(mob, target)) {
                return ActionOutcome.failed(PlanFailureReason.FAILED_BLOCKED);
            }

            target.startRiding(mob, true);
            target.setSpeed(0.0f);
            grabbed = true;
        }

        holdTicks++;

        if (target.getVehicle() == mob) {
            target.setDeltaMovement(Vec3.ZERO);
        } else {
            return ActionOutcome.failed(PlanFailureReason.FAILED_PRECONDITION);
        }

        if (!killed && holdTicks >= KILL_TICK) {
            animationCallback.accept(mob);
            killed = true;
        }

        if (holdTicks >= HOLD_DURATION_TICKS) {
            target.stopRiding();
            target.kill();
            cooldowns.set(AiKeys.GRAB_COOLDOWN, 200);
            return ActionOutcome.success();
        }

        return ActionOutcome.running();
    }

    @Override
    public void stop(E mob, Blackboard blackboard, CooldownTracker cooldowns, ActionStatus reason) {
        holdTicks = 0;
        grabbed = false;
        killed = false;

        var target = blackboard.get(CommonBlackboardKeys.TARGET);
        if (target != null && target.getVehicle() == mob) {
            target.stopRiding();
        }
        mob.setIsExecuting(false);
    }

    @Override
    public boolean isInterruptible() {
        return !grabbed;
    }

    @Override
    public int priority() {
        return priority;
    }
}
