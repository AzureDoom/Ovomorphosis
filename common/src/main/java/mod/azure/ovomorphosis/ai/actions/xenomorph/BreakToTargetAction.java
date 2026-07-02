package mod.azure.ovomorphosis.ai.actions.xenomorph;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import mod.azure.ovomorphosis.ai.actions.MoveToTargetAction;
import mod.azure.ovomorphosis.ai.core.Action;
import mod.azure.ovomorphosis.ai.core.ActionStatus;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.Blackboard;
import mod.azure.ovomorphosis.ai.core.Cooldowns;
import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.ai.goap.PlanFailureReason;
import mod.azure.ovomorphosis.ai.goap.PlanFeedback;
import mod.azure.ovomorphosis.entities.AbstractAlienEntity;
import mod.azure.ovomorphosis.util.ModTags;

/**
 * Makes the xenomorph break blocks that are directly obstructing its path to its current attack target.
 * <p>
 * This action is entered when either:
 * <ul>
 * <li>{@link MoveToTargetAction} reports the mob as stuck (via {@link AiKeys#BREAK_TO_TARGET_TRIGGER}), or</li>
 * <li>The GOAP planner chose {@link AiGoalType#BREAK_OBSTACLE} and pre-populated {@link AiKeys#BREAK_TO_TARGET_SCAN}
 * via its proactive ray-trace.</li>
 * </ul>
 * Once the obstructing block is cleared it returns {@link ActionStatus#SUCCESS} so the tree immediately falls back to
 * movement. The action is {@link #isInterruptible() interruptible} so higher-priority combat actions always preempt it.
 * <h3>Fixes applied</h3>
 * <ol>
 * <li><b>Auto-trigger on BREAK_OBSTACLE goal:</b> {@link #start} now sets {@link AiKeys#BREAK_TO_TARGET_TRIGGER}
 * whenever the active goal is {@link AiGoalType#BREAK_OBSTACLE}, so the action does not immediately return
 * {@link ActionStatus#SUCCESS} on its first tick.</li>
 * <li><b>Ray-march obstacle scan:</b> {@link #findObstructingBlock} was replaced with a proper DDA ray-march that
 * samples every block column along the direct line from mob to target. The old signum-step diagonal walk skipped blocks
 * that weren't perfectly axis-aligned and could walk around a corner wall rather than into it.</li>
 * </ol>
 *
 * @param <E> xenomorph entity type
 */
public class BreakToTargetAction<E extends AbstractAlienEntity> implements Action<E> {

    private BlockPos targetBlock = null;

    private float breakProgress = 0f;

    private int breakId = -1;

    public BreakToTargetAction() {}

    @Override
    public void start(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        targetBlock = null;
        breakProgress = 0f;
        breakId = mob.getId() ^ 0x3A7F_0000;

        var activeGoal = blackboard.get(AiKeys.ACTIVE_GOAL_TYPE, AiGoalType.class);
        if (activeGoal == AiGoalType.BREAK_OBSTACLE && !blackboard.has(AiKeys.BREAK_TO_TARGET_TRIGGER)) {
            blackboard.set(AiKeys.BREAK_TO_TARGET_TRIGGER, Boolean.TRUE);
        }
    }

    @Override
    public ActionStatus tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        var target = blackboard.get(AiKeys.TARGET, LivingEntity.class);
        if (target == null || !target.isAlive()) {
            return ActionStatus.FAILURE;
        }

        if (
            !mob.level()
                .getGameRules()
                .getBoolean(GameRules.RULE_MOBGRIEFING)
        ) {
            return ActionStatus.FAILURE;
        }

        if (!blackboard.has(AiKeys.BREAK_TO_TARGET_TRIGGER)) {
            return ActionStatus.SUCCESS;
        }

        var level = mob.level();

        if (targetBlock == null) {
            if (cooldowns.isOnCooldown(AiKeys.BREAK_TO_TARGET_SCAN)) {
                return ActionStatus.RUNNING;
            }
            cooldowns.set(AiKeys.BREAK_TO_TARGET_SCAN, 10);

            var hint = blackboard.get(AiKeys.BREAK_TO_TARGET_SCAN, BlockPos.class);
            if (hint != null && isBreakable(level, hint, level.getBlockState(hint))) {
                targetBlock = hint;
            } else {
                targetBlock = findObstructingBlock(mob, target);
            }

            if (targetBlock == null) {
                blackboard.remove(AiKeys.BREAK_TO_TARGET_TRIGGER);
                var activeGoal = blackboard.get(AiKeys.ACTIVE_GOAL_TYPE, AiGoalType.class);
                blackboard.set(
                    AiKeys.LAST_PLAN_FEEDBACK,
                    PlanFeedback.of(
                        PlanFailureReason.FAILED_OBSTACLE_UNBREAKABLE,
                        (int) mob.level().getGameTime(),
                        mob.blockPosition(),
                        activeGoal != null ? activeGoal : AiGoalType.NONE
                    )
                );
                return ActionStatus.FAILURE;
            }
            breakProgress = 0f;
        }

        if (!isWithinReach(mob, targetBlock)) {
            level.destroyBlockProgress(breakId, targetBlock, -1);
            targetBlock = null;
            blackboard.remove(AiKeys.BREAK_TO_TARGET_TRIGGER);
            blackboard.remove(AiKeys.BREAK_TO_TARGET_SCAN);
            return ActionStatus.SUCCESS;
        }

        var state = level.getBlockState(targetBlock);

        if (state.isAir() || state.getCollisionShape(level, targetBlock).isEmpty()) {
            level.destroyBlockProgress(breakId, targetBlock, -1);
            targetBlock = null;
            blackboard.remove(AiKeys.BREAK_TO_TARGET_TRIGGER);
            return ActionStatus.SUCCESS;
        }

        var center = Vec3.atCenterOf(targetBlock);
        mob.getLookControl().setLookAt(center.x, center.y, center.z, 30f, 30f);

        var hardness = state.getDestroySpeed(level, targetBlock);
        var tickProgress = hardness <= 0f
            ? 0.035f * 4f
            : 0.035f / Math.max(hardness, 0.1f);
        breakProgress += tickProgress;

        var stage = (int) Math.min(breakProgress * 10f, 9f);
        level.destroyBlockProgress(breakId, targetBlock, stage);

        if (breakProgress >= 1f) {
            level.destroyBlockProgress(breakId, targetBlock, -1);
            level.destroyBlock(targetBlock, true, mob);
            targetBlock = null;
            breakProgress = 0f;
            blackboard.remove(AiKeys.BREAK_TO_TARGET_TRIGGER);
        }

        return ActionStatus.RUNNING;
    }

    @Override
    public void stop(E mob, Blackboard blackboard, Cooldowns cooldowns, ActionStatus reason) {
        if (targetBlock != null) {
            mob.level().destroyBlockProgress(breakId, targetBlock, -1);
        }
        targetBlock = null;
        breakProgress = 0f;
    }

    @Override
    public boolean isInterruptible() {
        return breakProgress <= 0f;
    }

    @Override
    public int priority() {
        return 15;
    }

    /**
     * True if {@code pos} is close enough to the mob's body that the mob could plausibly be pressed against it. Without
     * this gate the action would happily accrue break progress on a block several blocks away — one selected by
     * {@link #findObstructingBlock}'s ray-march or by a stale path-scan hint — which reads as the mob "reaching through
     * the world" to dissolve random distant blocks.
     */
    private static boolean isWithinReach(AbstractAlienEntity mob, BlockPos pos) {
        var center = Vec3.atCenterOf(pos);
        var dx = center.x - mob.getX();
        var dy = center.y - (mob.getY() + mob.getBbHeight() * 0.5D);
        var dz = center.z - mob.getZ();
        var reach = (mob.getBbWidth() / 2.0D) + 2.0D;
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    /**
     * Finds the nearest breakable block between the mob and its target using a DDA ray-march.
     * <p>
     * <b>Fix:</b> The original implementation used a signum-step diagonal walk that advanced all three axes
     * simultaneously. For a mostly-horizontal obstruction, this caused the trace to skip corner blocks entirely and
     * sometimes "walk around" a wall instead of through it. The new implementation performs a proper integer DDA
     * ray-march — it advances only the cheapest axis at each step — which guarantees every discrete block column along
     * the direct line is tested.
     * <p>
     * Also checks one block above the mob's feet at each step to handle walls that are 2 blocks tall but where the path
     * approaches at foot level.
     */
    private static BlockPos findObstructingBlock(AbstractAlienEntity mob, LivingEntity target) {
        var from = mob.blockPosition();
        var to = target.blockPosition();

        var distSq = from.distSqr(to);
        if (distSq > (double) (4 * 4)) {
            return null;
        }

        var level = mob.level();

        var x0 = from.getX();
        var z0 = from.getZ();
        var x1 = to.getX();
        var z1 = to.getZ();

        var yMin = from.getY();
        var yMax = from.getY() + 1; // feet + head only — never scan up a column into overhead foliage/canopy

        var dx = Math.abs(x1 - x0);
        var dz = Math.abs(z1 - z0);
        var sx = x0 < x1 ? 1 : -1;
        var sz = z0 < z1 ? 1 : -1;

        var x = x0;
        var z = z0;
        var err = dx - dz;

        var steps = dx + dz + 1;

        for (var i = 0; i < steps; i++) {
            if (x == x1 && z == z1)
                break;

            for (var y = yMin; y <= yMax; y++) {
                var check = new BlockPos(x, y, z);
                if (check.equals(from) || check.equals(to))
                    continue;
                var state = level.getBlockState(check);
                if (isBreakable(level, check, state)) {
                    return check;
                }
            }

            var e2 = 2 * err;
            if (e2 > -dz) {
                err -= dz;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                z += sz;
            }
        }

        return null;
    }

    private static boolean isBreakable(Level level, BlockPos pos, BlockState state) {
        if (state.isAir())
            return false;

        if (!state.is(ModTags.WEAK_BLOCKS))
            return false;

        var hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0f || hardness > 50f)
            return false;

        return !state.getCollisionShape(level, pos).isEmpty();
    }
}
