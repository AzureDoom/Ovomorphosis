package mod.azure.ovomorphosis.ai.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

import mod.azure.ovomorphosis.ai.core.Action;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.Blackboard;
import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.ai.goap.PlanFeedback;

/**
 * Builds a single-line, human-scannable diagnostic string summarizing what a mob's brain is currently doing, in the
 * form:
 *
 * <pre>
 * TARGET=Villager, PLAN=CAPTURE_HOST, ACTION=CARRY_TO_WEB, PATH=BLOCKED(GLASS@120,64,-30)
 * </pre>
 *
 * <h3>Why this exists</h3> Once a plan/blackboard/pathing chain has several layers (GOAP planner → behavior tree →
 * action → custom A*), a misbehaving mob can be failing at any one of them, and the failure often only shows up two or
 * three layers away from its actual cause (e.g. the mob "just stands there" because the planner picked
 * {@code CAPTURE_HOST}, the tree correctly started {@code CarryToWebAction}, and the pathfinder is quietly reporting
 * {@code FAILED_BLOCKED} against a glass pane nobody happened to notice). This puts all four pieces of state on one
 * line so that chain can be read at a glance instead of reconstructed by re-deriving each layer from separate logs.
 * <p>
 * This is a read-only formatter — it does not gate or influence any AI decision, only describes it. Call it from
 * wherever is convenient (a debug command, a periodic log tick, a HUD overlay); it is intentionally decoupled from any
 * particular delivery mechanism.
 */
public final class AiDiagnostics {

    private AiDiagnostics() {}

    /**
     * Builds the one-line diagnostic for {@code mob}.
     *
     * @param mob           the mob to describe
     * @param blackboard    the mob's blackboard
     * @param currentAction the action currently running on {@code mob}'s brain, or {@code null} if none
     * @return the formatted diagnostic line
     */
    public static String describe(Mob mob, Blackboard blackboard, @Nullable Action<?> currentAction) {
        return "TARGET=" + describeTarget(blackboard)
            + ", PLAN=" + describePlan(blackboard)
            + ", ACTION=" + describeAction(currentAction)
            + ", PATH=" + describePath(mob, blackboard);
    }

    private static String describeTarget(Blackboard blackboard) {
        var target = blackboard.get(AiKeys.TARGET, LivingEntity.class);
        if (target == null || !target.isAlive())
            return "NONE";
        return target.getType().getDescription().getString();
    }

    private static String describePlan(Blackboard blackboard) {
        var goalType = blackboard.get(AiKeys.ACTIVE_GOAL_TYPE, AiGoalType.class);
        return goalType != null ? goalType.name() : AiGoalType.NONE.name();
    }

    private static String describeAction(@Nullable Action<?> currentAction) {
        return currentAction != null ? currentAction.debugName() : "NONE";
    }

    private static String describePath(Mob mob, Blackboard blackboard) {
        var feedback = blackboard.get(AiKeys.LAST_PLAN_FEEDBACK, PlanFeedback.class);
        if (feedback == null || feedback.isNone())
            return "OK";

        var blockingPositions = feedback.blockingPositions();
        if (blockingPositions.isEmpty())
            return feedback.reason().name();

        var pos = blockingPositions.get(0);
        return feedback.reason().name() + "(" + describeBlock(mob, pos) + "@" + pos.getX() + "," + pos.getY() + ","
            + pos.getZ() + ")";
    }

    private static String describeBlock(Mob mob, BlockPos pos) {
        var block = mob.level().getBlockState(pos).getBlock();
        var key = BuiltInRegistries.BLOCK.getKey(block);
        return key.getPath().toUpperCase(java.util.Locale.ROOT);
    }
}
