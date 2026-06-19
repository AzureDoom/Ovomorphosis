package mod.azure.xenogenesis.ai.actions;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.function.Consumer;

import mod.azure.xenogenesis.ai.core.*;

public final class MeleeCloseRangeAction<E extends Mob> implements Action<E> {

    private static final double HIT_INFLATE = 1.0D;

    private final String cooldownKey;

    private final int cooldownTicks;

    private final int totalTicks;

    private final int damageTick;

    private final double triggerDistanceSqr;

    private final float damageMultiplier;

    private final int priority;

    private final Consumer<E> animationTrigger;

    private int age;

    public MeleeCloseRangeAction(
        String cooldownKey,
        int cooldownTicks,
        int totalTicks,
        int damageTick,
        double triggerDistance,
        float damageMultiplier,
        int priority,
        Consumer<E> animationTrigger
    ) {
        this.cooldownKey = cooldownKey;
        this.cooldownTicks = cooldownTicks;
        this.totalTicks = totalTicks;
        this.damageTick = damageTick;
        this.triggerDistanceSqr = triggerDistance * triggerDistance;
        this.damageMultiplier = damageMultiplier;
        this.priority = priority;
        this.animationTrigger = animationTrigger;
    }

    @Override
    public void start(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        this.age = 0;
        mob.hasImpulse = true;
        mob.setAggressive(true);
        animationTrigger.accept(mob);
    }

    @Override
    public ActionStatus tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        age++;
        mob.setDeltaMovement(0.0D, mob.getDeltaMovement().y, 0.0D);
        mob.hasImpulse = true;

        if (mob.getHealth() <= 0) {
            mob.setAggressive(false);
            return ActionStatus.INTERRUPTED;
        }

        var target = blackboard.get(AiKeys.TARGET, LivingEntity.class);
        if (target == null || !target.isAlive()) {
            return ActionStatus.FAILURE;
        }

        var dx = target.getX() - mob.getX();
        var dz = target.getZ() - mob.getZ();
        var yaw = (float) (Math.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        mob.setYRot(yaw);
        mob.yBodyRot = yaw;
        mob.yHeadRot = yaw;
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

        if (age == damageTick) {
            if (
                mob.distanceToSqr(target) <= triggerDistanceSqr
                    && mob.getBoundingBox().inflate(HIT_INFLATE).intersects(target.getBoundingBox())
            ) {

                var baseDamage = (float) mob.getAttributeValue(
                    net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE
                );
                var boostedDamage = baseDamage * damageMultiplier;

                target.hurt(mob.damageSources().mobAttack(mob), boostedDamage);

                if (!target.isAlive()) {
                    blackboard.set(AiKeys.TARGET, null);
                    mob.setTarget(null);
                    mob.setAggressive(false);
                    cooldowns.set(cooldownKey, cooldownTicks);
                    return ActionStatus.SUCCESS;
                }
            }
        }

        if (age >= totalTicks) {
            cooldowns.set(cooldownKey, cooldownTicks);
            return ActionStatus.SUCCESS;
        }

        return ActionStatus.RUNNING;
    }

    @Override
    public void stop(E mob, Blackboard blackboard, ActionStatus reason) {
        mob.setAggressive(false);
    }

    @Override
    public boolean isInterruptible() {
        return false;
    }

    @Override
    public int priority() {
        return priority;
    }
}
