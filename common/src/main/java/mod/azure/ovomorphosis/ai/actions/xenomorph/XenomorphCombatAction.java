package mod.azure.ovomorphosis.ai.actions.xenomorph;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

import mod.azure.ovomorphosis.ai.core.*;
import mod.azure.ovomorphosis.ai.util.CrawlingManager;
import mod.azure.ovomorphosis.ai.util.MovementUtils;

public final class XenomorphCombatAction<E extends Mob> implements Action<E> {

    private enum Phase {
        APPROACH,
        SWIPE_1,
        STRAFE,
        SWIPE_2,
        RETREAT
    }

    private final String cooldownKey;

    private final int cooldownTicks;

    private final int priority;

    private final Consumer<E> swipeAnimation;

    private Phase phase = Phase.APPROACH;

    private int phaseAge = 0;

    private int strafeDir = 1;

    private boolean didDamageSwipe1 = false;

    private boolean didDamageSwipe2 = false;

    private boolean wasCrawlingOnStart = false;

    public XenomorphCombatAction(
        String cooldownKey,
        int cooldownTicks,
        int priority,
        Consumer<E> swipeAnimation
    ) {
        this.cooldownKey = cooldownKey;
        this.cooldownTicks = cooldownTicks;
        this.priority = priority;
        this.swipeAnimation = swipeAnimation;
    }

    @Override
    public void start(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        cooldowns.set(AiKeys.PASSIVE_DECISION, 1);
        phase = Phase.APPROACH;
        phaseAge = 0;
        didDamageSwipe1 = false;
        didDamageSwipe2 = false;
        strafeDir = mob.getRandom().nextBoolean() ? 1 : -1;
        wasCrawlingOnStart = CrawlingManager.wasRecentlyWallCrawling(mob);
        mob.hasImpulse = true;

        if (wasCrawlingOnStart) {
            CrawlingManager.setWallCrawling(mob, true);
            CrawlingManager.updateWallCrawlingPhysics(mob);
        }
    }

    @Override
    public ActionStatus tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        if (mob.getHealth() <= 0) {
            return ActionStatus.INTERRUPTED;
        }

        var target = blackboard.get(AiKeys.TARGET, LivingEntity.class);
        if (target == null || !target.isAlive()) {
            return ActionStatus.FAILURE;
        }

        maintainCrawl(mob);
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        phaseAge++;

        return switch (phase) {
            case APPROACH -> tickApproach(mob, target);
            case SWIPE_1 -> tickSwipe(mob, target, blackboard, cooldowns, true);
            case STRAFE -> tickStrafe(mob, target);
            case SWIPE_2 -> tickSwipe(mob, target, blackboard, cooldowns, false);
            case RETREAT -> tickRetreat(mob, cooldowns);
        };
    }

    @Override
    public void stop(E mob, Blackboard blackboard, Cooldowns cooldowns, ActionStatus reason) {
        wasCrawlingOnStart = false;
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

    private ActionStatus tickApproach(E mob, LivingEntity target) {
        var distSq = mob.distanceToSqr(target);

        if (distSq <= 2.2D * 2.2D || phaseAge > 60) {
            enterPhase(mob, Phase.SWIPE_1);
            return ActionStatus.RUNNING;
        }

        var toTarget = target.position().subtract(mob.position()).normalize().scale(0.52D);
        applyDangerSteering(mob, toTarget);
        return ActionStatus.RUNNING;
    }

    private ActionStatus tickSwipe(
        E mob,
        LivingEntity target,
        Blackboard blackboard,
        Cooldowns cooldowns,
        boolean isFirst
    ) {
        if (phaseAge == 1) {
            mob.setAggressive(true);
            swipeAnimation.accept(mob);
        }

        var alreadyDamaged = isFirst ? didDamageSwipe1 : didDamageSwipe2;
        if (!alreadyDamaged && phaseAge == 5) {
            if (
                mob.getBoundingBox().inflate(2.5D).intersects(target.getBoundingBox())
                    && hasMeleeLineOfSight(mob, target)
            ) {
                mob.doHurtTarget(target);
                if (isFirst)
                    didDamageSwipe1 = true;
                else
                    didDamageSwipe2 = true;

                if (!target.isAlive()) {
                    blackboard.set(AiKeys.TARGET, null);
                    mob.setTarget(null);
                    cooldowns.set(cooldownKey, cooldownTicks);
                    return ActionStatus.SUCCESS;
                }
            }
        }

        if (phaseAge >= 8) {
            if (isFirst) {
                enterPhase(mob, Phase.STRAFE);
            } else {
                enterPhase(mob, Phase.RETREAT);
            }
        }

        return ActionStatus.RUNNING;
    }

    private ActionStatus tickStrafe(E mob, LivingEntity target) {
        var toTarget = target.position().subtract(mob.position());
        var lateral = new Vec3(-toTarget.z, 0, toTarget.x).normalize();
        var strafe = lateral.scale(0.9D * strafeDir);

        applyDangerSteering(mob, strafe);
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

        if (mob.getRandom().nextFloat() < 0.05F) {
            strafeDir = -strafeDir;
        }

        if (phaseAge >= 18) {
            if (mob.distanceToSqr(target) <= (10.2D + 1.5D) * (10.2D + 1.5D)) {
                enterPhase(mob, Phase.SWIPE_2);
            } else {
                enterPhase(mob, Phase.RETREAT);
            }
        }

        return ActionStatus.RUNNING;
    }

    private ActionStatus tickRetreat(E mob, Cooldowns cooldowns) {
        var backward = MovementUtils.steerAwayFromDangerEntities(
            mob,
            mob.getLookAngle().scale(-0.48D)
        );
        final int[] steerBias = { 0 };
        var safe = MovementUtils.findSafeMovement(mob, backward, steerBias);
        mob.setDeltaMovement(safe.x, mob.getDeltaMovement().y, safe.z);
        mob.hasImpulse = true;

        if (phaseAge >= 10) {
            cooldowns.set(cooldownKey, cooldownTicks);
            mob.setAggressive(false);
            return ActionStatus.SUCCESS;
        }

        return ActionStatus.RUNNING;
    }

    private void enterPhase(E mob, Phase next) {
        phase = next;
        phaseAge = 0;
        mob.hasImpulse = true;
    }

    private void maintainCrawl(E mob) {
        if (wasCrawlingOnStart) {
            CrawlingManager.setWallCrawling(mob, true);
            CrawlingManager.updateWallCrawlingPhysics(mob);
        }
    }

    private void applyDangerSteering(E mob, Vec3 desired) {
        var danger = MovementUtils.steerAwayFromDangerEntities(mob, Vec3.ZERO);
        Vec3 final_;
        if (danger.lengthSqr() > 0.0001D) {
            var safe = MovementUtils.findSafeMovement(mob, danger, new int[] { 0 });
            final_ = safe.equals(Vec3.ZERO) ? danger : safe;
        } else {
            final_ = desired;
        }
        mob.setDeltaMovement(final_.x, mob.getDeltaMovement().y, final_.z);
        mob.hasImpulse = true;
    }

    private static boolean hasMeleeLineOfSight(Mob mob, LivingEntity target) {
        var level = mob.level();
        var hit = level.clip(
            new ClipContext(
                mob.getEyePosition(),
                target.getEyePosition(),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                mob
            )
        );
        return hit.getType() == HitResult.Type.MISS;
    }
}
