package mod.azure.ovomorphosis.ai.actions;

import com.azure.azurecortex.api.action.Action;
import com.azure.azurecortex.api.action.ActionOutcome;
import com.azure.azurecortex.api.action.ActionStatus;
import com.azure.azurecortex.api.blackboard.Blackboard;
import com.azure.azurecortex.api.blackboard.CommonBlackboardKeys;
import com.azure.azurecortex.goap.PlanFailureReason;
import com.azure.azurecortex.navigation.movement.MovementController;
import com.azure.azurecortex.runtime.CooldownTracker;
import com.azure.azurecortex.runtime.InterruptCategory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.entities.xenomorph.XenomorphEntity;

public final class FleeFireAction<E extends Mob, G> implements Action<E, G> {

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

    /**
     * How often (ticks) {@link #shouldFleefire} actually re-runs {@link #findNearestFire}'s block scan, reusing
     * {@link mod.azure.ovomorphosis.ai.core.AiKeys#FIRE_SCAN_RESULT} on ticks in between. 5 ticks (a quarter second) is
     * imperceptible for a slow-changing environmental hazard like fire, and cuts the scan's per-mob, per-tick cost by
     * 5x — this precheck runs unconditionally for every xenomorph every tick, unlike the rest of this class which only
     * runs while actually fleeing.
     */
    private static final int SCAN_INTERVAL_TICKS = 5;

    private final int priority;

    private final int[] steerBias = { 0 };

    private int stuckTicks;

    private Vec3 lastPos;

    public FleeFireAction(int priority) {
        this.priority = priority;
    }

    @Override
    public void start(E mob, Blackboard blackboard, CooldownTracker cooldowns) {
        stuckTicks = 0;
        lastPos = mob.position();
        mob.setAggressive(false);
    }

    @Override
    public ActionOutcome<G> tick(E mob, Blackboard blackboard, CooldownTracker cooldowns) {
        if (mob.getHealth() <= 0)
            return ActionOutcome.failed();

        if (
            mob instanceof XenomorphEntity xeno
                && xeno.isFireHardened()
        ) {
            return ActionOutcome.failed();
        }

        var nearestFire = findNearestFire(mob);
        if (nearestFire != null) {
            blackboard.set(CommonBlackboardKeys.LAST_FIRE_POS, nearestFire);
            updateFireAttacker(mob, blackboard, nearestFire);
        } else {
            nearestFire = blackboard.get(CommonBlackboardKeys.LAST_FIRE_POS);
        }

        var onFire = mob.isOnFire();

        if (nearestFire == null && !onFire) {
            writeTolerance(blackboard);
            blackboard.set(CommonBlackboardKeys.LAST_FIRE_POS, null);
            blackboard.set(
                CommonBlackboardKeys.FIRE_FLEE_COOLDOWN,
                (int) mob.level().getGameTime() + POST_FLEE_COOLDOWN_TICKS
            );
            slowDown(mob);
            return ActionOutcome.success();
        }

        mob.setAggressive(false);

        if (nearestFire != null) {
            var distSq = mob.distanceToSqr(Vec3.atCenterOf(nearestFire));
            if (distSq >= SAFE_DIST_SQ && !onFire) {
                writeTolerance(blackboard);
                blackboard.set(CommonBlackboardKeys.LAST_FIRE_POS, null);
                blackboard.set(
                    CommonBlackboardKeys.FIRE_FLEE_COOLDOWN,
                    (int) mob.level().getGameTime() + POST_FLEE_COOLDOWN_TICKS
                );
                slowDown(mob);
                return ActionOutcome.success();
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
            return ActionOutcome.failed(PlanFailureReason.FAILED_STUCK);
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
        var safe = MovementController.findSafeMovement(mob, desired, steerBias);
        var move = safe.equals(Vec3.ZERO) ? desired : safe;

        mob.setDeltaMovement(move.x, mob.getDeltaMovement().y, move.z);
        mob.hasImpulse = true;

        var yaw = (float) (Math.atan2(move.z, move.x) * (180.0 / Math.PI)) - 90.0F;
        mob.setYRot(yaw);
        mob.yBodyRot = yaw;
        mob.yHeadRot = yaw;

        return ActionOutcome.running();
    }

    @Override
    public void stop(E mob, Blackboard blackboard, CooldownTracker cooldowns, ActionStatus reason) {
        slowDown(mob);
    }

    @Override
    public boolean isInterruptible() {
        return true;
    }

    /**
     * Fire is a life-threatening emergency: this action must be able to preempt a {@link InterruptCategory#LOCKED}
     * action (e.g. mid-{@code CarryToWebAction}) rather than waiting for it to finish or expire.
     */
    @Override
    public InterruptCategory interruptCategory() {
        return InterruptCategory.EMERGENCY;
    }

    @Override
    public int priority() {
        return priority;
    }

    /**
     * Attempts to identify which entity caused the fire near {@code mob} and writes it to
     * {@link CommonBlackboardKeys#LAST_FIRE_ATTACKER}, {@link CommonBlackboardKeys#TARGET_IS_FIRE_USER}, and
     * {@link CommonBlackboardKeys#FIRE_DANGER_UNTIL_TICK}.
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
            blackboard.set(CommonBlackboardKeys.LAST_FIRE_ATTACKER, attacker);
            var existingExpiry = blackboard.get(CommonBlackboardKeys.FIRE_DANGER_UNTIL_TICK);
            var newExpiry = currentTick + FIRE_DANGER_DURATION;
            if (existingExpiry == null || newExpiry > existingExpiry) {
                blackboard.set(CommonBlackboardKeys.FIRE_DANGER_UNTIL_TICK, newExpiry);
            }

            var currentTarget = blackboard.get(CommonBlackboardKeys.TARGET);
            blackboard.set(
                CommonBlackboardKeys.TARGET_IS_FIRE_USER,
                currentTarget != null && currentTarget == attacker
            );
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
     * <p>
     * This runs unconditionally every tick for every xenomorph (unlike the rest of this class, which only runs while
     * actually fleeing), so the underlying {@link #findNearestFire} scan is throttled to once every
     * {@link #SCAN_INTERVAL_TICKS} ticks via {@link AiKeys#FIRE_SCAN_COOLDOWN}, with the cached result in
     * {@link AiKeys#FIRE_SCAN_RESULT} reused in between.
     */
    public static <E extends Mob> boolean shouldFleefire(E mob, Blackboard blackboard, CooldownTracker cooldowns) {
        if (
            mob instanceof XenomorphEntity xeno
                && xeno.isFireHardened()
        ) {
            return false;
        }

        if (cooldowns.ready(AiKeys.FIRE_SCAN_COOLDOWN)) {
            var found = findNearestFire(mob);
            blackboard.set(AiKeys.FIRE_SCAN_RESULT, found);
            cooldowns.set(AiKeys.FIRE_SCAN_COOLDOWN, SCAN_INTERVAL_TICKS);
            return found != null;
        }

        return blackboard.get(AiKeys.FIRE_SCAN_RESULT) != null;
    }

    public static boolean isFireDangerActive(Blackboard blackboard, int currentTick) {
        var expiry = blackboard.get(CommonBlackboardKeys.FIRE_DANGER_UNTIL_TICK);
        return expiry != null && currentTick < expiry;
    }

    public static void tickFireAttackerMemory(Blackboard blackboard, int currentTick) {
        var expiry = blackboard.get(CommonBlackboardKeys.FIRE_DANGER_UNTIL_TICK);
        if (expiry != null && currentTick >= expiry) {
            blackboard.set(CommonBlackboardKeys.LAST_FIRE_ATTACKER, null);
            blackboard.set(CommonBlackboardKeys.TARGET_IS_FIRE_USER, false);
            blackboard.set(CommonBlackboardKeys.FIRE_DANGER_UNTIL_TICK, null);
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
        blackboard.set(CommonBlackboardKeys.FIRE_TOLERANCE, 0.0F);
    }

    private static <E extends Mob> void slowDown(E mob) {
        mob.setDeltaMovement(
            mob.getDeltaMovement().x * 0.25,
            mob.getDeltaMovement().y,
            mob.getDeltaMovement().z * 0.25
        );
    }

}
