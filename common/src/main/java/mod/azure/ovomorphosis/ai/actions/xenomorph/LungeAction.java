package mod.azure.ovomorphosis.ai.actions.xenomorph;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

import mod.azure.ovomorphosis.ai.core.Action;
import mod.azure.ovomorphosis.ai.core.ActionStatus;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.Blackboard;
import mod.azure.ovomorphosis.ai.core.Cooldowns;
import mod.azure.ovomorphosis.ai.util.TargetingUtils;
import mod.azure.ovomorphosis.entities.xenomorph.XenomorphEntity;

public final class LungeAction<E extends XenomorphEntity> implements Action<E> {

    private enum Phase {
        WIND_UP,
        AIRBORNE,
        LAND
    }

    private Phase phase;

    private int phaseAge;

    private Vec3 lungeDir;

    private final int priority;

    private final Consumer<E> windUpAnimation;

    private final Consumer<E> lungeAnimation;

    public LungeAction(int priority, Consumer<E> windUpAnimation, Consumer<E> lungeAnimation) {
        this.priority = priority;
        this.windUpAnimation = windUpAnimation;
        this.lungeAnimation = lungeAnimation;
    }

    public static boolean canLunge(XenomorphEntity mob, LivingEntity target, Cooldowns cooldowns) {
        if (cooldowns.isOnCooldown(AiKeys.LUNGE_COOLDOWN))
            return false;
        var distSq = mob.distanceToSqr(target);
        return distSq >= 3.0 * 3.0 && distSq <= 14.0 * 14.0;
    }

    @Override
    public void start(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        cooldowns.set(AiKeys.PASSIVE_DECISION, 1);
        phase = Phase.WIND_UP;
        phaseAge = 0;
        lungeDir = null;
        mob.setAggressive(true);
        windUpAnimation.accept(mob);
    }

    @Override
    public ActionStatus tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        if (mob.getHealth() <= 0)
            return ActionStatus.INTERRUPTED;

        var target = blackboard.get(AiKeys.TARGET, LivingEntity.class);
        if (target == null || !target.isAlive()) {
            return ActionStatus.FAILURE;
        }

        phaseAge++;

        return switch (phase) {
            case WIND_UP -> tickWindUp(mob, target);
            case AIRBORNE -> tickAirborne(mob, target, cooldowns);
            case LAND -> tickLand(mob, target, blackboard, cooldowns);
        };
    }

    @Override
    public void stop(E mob, Blackboard blackboard, Cooldowns cooldowns, ActionStatus reason) {
        mob.setAggressive(false);
        lungeDir = null;
    }

    @Override
    public boolean isInterruptible() {
        return phase == Phase.WIND_UP;
    }

    @Override
    public int priority() {
        return priority;
    }

    private ActionStatus tickWindUp(E mob, LivingEntity target) {
        mob.setDeltaMovement(0, mob.getDeltaMovement().y, 0);
        mob.getLookControl().setLookAt(target, 30f, 30f);

        if (phaseAge >= 6) {
            var toTarget = target.position().subtract(mob.position());
            var horizontal = new Vec3(toTarget.x, 0, toTarget.z);
            lungeDir = horizontal.lengthSqr() > 0.0001D
                ? horizontal.normalize()
                : mob.getLookAngle();

            lungeAnimation.accept(mob);
            mob.setDeltaMovement(
                lungeDir.x * 1.05D,
                0.45D,
                lungeDir.z * 1.05D
            );
            mob.hasImpulse = true;

            phase = Phase.AIRBORNE;
            phaseAge = 0;
        }
        return ActionStatus.RUNNING;
    }

    private ActionStatus tickAirborne(E mob, LivingEntity target, Cooldowns cooldowns) {
        if (TargetingUtils.isInAttackRange(mob, target, 2.8D)) {
            phase = Phase.LAND;
            phaseAge = 0;
            return ActionStatus.RUNNING;
        }

        if (mob.onGround() && phaseAge > 2) {
            cooldowns.set(AiKeys.LUNGE_COOLDOWN, 80 / 2);
            return ActionStatus.SUCCESS;
        }

        if (phaseAge >= 30) {
            cooldowns.set(AiKeys.LUNGE_COOLDOWN, 80 / 2);
            return ActionStatus.SUCCESS;
        }

        return ActionStatus.RUNNING;
    }

    private ActionStatus tickLand(E mob, LivingEntity target, Blackboard blackboard, Cooldowns cooldowns) {
        if (phaseAge == 1) {
            if (
                TargetingUtils.isInAttackRange(mob, target, 2.8D)
                    && TargetingUtils.hasMeleeLineOfSight(mob, target)
            ) {
                mob.doHurtTarget(target);
                if (!target.isAlive()) {
                    blackboard.set(AiKeys.TARGET, null);
                    mob.setTarget(null);
                }
            }
        }

        cooldowns.set(AiKeys.LUNGE_COOLDOWN, 80);
        mob.setAggressive(false);
        return ActionStatus.SUCCESS;
    }
}
