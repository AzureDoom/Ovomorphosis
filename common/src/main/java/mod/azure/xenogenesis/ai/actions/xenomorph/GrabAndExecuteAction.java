package mod.azure.xenogenesis.ai.actions.xenomorph;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

import mod.azure.xenogenesis.ai.core.*;
import mod.azure.xenogenesis.ai.util.TargetingUtils;
import mod.azure.xenogenesis.entities.xenomorph.XenomorphEntity;

public final class GrabAndExecuteAction<E extends XenomorphEntity> implements Action<E> {

    private static final int HOLD_DURATION_TICKS = 200;

    private static final int KILL_TICK = HOLD_DURATION_TICKS - 20;

    private static final double GRAB_REACH = 1.5D;

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
    public void start(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        holdTicks = 0;
        grabbed = false;
        killed = false;
        mob.setAggressive(true);
        mob.setIsExecuting(true);
    }

    @Override
    public ActionStatus tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        if (mob.getHealth() <= 0) {
            return ActionStatus.INTERRUPTED;
        }

        var target = blackboard.get(AiKeys.TARGET, LivingEntity.class);
        if (target == null || !target.isAlive()) {
            return ActionStatus.SUCCESS;
        }

        if (!grabbed) {
            if (!TargetingUtils.isInAttackRange(mob, target, GRAB_REACH)) {
                return ActionStatus.FAILURE;
            }

            target.startRiding(mob, true);
            target.setSpeed(0.0f);
            grabbed = true;
        }

        holdTicks++;

        if (target.getVehicle() == mob) {
            target.setDeltaMovement(Vec3.ZERO);
        } else {
            return ActionStatus.FAILURE;
        }

        if (!killed && holdTicks >= KILL_TICK) {
            animationCallback.accept(mob);
            killed = true;
        }

        if (holdTicks >= HOLD_DURATION_TICKS) {
            target.stopRiding();
            target.kill();
            cooldowns.set(AiKeys.GRAB_COOLDOWN, 200);
            return ActionStatus.SUCCESS;
        }

        return ActionStatus.RUNNING;
    }

    @Override
    public void stop(E mob, Blackboard blackboard, ActionStatus reason) {
        holdTicks = 0;
        grabbed = false;
        killed = false;

        var target = blackboard.get(AiKeys.TARGET, LivingEntity.class);
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
