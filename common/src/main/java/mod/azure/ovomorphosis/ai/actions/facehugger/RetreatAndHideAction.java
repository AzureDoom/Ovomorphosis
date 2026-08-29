package mod.azure.ovomorphosis.ai.actions.facehugger;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import mod.azure.ovomorphosis.ai.core.Action;
import mod.azure.ovomorphosis.ai.core.ActionOutcome;
import mod.azure.ovomorphosis.ai.core.ActionStatus;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.Blackboard;
import mod.azure.ovomorphosis.ai.core.Cooldowns;
import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.ai.goap.PlanFailureReason;
import mod.azure.ovomorphosis.entities.facehugger.FacehuggerEntity;

public final class RetreatAndHideAction implements Action<FacehuggerEntity> {

    private static final double ARRIVED_DIST_SQ = 2.0 * 2.0;

    private static final float RECOVERY_HEALTH_FRACTION = 0.60f;

    private static final int MAX_HIDE_TICKS = 400;

    private enum Phase {
        FLEEING,
        HIDING
    }

    private Phase phase;

    private int ticksHiding;

    @Override
    public void start(FacehuggerEntity mob, Blackboard blackboard, Cooldowns cooldowns) {
        phase = Phase.FLEEING;
        ticksHiding = 0;

        mob.setAggressive(false);
        mob.setTarget(null);

        blackboard.remove(AiKeys.TARGET);

        var dest = blackboard.get(AiKeys.GOAL_DESTINATION, BlockPos.class);
        if (dest != null) {
            navigateTo(blackboard, dest);
        }
    }

    @Override
    public ActionOutcome tick(FacehuggerEntity mob, Blackboard blackboard, Cooldowns cooldowns) {
        return switch (phase) {
            case FLEEING -> tickFleeing(mob, blackboard);
            case HIDING -> tickHiding(mob);
        };
    }

    private ActionOutcome tickFleeing(FacehuggerEntity mob, Blackboard blackboard) {
        var dest = blackboard.get(AiKeys.GOAL_DESTINATION, BlockPos.class);
        if (dest == null) {
            blackboard.remove(AiKeys.DESTINATION);
            phase = Phase.HIDING;
            return ActionOutcome.RUNNING;
        }

        navigateTo(blackboard, dest);

        var distSq = mob.distanceToSqr(Vec3.atCenterOf(dest));
        if (distSq <= ARRIVED_DIST_SQ) {
            blackboard.remove(AiKeys.DESTINATION);
            phase = Phase.HIDING;
        }

        return ActionOutcome.RUNNING;
    }

    private ActionOutcome tickHiding(FacehuggerEntity mob) {
        ticksHiding++;

        if (ticksHiding > MAX_HIDE_TICKS) {
            return ActionOutcome.failed(
                PlanFailureReason.FAILED_STUCK,
                mob.blockPosition(),
                AiGoalType.RETREAT_AND_HIDE
            );
        }

        float healthFraction = mob.getHealth() / mob.getMaxHealth();
        if (healthFraction >= RECOVERY_HEALTH_FRACTION) {
            return ActionOutcome.SUCCESS;
        }

        return ActionOutcome.RUNNING;
    }

    @Override
    public void stop(FacehuggerEntity mob, Blackboard blackboard, Cooldowns cooldowns, ActionStatus reason) {
        blackboard.remove(AiKeys.GOAL_DESTINATION);

        if (reason == ActionStatus.FAILURE) {
            var failCount = blackboard.get(AiKeys.FAILED_GOAL_COUNT, Integer.class);
            blackboard.set(AiKeys.FAILED_GOAL_COUNT, failCount == null ? 1 : failCount + 1);
        } else if (reason == ActionStatus.SUCCESS) {
            blackboard.set(AiKeys.FAILED_GOAL_COUNT, 0);
        }
    }

    @Override
    public boolean isInterruptible() {
        return phase == Phase.HIDING;
    }

    @Override
    public int priority() {
        return 28;
    }

    private static void navigateTo(Blackboard blackboard, BlockPos destination) {
        blackboard.set(AiKeys.DESTINATION, destination.immutable());
    }
}
