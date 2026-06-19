package mod.azure.xenogenesis.ai.actions;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

import mod.azure.xenogenesis.ai.core.*;
import mod.azure.xenogenesis.ai.util.CrawlingManager;
import mod.azure.xenogenesis.ai.util.MovementUtils;

public final class TimedAttackAction<E extends Mob> implements Action<E> {

    private static final double HIT_INFLATE = 2.5D;

    private static final int LUNGE_FORWARD_START_TICK = 4;

    private static final int LUNGE_FORWARD_END_TICK = 8;

    private static final int LUNGE_BACK_START_TICK = 9;

    private static final int LUNGE_BACK_END_TICK = 13;

    private static final double LUNGE_FORWARD_SPEED = 0.22D;

    private static final double LUNGE_BACK_SPEED = 0.16D;

    private final String cooldownKey;

    private final int cooldownTicks;

    private final int totalTicks;

    private final int damageTick;

    private final int priority;

    private final Consumer<E> animationTrigger;

    private int age;

    // Captured at start() before the previous action's stop() clears the crawl flag.
    // Used to maintain wall-crawling physics for the full duration of the attack.
    private boolean wasCrawlingOnStart;

    public TimedAttackAction(
        String cooldownKey,
        int cooldownTicks,
        int totalTicks,
        int damageTick,
        int priority,
        Consumer<E> animationTrigger
    ) {
        this.cooldownKey = cooldownKey;
        this.cooldownTicks = cooldownTicks;
        this.totalTicks = totalTicks;
        this.damageTick = damageTick;
        this.priority = priority;
        this.animationTrigger = animationTrigger;
    }

    @Override
    public void start(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        this.age = 0;
        // Capture BEFORE the previous action's stop() fires and clears isWallCrawling.
        // wasRecentlyWallCrawling checks both the flag and remaining grace ticks.
        this.wasCrawlingOnStart = CrawlingManager.wasRecentlyWallCrawling(mob);
        mob.hasImpulse = true;
        animationTrigger.accept(mob);

        if (wasCrawlingOnStart) {
            CrawlingManager.setWallCrawling(mob, true);
            CrawlingManager.updateWallCrawlingPhysics(mob);
        }
    }

    @Override
    public ActionStatus tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        age++;

        if (mob.getHealth() <= 0) {
            mob.setAggressive(false);
            return ActionStatus.INTERRUPTED;
        }

        var target = blackboard.get(AiKeys.TARGET, LivingEntity.class);
        if (target == null || !target.isAlive()) {
            return ActionStatus.FAILURE;
        }

        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

        // Keep crawling active for the full attack if we were crawling at start.
        // Don't re-check isWallCrawling here — it will be false because we're the
        // only action running and nothing is calling setWallCrawling(true) this tick.
        if (wasCrawlingOnStart) {
            CrawlingManager.setWallCrawling(mob, true);
            CrawlingManager.updateWallCrawlingPhysics(mob);
        }

        var dangerMove = MovementUtils.steerAwayFromDangerEntities(mob, Vec3.ZERO);

        if (dangerMove.lengthSqr() > 0.0001D) {
            var safe = MovementUtils.findSafeMovement(mob, dangerMove, new int[] { 0 });

            if (!safe.equals(Vec3.ZERO)) {
                mob.setDeltaMovement(safe.x, mob.getDeltaMovement().y, safe.z);
            } else {
                mob.setDeltaMovement(dangerMove.x, mob.getDeltaMovement().y, dangerMove.z);
            }
        } else {
            var lungeMove = getLungeMovement(mob, target);
            mob.setDeltaMovement(lungeMove.x, mob.getDeltaMovement().y, lungeMove.z);
        }
        mob.hasImpulse = true;

        if (age == damageTick) {
            if (mob.getBoundingBox().inflate(HIT_INFLATE).intersects(target.getBoundingBox())) {
                mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
                mob.doHurtTarget(target);

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
            mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
            return ActionStatus.SUCCESS;
        }

        return ActionStatus.RUNNING;
    }

    private Vec3 getLungeMovement(E mob, LivingEntity target) {
        var direction = target.position().subtract(mob.position());
        direction = new Vec3(direction.x, 0.0D, direction.z);

        if (direction.lengthSqr() < 0.0001D) {
            return Vec3.ZERO;
        }

        direction = direction.normalize();

        if (age >= LUNGE_FORWARD_START_TICK && age <= LUNGE_FORWARD_END_TICK) {
            return direction.scale(LUNGE_FORWARD_SPEED);
        }

        if (age >= LUNGE_BACK_START_TICK && age <= LUNGE_BACK_END_TICK) {
            return direction.scale(-LUNGE_BACK_SPEED);
        }

        return Vec3.ZERO;
    }

    @Override
    public void stop(E mob, Blackboard blackboard, ActionStatus reason) {
        wasCrawlingOnStart = false;
        // Only clear crawling if still set — the next action's start() may need
        // wasRecentlyWallCrawling to still return true from grace ticks.
        // CrawlToTargetAction will re-establish or clear it on its next tick.
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
