package mod.azure.ovomorphosis.ai.actions.xenomorph;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

import mod.azure.ovomorphosis.ai.core.*;
import mod.azure.ovomorphosis.ai.util.CrawlingMovementManager;
import mod.azure.ovomorphosis.ai.util.MovementUtils;
import mod.azure.ovomorphosis.ai.util.TargetingUtils;

public final class XenomorphCombatAction<E extends Mob> implements Action<E> {

    private enum Phase {
        STALK,
        STRIKE,
        CIRCLE_OUT,
        THREAT_RESPONSE
    }

    private final String cooldownKey;

    private final int cooldownTicks;

    private final int priority;

    private final Consumer<E> strikeAnimation;

    private Phase phase = Phase.STALK;

    private int phaseAge = 0;

    private boolean didStrike = false;

    private boolean wasCrawlingOnStart = false;

    private int stalkLateralBias = 1;

    private int circleDir = 1;

    public XenomorphCombatAction(
        String cooldownKey,
        int cooldownTicks,
        int priority,
        Consumer<E> strikeAnimation
    ) {
        this.cooldownKey = cooldownKey;
        this.cooldownTicks = cooldownTicks;
        this.priority = priority;
        this.strikeAnimation = strikeAnimation;
    }

    @Override
    public void start(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        cooldowns.set(AiKeys.PASSIVE_DECISION, 1);
        phase = Phase.STALK;
        phaseAge = 0;
        didStrike = false;
        stalkLateralBias = mob.getRandom().nextBoolean() ? 1 : -1;
        wasCrawlingOnStart = CrawlingMovementManager.wasRecentlyWallCrawling(mob);
        mob.hasImpulse = true;

        if (wasCrawlingOnStart) {
            CrawlingMovementManager.setWallCrawling(mob, true);
            CrawlingMovementManager.updateWallCrawlingPhysics(mob);
        }
    }

    @Override
    public ActionStatus tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        if (mob.getHealth() <= 0)
            return ActionStatus.INTERRUPTED;

        var target = blackboard.get(AiKeys.TARGET, LivingEntity.class);
        if (target == null || !target.isAlive())
            return ActionStatus.FAILURE;

        maintainCrawl(mob);
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        phaseAge++;

        return switch (phase) {
            case STALK -> tickStalk(mob, target);
            case STRIKE -> tickStrike(mob, target, blackboard, cooldowns);
            case CIRCLE_OUT -> tickCircleOut(mob, target, cooldowns, false);
            case THREAT_RESPONSE -> tickThreatResponse(mob, target, cooldowns);
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

    // TODO: Fix me for warning
    private ActionStatus tickStalk(E mob, LivingEntity target) {
        var distSq = mob.distanceToSqr(target);

        if (distSq <= 7D * 7D && isTargetFacingMob(target, mob)) {
            enterPhase(mob, Phase.THREAT_RESPONSE);
            return ActionStatus.RUNNING;
        }

        if (distSq <= 5D * 5D) {
            enterPhase(mob, Phase.STRIKE);
            return ActionStatus.RUNNING;
        }

        if (phaseAge > 140) {
            enterPhase(mob, Phase.STRIKE);
            return ActionStatus.RUNNING;
        }

        if (phaseAge % 14 == 0 && mob.getRandom().nextFloat() < 0.3F) {
            stalkLateralBias = -stalkLateralBias;
        }

        var toTarget = target.position().subtract(mob.position()).normalize();
        var lateral = new Vec3(-toTarget.z, 0, toTarget.x).scale(0.22D * stalkLateralBias);
        var desired = toTarget.scale(0.38D).add(lateral);
        applyDangerSteering(mob, desired);

        return ActionStatus.RUNNING;
    }

    private ActionStatus tickStrike(
        E mob,
        LivingEntity target,
        Blackboard blackboard,
        Cooldowns cooldowns
    ) {
        if (phaseAge == 1) {
            mob.setAggressive(true);
            strikeAnimation.accept(mob);
        }

        if (!didStrike && phaseAge == 5) {
            if (
                mob.getBoundingBox().inflate(2.8D).intersects(target.getBoundingBox())
                    && TargetingUtils.hasMeleeLineOfSight(mob, target)
            ) {
                mob.doHurtTarget(target);
                didStrike = true;

                if (!target.isAlive()) {
                    blackboard.set(AiKeys.TARGET, null);
                    mob.setTarget(null);
                    cooldowns.set(cooldownKey, cooldownTicks);
                    mob.setAggressive(false);
                    return ActionStatus.SUCCESS;
                }
            }
        }

        if (phaseAge >= 10) {
            circleDir = mob.getRandom().nextBoolean() ? 1 : -1;
            enterPhase(mob, Phase.CIRCLE_OUT);
        }

        return ActionStatus.RUNNING;
    }

    private ActionStatus tickCircleOut(E mob, LivingEntity target, Cooldowns cooldowns, boolean isThreatResponse) {
        mob.setAggressive(false);

        var toTarget = target.position().subtract(mob.position());
        var dist = toTarget.length();

        var lateral = new Vec3(-toTarget.z, 0, toTarget.x).normalize().scale(0.48D * circleDir);

        var radialError = dist - 5D;
        Vec3 movement;
        if (Math.abs(radialError) > 1.2D) {
            var radialDir = radialError < 0
                ? toTarget.normalize().scale(-1)
                : toTarget.normalize();
            var correction = radialDir.scale(Math.min(Math.abs(radialError) * 0.08D, 0.48D * 0.5D));
            movement = lateral.add(correction).normalize().scale(0.48D);
        } else {
            movement = lateral;
        }

        if (phaseAge % 20 == 0 && mob.getRandom().nextFloat() < 0.2F) {
            circleDir = -circleDir;
        }

        var safe = MovementUtils.findSafeMovement(mob, movement, new int[] { 0 });
        mob.setDeltaMovement(safe.x, mob.getDeltaMovement().y, safe.z);
        mob.hasImpulse = true;

        int duration = isThreatResponse ? 24 : 14;
        if (phaseAge >= duration) {
            if (isThreatResponse && mob.getRandom().nextFloat() < 0.30F) {
                cooldowns.set(cooldownKey, cooldownTicks + 40);
                return ActionStatus.FAILURE;
            }
            stalkLateralBias = circleDir;
            cooldowns.set(cooldownKey, cooldownTicks);
            return ActionStatus.SUCCESS;
        }

        return ActionStatus.RUNNING;
    }

    private ActionStatus tickThreatResponse(E mob, LivingEntity target, Cooldowns cooldowns) {
        if (phaseAge == 1) {
            mob.setAggressive(false);
            circleDir = mob.getRandom().nextBoolean() ? 1 : -1;
        }
        return tickCircleOut(mob, target, cooldowns, true);
    }

    private void enterPhase(E mob, Phase next) {
        phase = next;
        phaseAge = 0;
        mob.hasImpulse = true;
    }

    private void maintainCrawl(E mob) {
        if (wasCrawlingOnStart) {
            CrawlingMovementManager.setWallCrawling(mob, true);
            CrawlingMovementManager.updateWallCrawlingPhysics(mob);
        }
    }

    private static boolean isTargetFacingMob(LivingEntity target, Mob mob) {
        var toMob = mob.position().subtract(target.position()).normalize();
        return target.getLookAngle().dot(toMob) > 0.5D;
    }

    private void applyDangerSteering(E mob, Vec3 desired) {
        var danger = MovementUtils.steerAwayFromDangerEntities(mob, Vec3.ZERO);
        Vec3 result;
        if (danger.lengthSqr() > 0.0001D) {
            var safe = MovementUtils.findSafeMovement(mob, danger, new int[] { 0 });
            result = safe.equals(Vec3.ZERO) ? danger : safe;
        } else {
            result = desired;
        }
        mob.setDeltaMovement(result.x, mob.getDeltaMovement().y, result.z);
        mob.hasImpulse = true;
    }
}
