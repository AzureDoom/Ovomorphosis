package mod.azure.ovomorphosis.ai.actions.facehugger;

import com.azure.azurecortex.api.action.Action;
import com.azure.azurecortex.api.action.ActionOutcome;
import com.azure.azurecortex.api.action.ActionStatus;
import com.azure.azurecortex.api.blackboard.Blackboard;
import com.azure.azurecortex.api.blackboard.CommonBlackboardKeys;
import com.azure.azurecortex.goap.PlanFailureReason;
import com.azure.azurecortex.runtime.CooldownTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.entities.facehugger.FacehuggerEntity;

public final class RetreatAndHideAction<G> implements Action<FacehuggerEntity, G> {

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
    public void start(FacehuggerEntity mob, Blackboard blackboard, CooldownTracker cooldowns) {
        phase = Phase.FLEEING;
        ticksHiding = 0;

        mob.setAggressive(false);
        mob.setTarget(null);

        blackboard.remove(CommonBlackboardKeys.TARGET);

        var dest = blackboard.get(CommonBlackboardKeys.GOAL_DESTINATION);
        if (dest != null) {
            navigateTo(blackboard, dest);
        }
    }

    @Override
    public ActionOutcome<G> tick(FacehuggerEntity mob, Blackboard blackboard, CooldownTracker cooldowns) {
        return switch (phase) {
            case FLEEING -> tickFleeing(mob, blackboard);
            case HIDING -> tickHiding(mob);
        };
    }

    private ActionOutcome<G> tickFleeing(FacehuggerEntity mob, Blackboard blackboard) {
        var dest = blackboard.get(CommonBlackboardKeys.GOAL_DESTINATION);
        if (dest == null) {
            blackboard.remove(CommonBlackboardKeys.DESTINATION);
            phase = Phase.HIDING;
            return ActionOutcome.running();
        }

        navigateTo(blackboard, dest);

        var distSq = mob.distanceToSqr(Vec3.atCenterOf(dest));
        if (distSq <= ARRIVED_DIST_SQ) {
            blackboard.remove(CommonBlackboardKeys.DESTINATION);
            phase = Phase.HIDING;
        }

        return ActionOutcome.running();
    }

    @SuppressWarnings("unchecked")
    private ActionOutcome<G> tickHiding(FacehuggerEntity mob) {
        ticksHiding++;

        if (ticksHiding > MAX_HIDE_TICKS) {
            return (ActionOutcome<G>) ActionOutcome.failed(
                PlanFailureReason.FAILED_STUCK,
                mob.blockPosition(),
                AiGoalType.RETREAT_AND_HIDE
            );
        }

        float healthFraction = mob.getHealth() / mob.getMaxHealth();
        if (healthFraction >= RECOVERY_HEALTH_FRACTION) {
            return ActionOutcome.success();
        }

        return ActionOutcome.running();
    }

    @Override
    public void stop(FacehuggerEntity mob, Blackboard blackboard, CooldownTracker cooldowns, ActionStatus reason) {
        blackboard.remove(CommonBlackboardKeys.GOAL_DESTINATION);

        if (reason == ActionStatus.FAILURE) {
            var failCount = blackboard.get(CommonBlackboardKeys.FAILED_GOAL_COUNT);
            blackboard.set(CommonBlackboardKeys.FAILED_GOAL_COUNT, failCount == null ? 1 : failCount + 1);
        } else if (reason == ActionStatus.SUCCESS) {
            blackboard.set(CommonBlackboardKeys.FAILED_GOAL_COUNT, 0);
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
        blackboard.set(CommonBlackboardKeys.DESTINATION, destination.immutable());
    }
}
