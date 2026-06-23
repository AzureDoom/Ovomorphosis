package mod.azure.ovomorphosis.ai.actions.facehugger;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import mod.azure.ovomorphosis.ai.core.Action;
import mod.azure.ovomorphosis.ai.core.ActionStatus;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.Blackboard;
import mod.azure.ovomorphosis.ai.core.Cooldowns;
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
    public ActionStatus tick(FacehuggerEntity mob, Blackboard blackboard, Cooldowns cooldowns) {
        return switch (phase) {
            case FLEEING -> tickFleeing(mob, blackboard);
            case HIDING -> tickHiding(mob);
        };
    }

    private ActionStatus tickFleeing(FacehuggerEntity mob, Blackboard blackboard) {
        var dest = blackboard.get(AiKeys.GOAL_DESTINATION, BlockPos.class);
        if (dest == null) {
            blackboard.remove(AiKeys.DESTINATION);
            phase = Phase.HIDING;
            return ActionStatus.RUNNING;
        }

        navigateTo(blackboard, dest);

        double distSq = mob.distanceToSqr(Vec3.atCenterOf(dest));
        if (distSq <= ARRIVED_DIST_SQ) {
            blackboard.remove(AiKeys.DESTINATION);
            phase = Phase.HIDING;
        }

        return ActionStatus.RUNNING;
    }

    private ActionStatus tickHiding(FacehuggerEntity mob) {
        ticksHiding++;

        if (ticksHiding > MAX_HIDE_TICKS) {
            return ActionStatus.FAILURE;
        }

        float healthFraction = mob.getHealth() / mob.getMaxHealth();
        if (healthFraction >= RECOVERY_HEALTH_FRACTION) {
            return ActionStatus.SUCCESS;
        }

        return ActionStatus.RUNNING;
    }

    @Override
    public void stop(FacehuggerEntity mob, Blackboard blackboard, Cooldowns cooldowns, ActionStatus reason) {
        blackboard.remove(AiKeys.GOAL_DESTINATION);

        if (reason == ActionStatus.FAILURE) {
            var failCount = blackboard.get(AiKeys.FAILED_GOAL_COUNT, Integer.class);
            blackboard.set(AiKeys.FAILED_GOAL_COUNT, failCount == null ? 1 : failCount + 1);
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
