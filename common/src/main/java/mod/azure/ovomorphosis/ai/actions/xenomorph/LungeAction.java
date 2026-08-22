package mod.azure.ovomorphosis.ai.actions.xenomorph;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

import mod.azure.ovomorphosis.ai.combat.MeleeHitResolver;
import mod.azure.ovomorphosis.ai.core.Action;
import mod.azure.ovomorphosis.ai.core.ActionOutcome;
import mod.azure.ovomorphosis.ai.core.ActionStatus;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.Blackboard;
import mod.azure.ovomorphosis.ai.core.Cooldowns;
import mod.azure.ovomorphosis.ai.goap.PlanFailureReason;
import mod.azure.ovomorphosis.ai.util.TargetingUtils;
import mod.azure.ovomorphosis.entities.AbstractAlienEntity;

public final class LungeAction<E extends AbstractAlienEntity> implements Action<E> {

    private enum Phase {
        WIND_UP,
        AIRBORNE,
        LAND
    }

    private static final double KITING_DOT_THRESHOLD = 0.25D;

    private static final double MIN_KITING_SPEED = 0.05D;

    private static final float NON_KITE_LUNGE_CHANCE = 0.25f;

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

    public static boolean canLunge(AbstractAlienEntity mob, LivingEntity target, Cooldowns cooldowns) {
        if (cooldowns.isOnCooldown(AiKeys.LUNGE_COOLDOWN))
            return false;

        var distSq = mob.distanceToSqr(target);
        if (distSq < 3.0 * 3.0 || distSq > 14.0 * 14.0)
            return false;

        if (isTargetKiting(mob, target))
            return true;

        return mob.getRandom().nextFloat() < NON_KITE_LUNGE_CHANCE;
    }

    /**
     * Returns {@code true} if {@code target} is actively moving away from {@code mob}.
     * <p>
     * Uses the dot product of the target's horizontal velocity against the direction from the mob to the target. A
     * positive dot product means the velocity vector points generally away from the mob — i.e., the target is
     * retreating or strafing away.
     */
    private static boolean isTargetKiting(AbstractAlienEntity mob, LivingEntity target) {
        var targetVel = target.getDeltaMovement();
        var horizontal = new Vec3(targetVel.x, 0, targetVel.z);

        if (horizontal.length() < MIN_KITING_SPEED)
            return false;

        var awayFromMob = target.position().subtract(mob.position());
        var awayHorizontal = new Vec3(awayFromMob.x, 0, awayFromMob.z);
        if (awayHorizontal.lengthSqr() < 0.0001D)
            return false;

        var dot = horizontal.normalize().dot(awayHorizontal.normalize());
        return dot > KITING_DOT_THRESHOLD;
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
    public ActionOutcome tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        if (mob.getHealth() <= 0)
            return ActionOutcome.failed();

        var target = blackboard.get(AiKeys.TARGET, LivingEntity.class);
        if (target == null || !target.isAlive()) {
            return ActionOutcome.failed(PlanFailureReason.FAILED_TARGET_LOST);
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

    private ActionOutcome tickWindUp(E mob, LivingEntity target) {
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
        return ActionOutcome.RUNNING;
    }

    private ActionOutcome tickAirborne(E mob, LivingEntity target, Cooldowns cooldowns) {
        if (TargetingUtils.isInAttackRange(mob, target, 2.8D)) {
            phase = Phase.LAND;
            phaseAge = 0;
            return ActionOutcome.RUNNING;
        }

        if (mob.onGround() && phaseAge > 2) {
            cooldowns.set(AiKeys.LUNGE_COOLDOWN, 80 / 2);
            return ActionOutcome.SUCCESS;
        }

        if (phaseAge >= 30) {
            cooldowns.set(AiKeys.LUNGE_COOLDOWN, 80 / 2);
            return ActionOutcome.SUCCESS;
        }

        return ActionOutcome.RUNNING;
    }

    private ActionOutcome tickLand(E mob, LivingEntity target, Blackboard blackboard, Cooldowns cooldowns) {
        if (phaseAge == 1) {
            if (MeleeHitResolver.tryStrike(mob, target, 2.8D)) {
                if (!target.isAlive()) {
                    blackboard.set(AiKeys.TARGET, null);
                    mob.setTarget(null);
                }
            }
        }

        cooldowns.set(AiKeys.LUNGE_COOLDOWN, 80);
        mob.setAggressive(false);
        return ActionOutcome.SUCCESS;
    }
}
