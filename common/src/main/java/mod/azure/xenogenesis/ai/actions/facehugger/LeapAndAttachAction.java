package mod.azure.xenogenesis.ai.actions.facehugger;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import mod.azure.xenogenesis.CommonMod;
import mod.azure.xenogenesis.ai.core.*;
import mod.azure.xenogenesis.ai.util.TargetingUtils;
import mod.azure.xenogenesis.entities.facehugger.FacehuggerEntity;
import mod.azure.xenogenesis.infection.InfectionManager;

public final class LeapAndAttachAction<E extends FacehuggerEntity> implements Action<E> {

    private int attachedTicks = 0;

    private int leapCooldown = 0;

    public LeapAndAttachAction() {}

    @Override
    public void start(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        attachedTicks = 0;
        leapCooldown = 0;
        mob.setAggressive(true);
    }

    @Override
    public ActionStatus tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        if (mob.getHealth() <= 0) {
            return ActionStatus.INTERRUPTED;
        }

        if (mob.isAttachedToHost()) {
            attachedTicks++;

            if (attachedTicks >= CommonMod.getConfig().entityConfigs.facehuggerConfigs.facehuggerAttachMaxTicks) {
                mob.stopRiding();
                mob.setIsInfertile(true);
                mob.kill();
                return ActionStatus.SUCCESS;
            }

            mob.setDeltaMovement(Vec3.ZERO);
            mob.hasImpulse = false;
            return ActionStatus.RUNNING;
        }

        var target = blackboard.get(AiKeys.TARGET, LivingEntity.class);
        if (target == null || !target.isAlive()) {
            return ActionStatus.FAILURE;
        }

        if (
            mob.distanceToSqr(target) <= 0.5D * 0.5D
                || mob.getBoundingBox().inflate(0.05D).intersects(target.getBoundingBox())
        ) {
            if (!TargetingUtils.faceHuggerTest(mob, target)) {
                blackboard.set(AiKeys.TARGET, null);
                mob.setTarget(null);
                return ActionStatus.FAILURE;
            }
            mob.grabTarget(target);
            attachedTicks = 0;

            if (mob.level() instanceof ServerLevel) {
                InfectionManager.infect(target, target.getRandom().nextInt());
            }

            return ActionStatus.RUNNING;
        }

        if (leapCooldown > 0) {
            leapCooldown--;
        }

        var distSqr = mob.distanceToSqr(target);

        if (leapCooldown <= 0 && mob.onGround() && distSqr <= 4.0D * 4.0D) {
            var toTarget = target.position().subtract(mob.position());
            var horizontal = new Vec3(toTarget.x, 0.0D, toTarget.z);

            if (horizontal.lengthSqr() > 0.0001D) {
                var leap = horizontal.normalize().scale(0.65D);
                mob.setDeltaMovement(leap.x, 0.55D, leap.z);
                mob.hasImpulse = true;
                leapCooldown = 15;
                return ActionStatus.RUNNING;
            }
        }

        return ActionStatus.RUNNING;
    }

    @Override
    public void stop(E mob, Blackboard blackboard, ActionStatus reason) {
        attachedTicks = 0;
        leapCooldown = 0;
    }

    @Override
    public boolean isInterruptible() {
        return true;
    }

    @Override
    public int priority() {
        return 30;
    }
}
