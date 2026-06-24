package mod.azure.ovomorphosis.ai.actions.xenomorph;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.ai.core.*;
import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.ai.goap.PlanFailureReason;
import mod.azure.ovomorphosis.ai.goap.PlanFeedback;
import mod.azure.ovomorphosis.ai.util.CrawlingCustomAStar;
import mod.azure.ovomorphosis.ai.util.HiveMemory;
import mod.azure.ovomorphosis.ai.util.MovementUtils;
import mod.azure.ovomorphosis.level.ResinWebRegistry;
import mod.azure.ovomorphosis.registry.BlockRegistry;

/**
 * Carries a grabbed victim to the nearest {@code RESIN_WEB_CROSS} block for deposit.
 * <h3>Web location strategy</h3> Web lookup now goes through {@link HiveMemory} exclusively:
 * <ol>
 * <li>{@link HiveMemory#findNearestWebCross} is tried first against the already-cached set.</li>
 * <li>If that misses, {@link HiveMemory#syncFromRegistry} is called to pull fresh positions from
 * {@link ResinWebRegistry} (a chunk-bucketed index updated by {@code ResinWebFullBlock} on every placement and
 * removal). The expensive O(n³) world-cube scan that previously lived here is gone.</li>
 * </ol>
 * <h3>Sync cooldown</h3> Registry syncs are gated behind {@link AiKeys#HIVE_SYNC_COOLDOWN} (default 60 ticks) so the
 * chunk-bucket iteration does not run every tick when no web is nearby.
 */
public final class CarryToWebAction<E extends Mob> implements Action<E> {

    private final int priority;

    private final Consumer<E> onStartCallback;

    private final Consumer<E> onDepositCallback;

    private BlockPos webTarget = null;

    private List<BlockPos> path = Collections.emptyList();

    private int pathIndex = 0;

    private int repathCooldown = 0;

    private final int[] steerBias = { 0 };

    private int revalidateCooldown = 0;

    public CarryToWebAction(
        int priority,
        Consumer<E> onStartCallback,
        Consumer<E> onDepositCallback
    ) {
        this.priority = priority;
        this.onStartCallback = onStartCallback;
        this.onDepositCallback = onDepositCallback;
    }

    @Override
    public void start(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        cooldowns.set(AiKeys.PASSIVE_DECISION, 1);
        webTarget = null;
        path = Collections.emptyList();
        pathIndex = 0;
        repathCooldown = 0;
        revalidateCooldown = 0;

        syncMemoryFromRegistry(mob, blackboard, cooldowns);
        webTarget = resolveWebTarget(mob, blackboard);

        onStartCallback.accept(mob);
    }

    @Override
    public ActionStatus tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        if (mob.getHealth() <= 0)
            return ActionStatus.INTERRUPTED;

        var victim = blackboard.get(AiKeys.TARGET, LivingEntity.class);
        if (victim == null || !victim.isAlive()) {
            CommonMod.LOGGER.info(
                "[CarryToWeb] FAILURE: victim null={} alive={}",
                victim == null,
                victim != null && victim.isAlive()
            );
            writeFeedback(mob, blackboard, PlanFailureReason.FAILED_TARGET_LOST);
            return ActionStatus.FAILURE;
        }

        if (webTarget == null) {
            if (cooldowns.ready(AiKeys.HIVE_SYNC_COOLDOWN)) {
                syncMemoryFromRegistry(mob, blackboard, cooldowns);
            }
            webTarget = resolveWebTarget(mob, blackboard);
            if (webTarget == null) {
                CommonMod.LOGGER.info("[CarryToWeb] FAILURE: no web target found");
                writeFeedback(mob, blackboard, PlanFailureReason.FAILED_NO_WEB);
                return ActionStatus.FAILURE;
            }
            path = Collections.emptyList();
        } else if (revalidateCooldown <= 0) {
            var chunkLoaded = mob.level().isLoaded(webTarget);
            if (chunkLoaded && !mob.level().getBlockState(webTarget).is(BlockRegistry.RESIN_WEB_CROSS.get())) {
                webTarget = null;
                path = Collections.emptyList();
            }
            revalidateCooldown = 40;
        } else {
            revalidateCooldown--;
        }

        victim.startRiding(mob, true);

        var webVec = Vec3.atBottomCenterOf(webTarget);
        if (mob.distanceToSqr(webVec) <= 1.8D * 1.8D) {
            deposit(mob, victim, blackboard, cooldowns);
            return ActionStatus.SUCCESS;
        }

        navigate(mob);
        faceToward(mob, webVec);

        return ActionStatus.RUNNING;
    }

    @Override
    public void stop(E mob, Blackboard blackboard, Cooldowns cooldowns, ActionStatus reason) {
        if (reason == ActionStatus.INTERRUPTED) {
            var victim = blackboard.get(AiKeys.TARGET, LivingEntity.class);
            if (victim != null) {
                victim.setDeltaMovement(0, -0.1, 0);
            }
        }
        mob.setDeltaMovement(
            mob.getDeltaMovement().x * 0.25,
            mob.getDeltaMovement().y,
            mob.getDeltaMovement().z * 0.25
        );
    }

    @Override
    public boolean isInterruptible() {
        return false;
    }

    @Override
    public int priority() {
        return priority;
    }

    /**
     * Returns the nearest valid web cross from {@link HiveMemory} (which may have just been synced from
     * {@link ResinWebRegistry}). No world scan is performed here.
     */
    private BlockPos resolveWebTarget(E mob, Blackboard blackboard) {
        var memory = blackboard.get(AiKeys.HIVE_MEMORY, HiveMemory.class);
        if (memory == null)
            return null;

        var level = mob.level();
        var origin = mob.blockPosition();
        var maxRange = 80D;
        var maxRangeSqr = maxRange * maxRange;

        BlockPos best = null;
        var bestDistSq = Double.MAX_VALUE;

        for (var pos : memory.getAllWebCrosses()) {
            if (origin.distSqr(pos) > maxRangeSqr)
                continue;
            if (!level.isLoaded(pos))
                continue;
            if (!level.getBlockState(pos).is(BlockRegistry.RESIN_WEB_CROSS.get()))
                continue;
            if (isBlockOccupied(level, pos))
                continue;

            var dSq = origin.distSqr(pos);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                best = pos;
            }
        }

        return best;
    }

    private static boolean isBlockOccupied(Level level, BlockPos pos) {
        var cx = pos.getX() + 0.5;
        var cy = pos.getY() + 0.5;
        var cz = pos.getZ() + 0.5;
        var aabb = new AABB(
            cx - 0.5,
            cy - 0.5,
            cz - 0.5,
            cx + 0.5,
            cy + 0.5,
            cz + 0.5
        );
        return !level.getEntitiesOfClass(
            LivingEntity.class,
            aabb,
            e -> e.isAlive() && !e.isSpectator()
        ).isEmpty();
    }

    /**
     * Pulls fresh cross-block positions from {@link ResinWebRegistry} into {@link HiveMemory} and resets the sync
     * cooldown.
     * <p>
     * This is cheap: the registry iterates at most a small grid of chunk buckets rather than every block in a cube.
     * Still, callers should gate it behind {@link AiKeys#HIVE_SYNC_COOLDOWN} to avoid even that small overhead running
     * every tick.
     */
    private void syncMemoryFromRegistry(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        var memory = blackboard.get(AiKeys.HIVE_MEMORY, HiveMemory.class);
        if (memory == null)
            return;

        memory.syncFromRegistry(mob.level(), mob.blockPosition(), 80D);
        cooldowns.set(AiKeys.HIVE_SYNC_COOLDOWN, 60);
    }

    private void deposit(E mob, LivingEntity victim, Blackboard blackboard, Cooldowns cooldowns) {
        victim.stopRiding();
        var centre = Vec3.atBottomCenterOf(webTarget);
        victim.setPos(centre.x, webTarget.getY(), centre.z);
        victim.setDeltaMovement(Vec3.ZERO);
        victim.setNoGravity(false);

        blackboard.set(AiKeys.TARGET, null);
        mob.setTarget(null);

        cooldowns.set(AiKeys.CARRY_COOLDOWN, 200);

        onDepositCallback.accept(mob);
    }

    private void navigate(E mob) {
        if (repathCooldown > 0) {
            repathCooldown--;
        }

        if (repathCooldown <= 0 || path.isEmpty() || pathIndex >= path.size()) {
            path = CrawlingCustomAStar.findPath(mob, mob.blockPosition(), webTarget, 1024, 2);
            pathIndex = path.size() > 1 ? 1 : 0;
            repathCooldown = 15;
        }

        while (
            pathIndex < path.size()
                && mob.position().distanceToSqr(Vec3.atBottomCenterOf(path.get(pathIndex))) < 1.2D
        ) {
            pathIndex++;
        }

        Vec3 direction;
        if (pathIndex < path.size()) {
            direction = Vec3.atBottomCenterOf(path.get(pathIndex)).subtract(mob.position());
        } else {
            repathCooldown = 0;
            direction = Vec3.atBottomCenterOf(webTarget).subtract(mob.position());
        }

        var horizontal = new Vec3(direction.x, 0, direction.z);
        if (horizontal.lengthSqr() < 0.0001D)
            return;

        var normalised = horizontal.normalize().scale(0.28D);
        var movement = MovementUtils.steerAwayFromDangerEntities(mob, normalised);
        var safe = MovementUtils.findSafeMovement(mob, movement, steerBias);

        var toApply = safe.equals(Vec3.ZERO) ? normalised : safe;

        mob.setDeltaMovement(toApply.x, mob.getDeltaMovement().y, toApply.z);
        mob.hasImpulse = true;
    }

    private void faceToward(E mob, Vec3 target) {
        var dx = target.x - mob.getX();
        var dz = target.z - mob.getZ();
        var yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        mob.setYRot(yaw);
        mob.yBodyRot = yaw;
        mob.yHeadRot = yaw;
    }

    private void writeFeedback(E mob, Blackboard blackboard, PlanFailureReason reason) {
        var activeGoalType = blackboard.get(AiKeys.ACTIVE_GOAL_TYPE, AiGoalType.class);
        blackboard.set(
            AiKeys.LAST_PLAN_FEEDBACK,
            PlanFeedback.of(
                reason,
                (int) mob.level().getGameTime(),
                mob.blockPosition(),
                activeGoalType != null ? activeGoalType : AiGoalType.NONE
            )
        );
    }
}
