package mod.azure.ovomorphosis.ai.actions.xenomorph;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

import mod.azure.ovomorphosis.ai.core.Action;
import mod.azure.ovomorphosis.ai.core.ActionStatus;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.Blackboard;
import mod.azure.ovomorphosis.ai.core.Cooldowns;
import mod.azure.ovomorphosis.ai.util.MovementUtils;

public final class DodgeProjectileAction<E extends Mob> implements Action<E> {

    private static final double SCAN_RADIUS = 8.0D;

    private static final double INTERCEPT_CONE_DOT = 0.72D;

    private static final double MIN_PROJECTILE_SPEED_SQ = 0.08D * 0.08D;

    private static final double DODGE_SPEED = 0.65D;

    private static final double DODGE_JUMP = 0.30D;

    private static final int DODGE_COOLDOWN_TICKS = 25;

    private final int priority;

    public DodgeProjectileAction(int priority) {
        this.priority = priority;
    }

    /**
     * Returns {@code true} if there is currently a projectile on an intercept course with {@code mob}. Called by the
     * behavior tree to avoid offering this action every tick when nothing is incoming — an unconditional offer causes a
     * start/fail loop at high priority that blocks all other actions.
     */
    public boolean hasIncomingProjectile(E mob) {
        return findIncomingProjectile(mob) != null;
    }

    @Override
    public void start(E mob, Blackboard blackboard, Cooldowns cooldowns) {}

    @Override
    public ActionStatus tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        if (mob.getHealth() <= 0)
            return ActionStatus.INTERRUPTED;

        if (cooldowns.isOnCooldown(AiKeys.DODGE_COOLDOWN))
            return ActionStatus.FAILURE;

        var incoming = findIncomingProjectile(mob);
        if (incoming == null)
            return ActionStatus.FAILURE;

        applyDodge(mob, incoming);
        cooldowns.set(AiKeys.DODGE_COOLDOWN, DODGE_COOLDOWN_TICKS);
        return ActionStatus.SUCCESS;
    }

    @Override
    public void stop(E mob, Blackboard blackboard, Cooldowns cooldowns, ActionStatus reason) {}

    @Override
    public boolean isInterruptible() {
        return true;
    }

    @Override
    public int priority() {
        return priority;
    }

    /**
     * Returns the nearest projectile that is on an intercept course with {@code mob}, or {@code null} if none found.
     */
    private static <E extends Mob> Projectile findIncomingProjectile(E mob) {
        var box = mob.getBoundingBox().inflate(SCAN_RADIUS);
        List<Projectile> projectiles = mob.level()
            .getEntitiesOfClass(Projectile.class, box, p -> p.isAlive() && p.getOwner() != mob);

        return projectiles.stream()
            .filter(p -> isOnInterceptCourse(mob, p))
            .min(Comparator.comparingDouble(mob::distanceToSqr))
            .orElse(null);
    }

    private static boolean isOnInterceptCourse(Mob mob, Projectile projectile) {
        var vel = projectile.getDeltaMovement();
        if (vel.lengthSqr() < MIN_PROJECTILE_SPEED_SQ)
            return false;

        var toMob = mob.getEyePosition().subtract(projectile.position()).normalize();
        return vel.normalize().dot(toMob) >= INTERCEPT_CONE_DOT;
    }

    private static <E extends Mob> void applyDodge(E mob, Projectile projectile) {
        var vel = projectile.getDeltaMovement().normalize();
        var perpRight = new Vec3(-vel.z, 0, vel.x);
        var sign = mob.getRandom().nextBoolean() ? 1.0D : -1.0D;
        var lateral = perpRight.scale(sign * DODGE_SPEED);

        var safe = MovementUtils.findSafeMovement(mob, lateral, new int[] { 0 });
        var impulse = safe.equals(Vec3.ZERO) ? lateral : safe;

        mob.setDeltaMovement(impulse.x, DODGE_JUMP, impulse.z);
        mob.hasImpulse = true;

        var yaw = (float) (Math.atan2(impulse.z, impulse.x) * (180.0 / Math.PI)) - 90.0F;
        mob.setYRot(yaw);
        mob.yBodyRot = yaw;
        mob.yHeadRot = yaw;
    }
}
