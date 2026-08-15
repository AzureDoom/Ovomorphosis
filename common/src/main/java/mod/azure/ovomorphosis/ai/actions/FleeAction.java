package mod.azure.ovomorphosis.ai.actions;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import mod.azure.ovomorphosis.ai.core.*;
import mod.azure.ovomorphosis.ai.goap.PlanFailureReason;
import mod.azure.ovomorphosis.ai.util.AiDebugUtils;
import mod.azure.ovomorphosis.ai.util.MovementUtils;

public final class FleeAction<E extends Mob> implements Action<E> {

    private static final double STUCK_THRESHOLD = 0.05D;

    private static final int STUCK_TICKS_THRESHOLD = 30;

    private final double speed;

    private final double safeDistanceSqr;

    private final int priority;

    private final int[] steerBias = { 0 };

    private int stuckTicks;

    private Vec3 lastPosition;

    public FleeAction(double speed, double safeDistance, int priority) {
        this.speed = speed;
        this.safeDistanceSqr = safeDistance * safeDistance;
        this.priority = priority;
    }

    @Override
    public void start(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        cooldowns.set(AiKeys.PASSIVE_DECISION, 1);
        this.stuckTicks = 0;
        this.lastPosition = mob.position();
        mob.setAggressive(false);
        mob.getNavigation().stop();
    }

    @Override
    public ActionOutcome tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        if (mob.getHealth() <= 0) {
            return ActionOutcome.failed();
        }

        var threat = blackboard.get(AiKeys.TARGET, LivingEntity.class);

        if (threat == null || !threat.isAlive()) {
            slowDown(mob);
            return ActionOutcome.SUCCESS;
        }

        if (mob.distanceToSqr(threat) >= safeDistanceSqr) {
            slowDown(mob);
            return ActionOutcome.SUCCESS;
        }

        var current = mob.position();
        var displacement = current.distanceTo(lastPosition);
        lastPosition = current;

        if (displacement < STUCK_THRESHOLD) {
            stuckTicks++;
        } else {
            stuckTicks = Math.max(0, stuckTicks - 1);
        }

        if (stuckTicks >= STUCK_TICKS_THRESHOLD) {
            return ActionOutcome.failed(PlanFailureReason.FAILED_STUCK);
        }

        var awayFromThreat = mob.position().subtract(threat.position());
        var horizontal = new Vec3(awayFromThreat.x, 0.0D, awayFromThreat.z);

        if (horizontal.lengthSqr() < 0.0001D) {
            var angle = mob.getRandom().nextDouble() * Math.PI * 2.0D;
            horizontal = new Vec3(Math.cos(angle), 0.0D, Math.sin(angle));
        }

        var desired = horizontal.normalize().scale(speed);
        var safe = MovementUtils.findSafeMovement(mob, desired, steerBias);

        if (safe.equals(Vec3.ZERO)) {
            stuckTicks += 3;
            return ActionOutcome.RUNNING;
        }

        mob.setDeltaMovement(safe.x, mob.getDeltaMovement().y, safe.z);
        mob.hasImpulse = true;

        var yaw = (float) (Math.atan2(safe.z, safe.x) * (180.0D / Math.PI)) - 90.0F;
        mob.setYRot(yaw);
        mob.yBodyRot = yaw;
        mob.yHeadRot = yaw;

        var debugTarget = mob.position().add(safe.scale(6.0D));
        AiDebugUtils.sendParticlePath(
            mob,
            mob.position(),
            debugTarget
        );
        return ActionOutcome.RUNNING;
    }

    @Override
    public void stop(E mob, Blackboard blackboard, Cooldowns cooldowns, ActionStatus reason) {
        slowDown(mob);
    }

    @Override
    public boolean isInterruptible() {
        return true;
    }

    @Override
    public int priority() {
        return priority;
    }

    private void slowDown(E mob) {
        mob.setDeltaMovement(
            mob.getDeltaMovement().x * 0.25D,
            mob.getDeltaMovement().y,
            mob.getDeltaMovement().z * 0.25D
        );
    }
}
