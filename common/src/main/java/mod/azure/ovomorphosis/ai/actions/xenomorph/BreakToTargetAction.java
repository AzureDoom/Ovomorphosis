package mod.azure.ovomorphosis.ai.actions.xenomorph;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import mod.azure.ovomorphosis.ai.actions.MoveToTargetAction;
import mod.azure.ovomorphosis.ai.core.Action;
import mod.azure.ovomorphosis.ai.core.ActionStatus;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.Blackboard;
import mod.azure.ovomorphosis.ai.core.Cooldowns;
import mod.azure.ovomorphosis.entities.xenomorph.XenomorphEntity;
import mod.azure.ovomorphosis.util.ModTags;

/**
 * Makes the xenomorph break blocks that are directly obstructing its path to its current attack target.
 * <p>
 * This action is only entered when {@link MoveToTargetAction} reports the mob as stuck (via
 * {@link AiKeys#BREAK_TO_TARGET_TRIGGER}). Once the obstructing block is cleared it returns
 * {@link ActionStatus#SUCCESS} so the tree immediately falls back to movement. The action is {@link #isInterruptible()
 * interruptible} so higher-priority combat actions always preempt it.
 *
 * @param <E> xenomorph entity type
 */
public class BreakToTargetAction<E extends XenomorphEntity> implements Action<E> {

    private BlockPos targetBlock = null;

    private float breakProgress = 0f;

    private int breakId = -1;

    public BreakToTargetAction() {}

    @Override
    public void start(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        targetBlock = null;
        breakProgress = 0f;
        breakId = mob.getId() ^ 0x3A7F_0000;
    }

    @Override
    public ActionStatus tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        var target = blackboard.get(AiKeys.TARGET, LivingEntity.class);
        if (target == null || !target.isAlive()) {
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

            targetBlock = findObstructingBlock(mob, target);
            if (targetBlock == null) {
                blackboard.remove(AiKeys.BREAK_TO_TARGET_TRIGGER);
                return ActionStatus.SUCCESS;
            }
            breakProgress = 0f;
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
        return true;
    }

    @Override
    public int priority() {
        return 15;
    }

    private static BlockPos findObstructingBlock(XenomorphEntity mob, LivingEntity target) {
        var from = mob.blockPosition();
        var to = target.blockPosition();

        var dist = from.distSqr(to);
        if (dist > (double) (12 * 12)) {
            return null;
        }

        var level = mob.level();

        var dx = Integer.signum(to.getX() - from.getX());
        var dy = Integer.signum(to.getY() - from.getY());
        var dz = Integer.signum(to.getZ() - from.getZ());

        var cursor = new BlockPos.MutableBlockPos(from.getX(), from.getY(), from.getZ());

        var steps = (int) Math.sqrt(dist) + 2;
        for (var i = 0; i < steps; i++) {
            if (dx != 0)
                cursor.setX(cursor.getX() + dx);
            if (dy != 0)
                cursor.setY(cursor.getY() + dy);
            if (dz != 0)
                cursor.setZ(cursor.getZ() + dz);

            if (cursor.equals(to))
                break;

            for (var ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    var check = cursor.offset(ox, 0, oz);
                    var state = level.getBlockState(check);
                    if (isBreakable(level, check, state)) {
                        return check.immutable();
                    }
                }
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
