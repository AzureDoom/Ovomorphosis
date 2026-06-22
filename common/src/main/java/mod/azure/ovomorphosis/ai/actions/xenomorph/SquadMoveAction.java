package mod.azure.ovomorphosis.ai.actions.xenomorph;

import net.minecraft.world.entity.Mob;

import mod.azure.ovomorphosis.ai.core.*;
import mod.azure.ovomorphosis.ai.hive.TacticalOrder;

public final class SquadMoveAction<E extends Mob> implements Action<E> {

    private final double speedModifier;

    private final double arrivalRadiusSq;

    private final int timeoutTicks;

    private final int priority;

    private int age;

    public SquadMoveAction(double speedModifier, double arrivalRadius, int timeoutTicks, int priority) {
        this.speedModifier = speedModifier;
        this.arrivalRadiusSq = arrivalRadius * arrivalRadius;
        this.timeoutTicks = timeoutTicks;
        this.priority = priority;
    }

    @Override
    public void start(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        age = 0;
    }

    @Override
    public ActionStatus tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        age++;

        var order = blackboard.get(AiKeys.TACTICAL_ORDER, TacticalOrder.class);
        if (order == null || !order.hasDestination()) {
            return ActionStatus.FAILURE;
        }

        var dest = order.destination();
        var destCenter = dest.getCenter();

        if (mob.position().distanceToSqr(destCenter) <= arrivalRadiusSq) {
            return ActionStatus.SUCCESS;
        }

        if (age >= timeoutTicks) {
            return ActionStatus.SUCCESS;
        }

        mob.getNavigation().moveTo(destCenter.x, destCenter.y, destCenter.z, speedModifier);
        return ActionStatus.RUNNING;
    }

    @Override
    public void stop(E mob, Blackboard blackboard, Cooldowns cooldowns, ActionStatus reason) {}

    @Override
    public boolean isInterruptible() {
        return true;
    }

    @Override
    public int priority() {
        return priority;
    }
}
