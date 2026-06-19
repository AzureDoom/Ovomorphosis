package mod.azure.xenogenesis.ai.actions;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.function.Consumer;

import mod.azure.xenogenesis.ai.core.*;

public final class CornerFightAction<E extends Mob> implements Action<E> {

    private static final double HIT_INFLATE = 1.8D;

    private final String corneredCooldownKey;

    private final int cooldownTicks;

    private final int totalTicks;

    private final int damageTick;

    private final int priority;

    private final Consumer<E> animationTrigger;

    private int age;

    public CornerFightAction(
        String corneredCooldownKey,
        int cooldownTicks,
        int totalTicks,
        int damageTick,
        int priority,
        Consumer<E> animationTrigger
    ) {
        this.corneredCooldownKey = corneredCooldownKey;
        this.cooldownTicks = cooldownTicks;
        this.totalTicks = totalTicks;
        this.damageTick = damageTick;
        this.priority = priority;
        this.animationTrigger = animationTrigger;
    }

    @Override
    public void start(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        this.age = 0;
        mob.setAggressive(true);
        mob.hasImpulse = true;
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

        var threat = blackboard.get(AiKeys.TARGET, LivingEntity.class);
        if (threat == null || !threat.isAlive()) {
            mob.setAggressive(false);
            return ActionStatus.FAILURE;
        }

        var dx = threat.getX() - mob.getX();
        var dz = threat.getZ() - mob.getZ();
        var yaw = (float) (Math.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        mob.setYRot(yaw);
        mob.yBodyRot = yaw;
        mob.yHeadRot = yaw;
        mob.getLookControl().setLookAt(threat, 30.0F, 30.0F);

        if (age == damageTick) {
            if (mob.getBoundingBox().inflate(HIT_INFLATE).intersects(threat.getBoundingBox())) {
                mob.doHurtTarget(threat);

                if (!threat.isAlive()) {
                    blackboard.set(AiKeys.TARGET, null);
                    mob.setTarget(null);
                    mob.setAggressive(false);
                    cooldowns.set(corneredCooldownKey, cooldownTicks);
                    return ActionStatus.SUCCESS;
                }
            }
        }

        if (age >= totalTicks) {
            cooldowns.set(corneredCooldownKey, cooldownTicks);
            return ActionStatus.SUCCESS;
        }

        return ActionStatus.RUNNING;
    }

    @Override
    public void stop(E mob, Blackboard blackboard, ActionStatus reason) {
        if (reason == ActionStatus.SUCCESS || reason == ActionStatus.FAILURE) {
            mob.setAggressive(false);
        }
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
