package mod.azure.ovomorphosis.ai.actions;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
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
import mod.azure.ovomorphosis.entities.xenomorph.XenomorphEntity;

public final class FleeFireAction<E extends Mob> implements Action<E> {

    private static final int SCAN_RADIUS = 4;

    /**
     * How long (ticks) fire-attacker danger persists after the last fire event. 200 ticks = 10 seconds. Extended on
     * repeated events.
     */
    private static final int FIRE_DANGER_DURATION = 200;

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

        if (
            mob instanceof XenomorphEntity xeno
                && xeno.isFireHardened()
        ) {
            return ActionStatus.FAILURE;
        }

        var nearestFire = findNearestFire(mob);
        if (nearestFire != null) {
            blackboard.set(AiKeys.LAST_FIRE_POS, nearestFire);
            updateFireAttacker(mob, blackboard, nearestFire);
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
                blackboard.set(
                    AiKeys.FIRE_FLEE_COOLDOWN,
                    (int) mob.level().getGameTime() + POST_FLEE_COOLDOWN_TICKS
                );
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

        if (mob instanceof XenomorphEntity xeno) {
            var gain = mob.isOnFire() ? ON_FIRE_GAIN_RATE : TOLERANCE_GAIN_RATE;
            xeno.setFireToleranceNbt(xeno.getFireToleranceNbt() + gain);
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

    /**
     * Attempts to identify which entity caused the fire near {@code mob} and writes it to
     * {@link AiKeys#LAST_FIRE_ATTACKER}, {@link AiKeys#TARGET_IS_FIRE_USER}, and {@link AiKeys#FIRE_DANGER_UNTIL_TICK}.
     * <p>
     * Attribution strategy (in priority order):
     * <ol>
     * <li>A flaming {@link AbstractArrow} near the fire whose owner is a living entity.</li>
     * <li>A living entity within 8 blocks that is holding a fire-related item (checked via {@link #isHoldingFireTool})
     * — covers flint-and-steel and lava bucket users.</li>
     * <li>The mob's current vanilla target if it is alive and within 16 blocks — best-effort fallback for cases like
     * fire-aspect weapons.</li>
     * </ol>
     * <p>
     * The attacker record is extended by {@link #FIRE_DANGER_DURATION} ticks each time fire is detected, so it persists
     * through brief gaps where the fire block has been removed.
     */
    private static <E extends Mob> void updateFireAttacker(E mob, Blackboard blackboard, BlockPos firePos) {
        var level = mob.level();
        var currentTick = (int) level.getGameTime();

        LivingEntity attacker = null;

        var arrowBox = new AABB(
            firePos.getX() - 3,
            firePos.getY() - 2,
            firePos.getZ() - 3,
            firePos.getX() + 3,
            firePos.getY() + 2,
            firePos.getZ() + 3
        );
        var arrows = level.getEntitiesOfClass(
            AbstractArrow.class,
            arrowBox,
            a -> a.isOnFire() && a.getOwner() instanceof LivingEntity
        );
        if (!arrows.isEmpty()) {
            attacker = (LivingEntity) arrows.get(0).getOwner();
        }

        if (attacker == null) {
            var nearbyBox = mob.getBoundingBox().inflate(8.0D);
            var candidates = level.getEntitiesOfClass(
                LivingEntity.class,
                nearbyBox,
                e -> e.isAlive() && e != mob && isHoldingFireTool(e)
            );
            if (!candidates.isEmpty()) {
                attacker = candidates.stream()
                    .min(java.util.Comparator.comparingDouble(mob::distanceToSqr))
                    .orElse(null);
            }
        }

        if (attacker == null) {
            var vanillaTarget = mob.getTarget();
            if (
                vanillaTarget != null && vanillaTarget.isAlive()
                    && mob.distanceToSqr(vanillaTarget) <= 16.0 * 16.0
            ) {
                attacker = vanillaTarget;
            }
        }

        if (attacker != null) {
            blackboard.set(AiKeys.LAST_FIRE_ATTACKER, attacker);
            var existingExpiry = blackboard.get(AiKeys.FIRE_DANGER_UNTIL_TICK, Integer.class);
            var newExpiry = currentTick + FIRE_DANGER_DURATION;
            if (existingExpiry == null || newExpiry > existingExpiry) {
                blackboard.set(AiKeys.FIRE_DANGER_UNTIL_TICK, newExpiry);
            }

            var currentTarget = blackboard.get(AiKeys.TARGET, LivingEntity.class);
            blackboard.set(AiKeys.TARGET_IS_FIRE_USER, currentTarget != null && currentTarget == attacker);
        }
    }

    /**
     * Returns {@code true} if {@code entity} is holding an item that could be used to start fires: flint and steel, a
     * lava bucket, or a fire-charge-type item.
     */
    private static boolean isHoldingFireTool(LivingEntity entity) {
        for (var slot : entity.getHandSlots()) {
            var item = slot.getItem();
            var id = BuiltInRegistries.ITEM.getKey(item).getPath();
            if (
                id.contains("flint_and_steel")
                    || id.contains("lava_bucket")
                    || id.contains("fire_charge")
            ) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if fire is nearby AND the mob is not yet fire-hardened. Use this in the behavior tree
     * fire-flee pre-check so hardened xenomorphs never enter the flee branch and instead rely on pathfinding
     * danger-block avoidance.
     */
    public static <E extends Mob> boolean shouldFleefire(E mob) {
        if (
            mob instanceof XenomorphEntity xeno
                && xeno.isFireHardened()
        ) {
            return false;
        }
        return findNearestFire(mob) != null;
    }

    public static boolean isFireDangerActive(Blackboard blackboard, int currentTick) {
        var expiry = blackboard.get(AiKeys.FIRE_DANGER_UNTIL_TICK, Integer.class);
        return expiry != null && currentTick < expiry;
    }

    public static void tickFireAttackerMemory(Blackboard blackboard, int currentTick) {
        var expiry = blackboard.get(AiKeys.FIRE_DANGER_UNTIL_TICK, Integer.class);
        if (expiry != null && currentTick >= expiry) {
            blackboard.set(AiKeys.LAST_FIRE_ATTACKER, null);
            blackboard.set(AiKeys.TARGET_IS_FIRE_USER, false);
            blackboard.set(AiKeys.FIRE_DANGER_UNTIL_TICK, null);
        }
    }

    static <E extends Mob> BlockPos findNearestFire(E mob) {
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

    private static void writeTolerance(Blackboard blackboard) {
        blackboard.set(AiKeys.FIRE_TOLERANCE, 0.0F);
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
