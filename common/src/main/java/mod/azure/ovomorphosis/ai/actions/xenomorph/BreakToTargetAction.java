package mod.azure.ovomorphosis.ai.actions.xenomorph;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import mod.azure.ovomorphosis.ai.actions.MoveToTargetAction;
import mod.azure.ovomorphosis.ai.core.Action;
import mod.azure.ovomorphosis.ai.core.ActionOutcome;
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
        // Fix: only auto-arm on a stale BREAK_OBSTACLE goal type if this specific commitment hasn't already been
        // resolved. ACTIVE_GOAL_TYPE stays BREAK_OBSTACLE for the planner's whole commit window (40-200 ticks)
        // regardless of whether this action already broke the one real obstruction (or gave up) — without this
        // guard, the tree would restart this action every tick for the rest of that window with nothing left to
        // do, and movement/combat actions would never get a turn. A genuinely fresh BREAK_TO_TARGET_TRIGGER (set by
        // MoveToTargetAction detecting a real, current obstruction — e.g. the second block of a 2-tall wall) still
        // arms normally regardless of this flag, since that check comes first.
        if (
            activeGoal == AiGoalType.BREAK_OBSTACLE
                && !blackboard.has(AiKeys.BREAK_TO_TARGET_TRIGGER)
                && !blackboard.has(AiKeys.BREAK_TO_TARGET_EXHAUSTED)
        ) {
            blackboard.set(AiKeys.BREAK_TO_TARGET_TRIGGER, Boolean.TRUE);
        }
    }

    @Override
    public ActionOutcome tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        var target = blackboard.get(AiKeys.TARGET, LivingEntity.class);
        if (target == null || !target.isAlive()) {
            return ActionOutcome.failed(PlanFailureReason.FAILED_TARGET_LOST);
        }

        if (
            !mob.level()
                .getGameRules()
                .getBoolean(GameRules.RULE_MOBGRIEFING)
        ) {
            return ActionOutcome.failed(PlanFailureReason.FAILED_PRECONDITION, AiGoalType.BREAK_OBSTACLE);
        }

        if (!blackboard.has(AiKeys.BREAK_TO_TARGET_TRIGGER)) {
            return ActionOutcome.SUCCESS;
        }

        var level = mob.level();

        if (targetBlock == null) {
            if (cooldowns.isOnCooldown(AiKeys.BREAK_TO_TARGET_SCAN)) {
                return ActionOutcome.RUNNING;
            }
            cooldowns.set(AiKeys.BREAK_TO_TARGET_SCAN, 10);

            var hint = blackboard.get(AiKeys.BREAK_TO_TARGET_SCAN, BlockPos.class);
            if (hint != null && isBreakable(level, hint, level.getBlockState(hint))) {
                targetBlock = hint;
            } else {
                // Prefer the exact blocking position(s) MoveToTargetAction traced and attached to a fresh
                // FAILED_BLOCKED feedback over independently re-deriving a guess via our own ray-march below — the
                // movement action already knows precisely what stopped it.
                targetBlock = pickFromFeedback(mob, blackboard);
            }
            if (targetBlock == null) {
                // Cheap fast path: a single ray toward the target resolves the common case (one wall block
                // directly on the line to the target) with one clip() call instead of the full block-by-block
                // march below. Only falls through to the march when the ray misses, hits something unbreakable,
                // or the geometry is too indirect for a straight line to find (e.g. around a corner).
                targetBlock = findObstructingBlockViaRay(mob, target);
            }
            if (targetBlock == null) {
                targetBlock = findObstructingBlock(mob, target);
            }

            if (targetBlock == null) {
                blackboard.remove(AiKeys.BREAK_TO_TARGET_TRIGGER);
                // Explicitly attribute to BREAK_OBSTACLE, not whatever goal happened to be active. This action is
                // frequently auto-triggered opportunistically by MoveToTargetAction mid-chase (via
                // BREAK_TO_TARGET_TRIGGER) while HUNT_TARGET is still the active goal — without this override, a
                // failed break attempt would default to attributing the failure to HUNT_TARGET, which the planner's
                // FAILED_OBSTACLE_UNBREAKABLE handling then heavily penalizes (huntScore -40, suppressed for 120
                // ticks), making the mob refuse to press an otherwise perfectly viable attack for six real seconds
                // just because an unrelated break attempt didn't pan out.
                return ActionOutcome.failed(PlanFailureReason.FAILED_OBSTACLE_UNBREAKABLE, AiGoalType.BREAK_OBSTACLE);
            }
            breakProgress = 0f;
        }

        if (!isWithinReach(mob, targetBlock)) {
            level.destroyBlockProgress(breakId, targetBlock, -1);
            targetBlock = null;
            blackboard.remove(AiKeys.BREAK_TO_TARGET_TRIGGER);
            blackboard.remove(AiKeys.BREAK_TO_TARGET_SCAN);
            return ActionOutcome.SUCCESS;
        }

        var state = level.getBlockState(targetBlock);

        if (state.isAir() || state.getCollisionShape(level, targetBlock).isEmpty()) {
            level.destroyBlockProgress(breakId, targetBlock, -1);
            targetBlock = null;
            blackboard.remove(AiKeys.BREAK_TO_TARGET_TRIGGER);
            return ActionOutcome.SUCCESS;
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
            // Fix: report SUCCESS the instant the break completes, instead of falling through to RUNNING and
            // waiting a further tick for the top-of-tick "no trigger" check to catch up. One idle tick isn't much
            // on its own, but it's one more tick a higher-priority combat action has to wait to preempt this one.
            return ActionOutcome.SUCCESS;
        }

        return ActionOutcome.RUNNING;
    }

    @Override
    public void stop(E mob, Blackboard blackboard, Cooldowns cooldowns, ActionStatus reason) {
        if (targetBlock != null) {
            mob.level().destroyBlockProgress(breakId, targetBlock, -1);
        }
        targetBlock = null;
        breakProgress = 0f;
        // Mark this BREAK_OBSTACLE commitment as resolved, regardless of why we're stopping. Safe even for a genuine
        // mid-break INTERRUPTED: if the obstruction is still real, BREAK_TO_TARGET_TRIGGER is still set (tick()'s own
        // cleanup only clears it on an actual SUCCESS/FAILURE resolution), and a set trigger bypasses this flag
        // entirely in the tree's routing condition — so an interrupted-but-still-real obstruction can still be
        // re-entered once whatever preempted this action clears.
        blackboard.set(AiKeys.BREAK_TO_TARGET_EXHAUSTED, Boolean.TRUE);
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
     * Fast-path obstruction check inspired by Gigeresque's {@code BreakBlocksGoal}: a single ray toward the target
     * doing double duty as both a line-of-sight probe and an obstruction finder, instead of stepping every block column
     * the way {@link #findObstructingBlock}'s DDA march does. Covers the common case cheaply — a single wall block
     * sitting directly on the line between mob and target — and leaves the fuller (and pricier) march to handle
     * geometry a straight ray can't, like a corner the mob has to path around.
     * <p>
     * <b>Fix:</b> traces from feet level ({@link net.minecraft.world.entity.Entity#position()}), not eye level. Every
     * other obstruction-finder in this system — the DDA march below, and {@code MoveToTargetAction}'s own collision
     * recovery — checks feet before head, since a solid foot-level block is what actually stops a mob from walking
     * forward. Tracing eye-to-eye only ever found whatever happened to be at eye height, so on a 2-tall wall it would
     * repeatedly clear only the top block and never the one actually blocking movement.
     *
     * @return the first solid, breakable block the ray hits before reaching the target, or {@code null} if the ray
     *         missed, hit something unbreakable, or reached the target directly (nothing in the way)
     */
    private static BlockPos findObstructingBlockViaRay(AbstractAlienEntity mob, LivingEntity target) {
        var level = mob.level();
        var from = mob.position();
        var to = target.position();

        if (from.distanceToSqr(to) > (double) (6 * 6)) {
            return null;
        }

        var hit = level.clip(
            new ClipContext(
                from,
                to,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                mob
            )
        );

        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }

        var pos = hit.getBlockPos();
        if (pos.equals(target.blockPosition())) {
            return null;
        }

        var state = level.getBlockState(pos);
        return isBreakable(level, pos, state) ? pos : null;
    }

    /**
     * Consumes the block position(s) {@link MoveToTargetAction} traced and attached to a fresh
     * {@link PlanFailureReason#FAILED_BLOCKED} {@link PlanFeedback} — the exact obstruction the movement action
     * identified, rather than something this action has to separately re-derive via {@link #findObstructingBlock}.
     * Returns the first breakable position from that trace, or {@code null} if there's no fresh FAILED_BLOCKED
     * feedback, or none of its traced positions turn out to be breakable (in which case the caller falls back to its
     * own ray-march).
     */
    private static BlockPos pickFromFeedback(AbstractAlienEntity mob, Blackboard blackboard) {
        var feedback = blackboard.get(AiKeys.LAST_PLAN_FEEDBACK, PlanFeedback.class);
        if (feedback == null || feedback.reason() != PlanFailureReason.FAILED_BLOCKED)
            return null;

        var tick = (int) mob.level().getGameTime();
        if (!feedback.isFresh(tick))
            return null;

        var level = mob.level();
        for (var pos : feedback.blockingPositions()) {
            var state = level.getBlockState(pos);
            if (isBreakable(level, pos, state))
                return pos;
        }
        return null;
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
