package mod.azure.ovomorphosis.ai.actions.facehugger;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.ai.core.*;
import mod.azure.ovomorphosis.ai.util.CrawlingMovementManager;
import mod.azure.ovomorphosis.ai.util.TargetingUtils;
import mod.azure.ovomorphosis.entities.facehugger.FacehuggerEntity;
import mod.azure.ovomorphosis.infection.InfectionManager;

public final class LeapAndAttachAction<E extends FacehuggerEntity> implements Action<E> {

    private static final int WIND_UP_TICKS = 20;

    private int attachedTicks = 0;

    private int leapCooldown = 0;

    private int windUpTicks = -1;

    private boolean inAir = false;

    public LeapAndAttachAction() {}

    @Override
    public void start(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        cooldowns.set(AiKeys.PASSIVE_DECISION, 1);
        attachedTicks = 0;
        leapCooldown = 0;
        windUpTicks = -1;
        inAir = false;
        mob.setAggressive(true);
        CrawlingMovementManager.setWallCrawling(mob, false);
        mob.setNoGravity(false);
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

        if (!TargetingUtils.faceHuggerTest(mob, target)) {
            blackboard.set(AiKeys.TARGET, null);
            return ActionStatus.FAILURE;
        }

        if (CrawlingMovementManager.isWallCrawling(mob)) {
            CrawlingMovementManager.setWallCrawling(mob, false);
            mob.setNoGravity(false);
        }

        if (
            mob.distanceToSqr(target) <= 0.15D * 0.15D
                || mob.getBoundingBox().inflate(mob.isInWater() ? 0.3D : 0.05D).intersects(target.getBoundingBox())
        ) {
            if (target.isBlocking()) {
                var knockback = mob.position().subtract(target.position()).normalize().scale(0.4D);
                mob.setDeltaMovement(knockback.x, 0.3D, knockback.z);
                mob.hasImpulse = true;
                windUpTicks = -1;
                inAir = false;
                leapCooldown = 30;
                mob.playSound(SoundEvents.SHIELD_BLOCK, 1.0F, 1.0F);
                return ActionStatus.RUNNING;
            }
            if (!TargetingUtils.faceHuggerTest(mob, target)) {
                blackboard.set(AiKeys.TARGET, null);
                return ActionStatus.FAILURE;
            }
            mob.grabTarget(target);
            attachedTicks = 0;
            inAir = false;

            if (mob.level() instanceof ServerLevel) {
                InfectionManager.infect(target, target.getRandom().nextInt());
            }

            return ActionStatus.RUNNING;
        }

        if (inAir && mob.onGround()) {
            inAir = false;
            windUpTicks = -1;
            leapCooldown = 10;
            blackboard.set(AiKeys.TARGET, target);
            return mob.distanceToSqr(target) <= 1.5D * 1.5D
                ? ActionStatus.RUNNING
                : ActionStatus.INTERRUPTED;
        }

        if (leapCooldown > 0) {
            leapCooldown--;
        }

        var distSqr = mob.distanceToSqr(target);

        if (windUpTicks >= 0 && distSqr > 4.0D * 4.0D) {
            windUpTicks = -1;
            blackboard.set(AiKeys.TARGET, target);
            return ActionStatus.INTERRUPTED;
        }

        if (mob.isInWater() && distSqr <= 4.0D * 4.0D) {
            var toTarget = target.position().subtract(mob.position());
            if (toTarget.lengthSqr() > 0.001D) {
                var movement = toTarget.normalize().scale(0.25D);
                mob.setDeltaMovement(movement);
                mob.hasImpulse = true;
            }
            return ActionStatus.RUNNING;
        }

        if (leapCooldown <= 0 && mob.onGround() && distSqr <= 4.0D * 4.0D) {
            var toTarget = target.position().subtract(mob.position());
            var horizontal = new Vec3(toTarget.x, 0.0D, toTarget.z);

            if (horizontal.lengthSqr() > 0.0001D) {
                if (windUpTicks < 0) {
                    windUpTicks = 0;
                    mob.animationDispatcher.serverWindUp();
                    mob.setDeltaMovement(0.0D, mob.getDeltaMovement().y, 0.0D);
                    return ActionStatus.RUNNING;
                }

                windUpTicks++;
                mob.setDeltaMovement(0.0D, mob.getDeltaMovement().y, 0.0D);

                if (windUpTicks >= WIND_UP_TICKS) {
                    windUpTicks = -1;
                    inAir = true;
                    var leap = horizontal.normalize().scale(0.95D);
                    mob.setDeltaMovement(leap.x, 0.55D, leap.z);
                    mob.hasImpulse = true;
                    leapCooldown = 15;
                }

                return ActionStatus.RUNNING;
            }
        }

        return ActionStatus.RUNNING;
    }

    @Override
    public void stop(E mob, Blackboard blackboard, Cooldowns cooldowns, ActionStatus reason) {
        attachedTicks = 0;
        leapCooldown = 0;
        windUpTicks = -1;
        inAir = false;

        if (!mob.isAttachedToHost()) {
            mob.resetAnimationState();
        }
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
