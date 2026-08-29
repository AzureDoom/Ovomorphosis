package mod.azure.ovomorphosis.ai.actions.facehugger;

import com.azure.azurecortex.api.action.Action;
import com.azure.azurecortex.api.action.ActionOutcome;
import com.azure.azurecortex.api.action.ActionStatus;
import com.azure.azurecortex.api.blackboard.Blackboard;
import com.azure.azurecortex.api.blackboard.CommonBlackboardKeys;
import com.azure.azurecortex.goap.PlanFailureReason;
import com.azure.azurecortex.navigation.crawl.CrawlController;
import com.azure.azurecortex.runtime.CooldownTracker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.Vec3;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.ai.util.TargetingUtils;
import mod.azure.ovomorphosis.entities.facehugger.FacehuggerEntity;
import mod.azure.ovomorphosis.infection.InfectionManager;
import mod.azure.ovomorphosis.util.MobUtils;

public final class LeapAndAttachAction<E extends FacehuggerEntity, G> implements Action<E, G> {

    private static final int WIND_UP_TICKS = 20;

    private int attachedTicks = 0;

    private int leapCooldown = 0;

    private int windUpTicks = -1;

    private boolean inAir = false;

    public LeapAndAttachAction() {}

    @Override
    public void start(E mob, Blackboard blackboard, CooldownTracker cooldowns) {
        cooldowns.set(CommonBlackboardKeys.PASSIVE_DECISION, 1);
        attachedTicks = 0;
        leapCooldown = 0;
        windUpTicks = -1;
        inAir = false;
        mob.setAggressive(true);
        CrawlController.setWallCrawling(mob, false);
        mob.setNoGravity(false);
    }

    @Override
    public ActionOutcome<G> tick(E mob, Blackboard blackboard, CooldownTracker cooldowns) {
        if (mob.getHealth() <= 0) {
            return ActionOutcome.failed();
        }

        if (mob.isAttachedToHost()) {
            attachedTicks++;

            if (attachedTicks >= CommonMod.getConfig().entityConfigs.facehuggerConfigs.facehuggerAttachMaxTicks) {
                mob.stopRiding();
                mob.setIsInfertile(true);
                mob.kill();
                return ActionOutcome.success();
            }

            mob.setDeltaMovement(Vec3.ZERO);
            mob.hasImpulse = false;
            return ActionOutcome.running();
        }

        var target = blackboard.get(CommonBlackboardKeys.TARGET);
        if (target == null || !target.isAlive()) {
            return ActionOutcome.failed(PlanFailureReason.FAILED_TARGET_LOST);
        }

        if (!TargetingUtils.faceHuggerTest(mob, target)) {
            blackboard.set(CommonBlackboardKeys.TARGET, null);
            return ActionOutcome.failed(PlanFailureReason.FAILED_PRECONDITION);
        }

        if (CrawlController.isWallCrawling(mob)) {
            CrawlController.setWallCrawling(mob, false);
            mob.setNoGravity(false);
        }

        if (
            mob.distanceToSqr(target) <= 0.15D * 0.15D
                || mob.getBoundingBox().inflate(mob.isInWater() ? 0.3D : 0.05D).intersects(target.getBoundingBox())
        ) {
            if (target.isBlocking() || MobUtils.hasBlockingHelmet(target)) {
                var knockback = mob.position().subtract(target.position()).normalize().scale(0.4D);
                mob.setDeltaMovement(knockback.x, 0.3D, knockback.z);
                mob.hasImpulse = true;
                windUpTicks = -1;
                inAir = false;

                var helmetBlocked = MobUtils.hasBlockingHelmet(target);
                leapCooldown = helmetBlocked ? 40 : 30;
                mob.playSound(helmetBlocked ? SoundEvents.ITEM_BREAK : SoundEvents.SHIELD_BLOCK, 1.0F, 1.0F);

                if (helmetBlocked) {
                    MobUtils.punishBlockingHelmet(target);
                }

                return ActionOutcome.running();
            }
            if (!TargetingUtils.faceHuggerTest(mob, target)) {
                blackboard.set(CommonBlackboardKeys.TARGET, null);
                return ActionOutcome.failed(PlanFailureReason.FAILED_PRECONDITION);
            }
            mob.grabTarget(target);
            attachedTicks = 0;
            inAir = false;

            if (mob.level() instanceof ServerLevel) {
                InfectionManager.infect(target, target.getRandom().nextInt());
            }

            return ActionOutcome.running();
        }

        if (inAir && mob.onGround()) {
            inAir = false;
            windUpTicks = -1;
            leapCooldown = 10;
            blackboard.set(CommonBlackboardKeys.TARGET, target);
            return mob.distanceToSqr(target) <= 1.5D * 1.5D
                ? ActionOutcome.running()
                : ActionOutcome.failed(PlanFailureReason.FAILED_PRECONDITION);
        }

        if (leapCooldown > 0) {
            leapCooldown--;
        }

        var distSqr = mob.distanceToSqr(target);

        if (windUpTicks >= 0 && distSqr > 4.0D * 4.0D) {
            windUpTicks = -1;
            blackboard.set(CommonBlackboardKeys.TARGET, target);
            return ActionOutcome.failed(PlanFailureReason.FAILED_PRECONDITION);
        }

        if (mob.isInWater() && distSqr <= 4.0D * 4.0D) {
            var toTarget = target.position().subtract(mob.position());
            if (toTarget.lengthSqr() > 0.001D) {
                var movement = toTarget.normalize().scale(0.25D);
                mob.setDeltaMovement(movement);
                mob.hasImpulse = true;
            }
            return ActionOutcome.running();
        }

        if (leapCooldown <= 0 && mob.onGround() && distSqr <= 4.0D * 4.0D) {
            var toTarget = target.position().subtract(mob.position());
            var horizontal = new Vec3(toTarget.x, 0.0D, toTarget.z);

            if (horizontal.lengthSqr() > 0.0001D) {
                if (windUpTicks < 0) {
                    windUpTicks = 0;
                    mob.animationDispatcher.serverWindUp();
                    mob.setDeltaMovement(0.0D, mob.getDeltaMovement().y, 0.0D);
                    return ActionOutcome.running();
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

                return ActionOutcome.running();
            }
        }

        return ActionOutcome.failed(PlanFailureReason.FAILED_PRECONDITION);
    }

    @Override
    public void stop(E mob, Blackboard blackboard, CooldownTracker cooldowns, ActionStatus reason) {
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
