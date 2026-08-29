package mod.azure.ovomorphosis.ai.actions;

import com.azure.azurecortex.action.combat.MeleeHitResolver;
import com.azure.azurecortex.api.action.Action;
import com.azure.azurecortex.api.action.ActionOutcome;
import com.azure.azurecortex.api.action.ActionStatus;
import com.azure.azurecortex.api.blackboard.Blackboard;
import com.azure.azurecortex.api.blackboard.CommonBlackboardKeys;
import com.azure.azurecortex.goap.PlanFailureReason;
import com.azure.azurecortex.navigation.crawl.CrawlController;
import com.azure.azurecortex.navigation.movement.MovementController;
import com.azure.azurecortex.runtime.CooldownTracker;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

public final class TimedAttackAction<E extends Mob, G> implements Action<E, G> {

    private final String cooldownKey;

    private final int cooldownTicks;

    private final int totalTicks;

    private final int damageTick;

    private final int priority;

    private final Consumer<E> animationTrigger;

    private int age;

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
    public void start(E mob, Blackboard blackboard, CooldownTracker cooldowns) {
        cooldowns.set(CommonBlackboardKeys.PASSIVE_DECISION, 1);
        this.age = 0;
        this.wasCrawlingOnStart = CrawlController.wasRecentlyWallCrawling(mob);
        mob.hasImpulse = true;
        animationTrigger.accept(mob);

        if (wasCrawlingOnStart) {
            CrawlController.setWallCrawling(mob, true);
            CrawlController.updateWallCrawlingPhysics(mob);
        }
    }

    @Override
    public ActionOutcome<G> tick(E mob, Blackboard blackboard, CooldownTracker cooldowns) {
        age++;

        if (mob.getHealth() <= 0) {
            mob.setAggressive(false);
            return ActionOutcome.failed();
        }

        var target = blackboard.get(CommonBlackboardKeys.TARGET);
        if (target == null || !target.isAlive()) {
            return ActionOutcome.failed(PlanFailureReason.FAILED_TARGET_LOST);
        }

        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

        if (wasCrawlingOnStart) {
            CrawlController.setWallCrawling(mob, true);
            CrawlController.updateWallCrawlingPhysics(mob);
        }

        var dangerMove = MovementController.steerAwayFromDangerEntities(mob, Vec3.ZERO);

        if (dangerMove.lengthSqr() > 0.0001D) {
            var safe = MovementController.findSafeMovement(mob, dangerMove, new int[] { 0 });

            if (!safe.equals(Vec3.ZERO)) {
                mob.setDeltaMovement(safe.x, mob.getDeltaMovement().y, safe.z);
            } else {
                mob.setDeltaMovement(dangerMove.x, mob.getDeltaMovement().y, dangerMove.z);
            }

        } else {
            mob.setDeltaMovement(0.0D, mob.getDeltaMovement().y, 0.0D);
        }
        mob.hasImpulse = true;

        if (age == damageTick) {
            if (MeleeHitResolver.tryStrike(mob, target, 2.5D)) {
                if (!target.isAlive()) {
                    blackboard.set(CommonBlackboardKeys.TARGET, null);
                    mob.setTarget(null);
                    mob.setAggressive(false);
                    cooldowns.set(cooldownKey, cooldownTicks);
                    return ActionOutcome.success();
                }
            }
        }

        if (age >= totalTicks) {
            cooldowns.set(cooldownKey, cooldownTicks);
            mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
            return ActionOutcome.success();
        }

        return ActionOutcome.running();
    }

    @Override
    public void stop(E mob, Blackboard blackboard, CooldownTracker cooldowns, ActionStatus reason) {
        wasCrawlingOnStart = false;
    }

    @Override
    public boolean isInterruptible() {
        return false;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public String debugName() {
        return cooldownKey;
    }
}
