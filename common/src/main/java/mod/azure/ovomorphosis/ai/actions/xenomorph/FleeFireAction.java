package mod.azure.ovomorphosis.ai.actions.xenomorph;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import mod.azure.ovomorphosis.ai.core.Action;
import mod.azure.ovomorphosis.ai.core.ActionStatus;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.Blackboard;
import mod.azure.ovomorphosis.ai.core.Cooldowns;
import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.ai.goap.PlanFailureReason;
import mod.azure.ovomorphosis.ai.goap.PlanFeedback;
import mod.azure.ovomorphosis.ai.util.MovementUtils;

public final class FleeFireAction<E extends Mob> implements Action<E> {

    private static final int SCAN_RADIUS = 4;

    public static final float TOLERANCE_GAIN_RATE = 8.0f;

    public static final float ON_FIRE_GAIN_RATE = 20.0f;

    public static final float TOLERANCE_DRAIN_RATE = 0.4f;

    public static final float TOLERANCE_THRESHOLD = 12f;

    public static final float MAX_TOLERANCE = 120f;

    public static final double SAFE_DIST_SQ = 12.0 * 12.0;

    public static final int POST_FLEE_COOLDOWN_TICKS = 60;

    private static final double FLEE_SPEED = 0.52D;

    private static final int STUCK_TICKS_MAX = 40;

    private final int priority;

    private final int[] steerBias = { 0 };

    private int stuckTicks;

    private Vec3 lastPos;

    public FleeFireAction(int priority) {
        this.priority = priority;
    }

    @Override
    public void start(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        stuckTicks = 0;
        lastPos = mob.position();
        mob.setAggressive(false);
    }

    @Override
    public ActionStatus tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        if (mob.getHealth() <= 0)
            return ActionStatus.INTERRUPTED;

        var nearestFire = findNearestFire(mob);
        if (nearestFire != null) {
            blackboard.set(AiKeys.LAST_FIRE_POS, nearestFire);
        } else {
            nearestFire = blackboard.get(AiKeys.LAST_FIRE_POS, BlockPos.class);
        }

        var onFire = mob.isOnFire();

        if (nearestFire == null && !onFire) {
            writeTolerance(blackboard);
            blackboard.set(AiKeys.LAST_FIRE_POS, null);
            blackboard.set(
                AiKeys.FIRE_FLEE_COOLDOWN,
                (int) mob.level().getGameTime() + POST_FLEE_COOLDOWN_TICKS
            );
            slowDown(mob);
            return ActionStatus.SUCCESS;
        }

        mob.setAggressive(false);

        if (nearestFire != null) {
            var distSq = mob.distanceToSqr(Vec3.atCenterOf(nearestFire));
            if (distSq >= SAFE_DIST_SQ && !onFire) {
                writeTolerance(blackboard);
                blackboard.set(AiKeys.LAST_FIRE_POS, null);
                blackboard.set(AiKeys.FIRE_FLEE_COOLDOWN, (int) mob.level().getGameTime() + POST_FLEE_COOLDOWN_TICKS);
                slowDown(mob);
                return ActionStatus.SUCCESS;
            }
        }

        var cur = mob.position();
        if (cur.distanceToSqr(lastPos) < 0.005D) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        lastPos = cur;

        if (stuckTicks >= STUCK_TICKS_MAX) {
            writeFeedback(mob, blackboard);
            return ActionStatus.FAILURE;
        }

        Vec3 awayDir;
        if (nearestFire != null) {
            awayDir = mob.position().subtract(Vec3.atCenterOf(nearestFire));
        } else {
            awayDir = mob.getLookAngle().scale(-1);
        }

        var horizontal = new Vec3(awayDir.x, 0, awayDir.z);
        if (horizontal.lengthSqr() < 0.0001D) {
            var angle = mob.getRandom().nextDouble() * Math.PI * 2;
            horizontal = new Vec3(Math.cos(angle), 0, Math.sin(angle));
        }

        var desired = horizontal.normalize().scale(FLEE_SPEED);
        var safe = MovementUtils.findSafeMovement(mob, desired, steerBias);
        var move = safe.equals(Vec3.ZERO) ? desired : safe;

        mob.setDeltaMovement(move.x, mob.getDeltaMovement().y, move.z);
        mob.hasImpulse = true;

        var yaw = (float) (Math.atan2(move.z, move.x) * (180.0 / Math.PI)) - 90.0F;
        mob.setYRot(yaw);
        mob.yBodyRot = yaw;
        mob.yHeadRot = yaw;

        return ActionStatus.RUNNING;
    }

    @Override
    public void stop(E mob, Blackboard blackboard, Cooldowns cooldowns, ActionStatus reason) {
        slowDown(mob);
    }

    @Override
    public boolean isInterruptible() {
        return true;
    }

    @Override
    public int priority() {
        return priority;
    }

    private static float readTolerance(Blackboard blackboard) {
        var val = blackboard.get(AiKeys.FIRE_TOLERANCE, Float.class);
        return val != null ? val : 0f;
    }

    private static void writeTolerance(Blackboard blackboard) {
        blackboard.set(AiKeys.FIRE_TOLERANCE, 0.0F);
    }

    public static <E extends Mob> boolean hasNearbyFire(E mob) {
        return findNearestFire(mob) != null;
    }

    private static <E extends Mob> BlockPos findNearestFire(E mob) {
        var origin = mob.blockPosition();
        var level = mob.level();

        BlockPos best = null;
        var bestDist = Double.MAX_VALUE;

        for (var x = -SCAN_RADIUS; x <= SCAN_RADIUS; x++) {
            for (var y = -3; y <= 4; y++) {
                for (var z = -SCAN_RADIUS; z <= SCAN_RADIUS; z++) {
                    var pos = origin.offset(x, y, z);
                    var state = level.getBlockState(pos);
                    if (
                        state.is(BlockTags.FIRE)
                            || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                            || state.is(Blocks.LAVA) || state.is(Blocks.MAGMA_BLOCK)
                            || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
                            || state.is(Blocks.LAVA_CAULDRON)
                    ) {
                        var dist = origin.distSqr(pos);
                        if (dist < bestDist) {
                            bestDist = dist;
                            best = pos.immutable();
                        }
                    }
                }
            }
        }
        return best;
    }

    private static <E extends Mob> void slowDown(E mob) {
        mob.setDeltaMovement(
            mob.getDeltaMovement().x * 0.25,
            mob.getDeltaMovement().y,
            mob.getDeltaMovement().z * 0.25
        );
    }

    private static <E extends Mob> void writeFeedback(E mob, Blackboard blackboard) {
        var goalType = blackboard.get(AiKeys.ACTIVE_GOAL_TYPE, AiGoalType.class);
        blackboard.set(
            AiKeys.LAST_PLAN_FEEDBACK,
            PlanFeedback.of(
                PlanFailureReason.FAILED_STUCK,
                (int) mob.level().getGameTime(),
                mob.blockPosition(),
                goalType != null ? goalType : AiGoalType.NONE
            )
        );
    }
}
