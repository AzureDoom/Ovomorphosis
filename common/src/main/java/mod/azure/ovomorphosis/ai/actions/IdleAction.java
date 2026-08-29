package mod.azure.ovomorphosis.ai.actions;

import com.azure.azurecortex.api.action.Action;
import com.azure.azurecortex.api.action.ActionOutcome;
import com.azure.azurecortex.api.action.ActionStatus;
import com.azure.azurecortex.api.blackboard.Blackboard;
import com.azure.azurecortex.api.blackboard.CommonBlackboardKeys;
import com.azure.azurecortex.navigation.movement.MovementController;
import com.azure.azurecortex.runtime.CooldownTracker;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

public final class IdleAction<E extends Mob, G> implements Action<E, G> {

    private final int minDuration;

    private final int maxDuration;

    private final int priority;

    private int age;

    private int duration;

    public IdleAction(int minDuration, int maxDuration, int priority) {
        this.minDuration = minDuration;
        this.maxDuration = maxDuration;
        this.priority = priority;
    }

    @Override
    public void start(E mob, Blackboard blackboard, CooldownTracker cooldowns) {
        this.age = 0;
        this.duration = minDuration + mob.getRandom().nextInt(maxDuration - minDuration + 1);
        mob.setAggressive(false);
        cooldowns.set(CommonBlackboardKeys.PASSIVE_DECISION, 180);
    }

    @Override
    public ActionOutcome<G> tick(E mob, Blackboard blackboard, CooldownTracker cooldowns) {
        if (mob.getHealth() <= 0) {
            return ActionOutcome.failed();
        }

        var target = blackboard.get(CommonBlackboardKeys.TARGET);
        if (target != null && target.isAlive()) {
            return ActionOutcome.success();
        }

        var dangerMove = MovementController.steerAwayFromDangerEntities(mob, Vec3.ZERO);

        if (dangerMove.lengthSqr() > 0.0001D) {
            var safe = MovementController.findSafeMovement(mob, dangerMove, new int[] { 0 });

            if (!safe.equals(Vec3.ZERO)) {
                mob.setDeltaMovement(safe.x, mob.getDeltaMovement().y, safe.z);
                mob.hasImpulse = true;
                return ActionOutcome.running();
            }
        }

        age++;

        if (age % 20 == 0) {
            mob.setYRot((float) (mob.getRandom().nextDouble() * 360.0));
        }

        return age >= duration ? ActionOutcome.success() : ActionOutcome.running();
    }

    @Override
    public void stop(E mob, Blackboard blackboard, CooldownTracker cooldowns, ActionStatus reason) {
        cooldowns.set(CommonBlackboardKeys.PASSIVE_DECISION, 1);
    }

    @Override
    public boolean isInterruptible() {
        return true;
    }

    @Override
    public int priority() {
        return priority;
    }
}
