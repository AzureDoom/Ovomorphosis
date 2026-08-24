package mod.azure.ovomorphosis.ai.actions;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import mod.azure.ovomorphosis.ai.core.*;
import mod.azure.ovomorphosis.ai.nav.MovementUtils;

public final class IdleAction<E extends Mob> implements Action<E> {

    private final int minDuration;

    private final int maxDuration;

    private final int priority;

    private int age;

    private int duration;

    public IdleAction(int minDuration, int maxDuration, int priority) {
        this.minDuration = minDuration;
        this.maxDuration = maxDuration;
        this.priority = priority;
    }

    @Override
    public void start(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        this.age = 0;
        this.duration = minDuration + mob.getRandom().nextInt(maxDuration - minDuration + 1);
        mob.setAggressive(false);
        cooldowns.set(AiKeys.PASSIVE_DECISION, 180);
    }

    @Override
    public ActionOutcome tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        if (mob.getHealth() <= 0) {
            return ActionOutcome.failed();
        }

        var target = blackboard.get(AiKeys.TARGET, LivingEntity.class);
        if (target != null && target.isAlive()) {
            return ActionOutcome.SUCCESS;
        }

        var dangerMove = MovementUtils.steerAwayFromDangerEntities(mob, Vec3.ZERO);

        if (dangerMove.lengthSqr() > 0.0001D) {
            var safe = MovementUtils.findSafeMovement(mob, dangerMove, new int[] { 0 });

            if (!safe.equals(Vec3.ZERO)) {
                mob.setDeltaMovement(safe.x, mob.getDeltaMovement().y, safe.z);
                mob.hasImpulse = true;
                return ActionOutcome.RUNNING;
            }
        }

        age++;

        if (age % 20 == 0) {
            mob.setYRot((float) (mob.getRandom().nextDouble() * 360.0));
        }

        return age >= duration ? ActionOutcome.SUCCESS : ActionOutcome.RUNNING;
    }

    @Override
    public void stop(E mob, Blackboard blackboard, Cooldowns cooldowns, ActionStatus reason) {
        cooldowns.set(AiKeys.PASSIVE_DECISION, 1);
    }

    @Override
    public boolean isInterruptible() {
        return true;
    }

    @Override
    public int priority() {
        return priority;
    }
}
