package mod.azure.ovomorphosis.ai.actions;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;

import mod.azure.ovomorphosis.ai.core.*;
import mod.azure.ovomorphosis.ai.goap.PlanFailureReason;
import mod.azure.ovomorphosis.ai.util.AiDebugUtils;
import mod.azure.ovomorphosis.ai.util.MovementUtils;

public final class ExplosiveFleeAction<E extends Mob> implements Action<E> {

    private static final double STUCK_THRESHOLD = 0.05D;

    private static final int STUCK_TICKS_THRESHOLD = 30;

    private final double speed;

    private final double detectionRadius;

    private final double safeDistanceSqr;

    private final int priority;

    private final int[] steerBias = { 0 };

    private int stuckTicks;

    private Vec3 lastPosition;

    public ExplosiveFleeAction(double speed, double detectionRadius, double safeDistance, int priority) {
        this.speed = speed;
        this.detectionRadius = detectionRadius;
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

        var explosive = nearestExplosive(mob, detectionRadius);
        if (explosive == null || mob.distanceToSqr(explosive) >= safeDistanceSqr) {
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

        var awayFromExplosive = mob.position().subtract(explosive.position());
        var horizontal = new Vec3(awayFromExplosive.x, 0.0D, awayFromExplosive.z);

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
        mob.setAggressive(false);

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
        return false;
    }

    /**
     * An imminent explosion is a life-threatening emergency: this action must be able to preempt a
     * {@link InterruptCategory#LOCKED} action (e.g. mid-{@code CarryToWebAction}) immediately rather than waiting for
     * it to finish or expire. Once running, it still resists everything except a higher-priority emergency (e.g. being
     * on fire from the blast).
     */
    @Override
    public InterruptCategory interruptCategory() {
        return InterruptCategory.EMERGENCY;
    }

    @Override
    public int priority() {
        return priority;
    }

    public boolean hasNearbyExplosive(E mob) {
        Entity explosive = nearestExplosive(mob, detectionRadius);
        return explosive != null;
    }

    /**
     * Static, allocation-free check usable from contexts that don't hold an {@link ExplosiveFleeAction} instance (e.g.
     * a cheap pre-planner emergency probe). Mirrors {@link #hasNearbyExplosive(Mob)} but takes an explicit radius
     * instead of the instance's configured {@link #detectionRadius}.
     *
     * @param mob    the mob to scan around
     * @param radius the detection radius
     * @return {@code true} if an active explosive threat is within {@code radius}
     */
    public static boolean hasNearbyExplosive(Mob mob, double radius) {
        return nearestExplosive(mob, radius) != null;
    }

    private static Entity nearestExplosive(Mob mob, double radius) {
        var searchBox = mob.getBoundingBox().inflate(radius);

        return mob.level()
            .getEntities(mob, searchBox, ExplosiveFleeAction::isActiveExplosive)
            .stream()
            .min(Comparator.comparingDouble(mob::distanceToSqr))
            .orElse(null);
    }

    private static boolean isActiveExplosive(Entity entity) {
        if (entity instanceof PrimedTnt) {
            return entity.isAlive();
        }

        if (entity instanceof Creeper creeper) {
            return creeper.isAlive() && (creeper.isIgnited() || creeper.getSwellDir() > 0);
        }

        return false;
    }

    private void slowDown(E mob) {
        mob.setDeltaMovement(
            mob.getDeltaMovement().x * 0.25D,
            mob.getDeltaMovement().y,
            mob.getDeltaMovement().z * 0.25D
        );
    }
}
