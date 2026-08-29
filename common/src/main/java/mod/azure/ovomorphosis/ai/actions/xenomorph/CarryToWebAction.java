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
import mod.azure.ovomorphosis.ai.goap.PlanFailureReason;
import mod.azure.ovomorphosis.ai.nav.CrawlingCustomAStar;
import mod.azure.ovomorphosis.ai.nav.MovementUtils;
import mod.azure.ovomorphosis.ai.nav.PathNodeCache;
import mod.azure.ovomorphosis.ai.util.HiveMemory;
import mod.azure.ovomorphosis.ai.util.IncrementalPathSession;
import mod.azure.ovomorphosis.level.ResinWebRegistry;
import mod.azure.ovomorphosis.registry.BlockRegistry;

public final class CarryToWebAction<E extends Mob> implements Action<E> {

    /**
     * Ticks without meaningful positional displacement (despite an active path) before the action gives up and drops
     * the passenger. ~5 seconds.
     */
    private static final int STUCK_TICKS_MAX = 100;

    /** Soft threshold: once stuckTicks crosses this, report BLOCKED(FAILED_STUCK) every tick until it clears. */
    private static final int STUCK_TICKS_SOFT = 25;

    /**
     * Ticks without the distance-to-web actually shrinking (even if the mob is technically moving, e.g. circling an
     * obstacle) before giving up. ~8 seconds.
     */
    private static final int NO_PROGRESS_TICKS_MAX = 160;

    /** Soft threshold: once noProgressTicks crosses this, report BLOCKED(FAILED_STUCK) every tick until it clears. */
    private static final int NO_PROGRESS_TICKS_SOFT = 40;

    /**
     * Absolute hard cap on how long a single carry attempt may run, regardless of whether stuck/no-progress counters
     * ever individually trip. Guarantees termination even against pathological cases neither counter catches. ~30
     * seconds.
     */
    private static final int MAX_DURATION_TICKS = 600;

    /** Consecutive empty-path results from the pathfinder before giving up as FAILED_NO_PATH. */
    private static final int MAX_PATH_FAILURES = 5;

    private final int priority;

    private final Consumer<E> onStartCallback;

    private final Consumer<E> onDepositCallback;

    private BlockPos webTarget = null;

    private List<BlockPos> path = Collections.emptyList();

    private int pathIndex = 0;

    private int repathCooldown = 0;

    private final int[] steerBias = { 0 };

    private int revalidateCooldown = 0;

    private Vec3 lastPos = Vec3.ZERO;

    private int stuckTicks = 0;

    private int noProgressTicks = 0;

    private double lastDistSqToWeb = Double.MAX_VALUE;

    private int pathFailureCount = 0;

    private int ticksActive = 0;

    /** Set by {@link #navigate} when the repath attempt taken this tick came back empty. */
    private boolean pathAttemptFailedThisTick = false;

    /**
     * The in-progress incremental search toward {@link #webTarget}, or {@code null} when none is running. See
     * {@link IncrementalPathSession} and {@code OvomorphosisConfig#enableIncrementalPathfinding}. Unlike
     * {@code MoveToTargetAction}/{@code MoveToDestinationAction} this action has no fallback tiers to chain (the
     * original synchronous call here was always a single {@code CrawlingCustomAStar.findPath}, with pathfinding failure
     * handled entirely via {@link #pathFailureCount}), so a single {@link IncrementalPathSession} suffices — no
     * {@code PhasedPathSession} is needed.
     */
    private IncrementalPathSession pathSession = null;

    /** The start/{@link #webTarget} {@link #pathSession} was created for, used to detect staleness. */
    private BlockPos pathSessionStart = null;

    private BlockPos pathSessionGoal = null;

    /** Ticks {@link #pathSession} has been running; a safety valve so a pathological search can't run forever. */
    private int pathSessionAgeTicks = 0;

    /** A {@link PathNodeCache} dedicated to {@link #pathSession}'s lifetime; cleared only when a new one starts. */
    private final PathNodeCache sessionCache = new PathNodeCache();

    /** If the mob moved further than this (blocks, squared) since {@link #pathSession} started, restart it. */
    private static final double PATH_SESSION_START_DRIFT_SQ = 3.0D * 3.0D;

    /**
     * If {@link #webTarget} moved further than this (blocks, squared) since {@link #pathSession} started, restart it.
     */
    private static final double PATH_SESSION_GOAL_DRIFT_SQ = 4.0D * 4.0D;

    /** Hard cap on how many ticks a single incremental session may run before it is abandoned and restarted. */
    private static final int PATH_SESSION_MAX_AGE_TICKS = 100;

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
        lastPos = mob.position();
        stuckTicks = 0;
        noProgressTicks = 0;
        lastDistSqToWeb = Double.MAX_VALUE;
        pathFailureCount = 0;
        ticksActive = 0;
        pathAttemptFailedThisTick = false;
        pathSession = null;
        pathSessionStart = null;
        pathSessionGoal = null;
        pathSessionAgeTicks = 0;
        sessionCache.clear();

        syncMemoryFromRegistry(mob, blackboard, cooldowns);
        webTarget = resolveWebTarget(mob, blackboard);

        onStartCallback.accept(mob);
    }

    @Override
    public ActionOutcome tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        if (mob.getHealth() <= 0)
            return ActionOutcome.failed();

        var victim = blackboard.get(AiKeys.TARGET, LivingEntity.class);
        if (victim == null || !victim.isAlive()) {
            return ActionOutcome.failed(PlanFailureReason.FAILED_TARGET_LOST);
        }

        if (webTarget == null) {
            if (cooldowns.ready(AiKeys.HIVE_SYNC_COOLDOWN)) {
                syncMemoryFromRegistry(mob, blackboard, cooldowns);
            }
            webTarget = resolveWebTarget(mob, blackboard);
            if (webTarget == null) {
                dropPassengerSafely(mob, victim);
                return ActionOutcome.failed(PlanFailureReason.FAILED_NO_WEB);
            }
            path = Collections.emptyList();
            lastDistSqToWeb = Double.MAX_VALUE;
            pathSession = null;
        } else if (revalidateCooldown <= 0) {
            var chunkLoaded = mob.level().isLoaded(webTarget);
            if (chunkLoaded && !mob.level().getBlockState(webTarget).is(BlockRegistry.RESIN_WEB_CROSS.get())) {
                webTarget = null;
                path = Collections.emptyList();
                pathSession = null;

                webTarget = resolveWebTarget(mob, blackboard);
                if (webTarget == null) {
                    dropPassengerSafely(mob, victim);
                    return ActionOutcome.failed(PlanFailureReason.FAILED_NO_WEB);
                }
                lastDistSqToWeb = Double.MAX_VALUE;
            }
            revalidateCooldown = 40;
        } else {
            revalidateCooldown--;
        }

        ticksActive++;
        if (ticksActive >= MAX_DURATION_TICKS) {
            dropPassengerSafely(mob, victim);
            return ActionOutcome.failed(PlanFailureReason.FAILED_STUCK);
        }

        var movedSqr = mob.position().distanceToSqr(lastPos);
        lastPos = mob.position();
        stuckTicks = movedSqr < 0.0025D ? stuckTicks + 1 : 0;

        if (webTarget != null) {
            var distSqToWebNow = mob.distanceToSqr(Vec3.atBottomCenterOf(webTarget));
            if (distSqToWebNow < lastDistSqToWeb - 0.1D) {
                lastDistSqToWeb = distSqToWebNow;
                noProgressTicks = 0;
            } else {
                noProgressTicks++;
            }
        }

        if (stuckTicks >= STUCK_TICKS_MAX || noProgressTicks >= NO_PROGRESS_TICKS_MAX) {
            dropPassengerSafely(mob, victim);
            return ActionOutcome.failed(PlanFailureReason.FAILED_STUCK);
        }

        if (pathFailureCount >= MAX_PATH_FAILURES) {
            dropPassengerSafely(mob, victim);
            return ActionOutcome.failed(PlanFailureReason.FAILED_NO_PATH);
        }

        victim.startRiding(mob, true);

        var webVec = Vec3.atBottomCenterOf(webTarget);
        if (mob.distanceToSqr(webVec) <= 1.8D * 1.8D) {
            deposit(mob, victim, blackboard, cooldowns);
            return ActionOutcome.SUCCESS;
        }

        pathAttemptFailedThisTick = false;
        navigate(mob);
        faceToward(mob, webVec);

        if (pathAttemptFailedThisTick) {
            return ActionOutcome.blocked(PlanFailureReason.FAILED_NO_PATH, mob.blockPosition());
        }
        if (stuckTicks >= STUCK_TICKS_SOFT || noProgressTicks >= NO_PROGRESS_TICKS_SOFT) {
            return ActionOutcome.blocked(PlanFailureReason.FAILED_STUCK, mob.blockPosition());
        }

        return ActionOutcome.RUNNING;
    }

    @Override
    public void stop(E mob, Blackboard blackboard, Cooldowns cooldowns, ActionStatus reason) {
        if (reason == ActionStatus.INTERRUPTED || reason == ActionStatus.FAILURE) {
            var victim = blackboard.get(AiKeys.TARGET, LivingEntity.class);
            if (victim != null && victim.isPassenger() && victim.getVehicle() == mob) {
                dropPassengerSafely(mob, victim);
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
     * Safely detaches {@code victim} from {@code mob}, placing it at the mob's current position with a small downward
     * nudge instead of leaving it riding a mob that has given up carrying it. Called from every hard termination path
     * (stuck, no-path, no-web, max-duration) as well as the {@link #stop} safety net.
     */
    private void dropPassengerSafely(E mob, LivingEntity victim) {
        if (!victim.isPassenger() || victim.getVehicle() != mob) {
            return;
        }
        victim.stopRiding();
        var mobPos = mob.position();
        victim.setPos(mobPos.x, mob.getY(), mobPos.z);
        victim.setDeltaMovement(0, -0.1, 0);
        victim.setNoGravity(false);
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

        for (var pos : memory.getOwnedWebCrosses()) {
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

        memory.findNearestOwnedWebCross(mob.level(), mob.blockPosition(), 80D);
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
            final var searchStart = mob.blockPosition();
            final var searchGoal = webTarget;

            List<BlockPos> newPath;

            if (CommonMod.getConfig().enableIncrementalPathfinding) {
                var stale = pathSession != null
                    && (pathSessionStart.distSqr(searchStart) > PATH_SESSION_START_DRIFT_SQ
                        || pathSessionGoal.distSqr(searchGoal) > PATH_SESSION_GOAL_DRIFT_SQ
                        || pathSessionAgeTicks > PATH_SESSION_MAX_AGE_TICKS);

                if (pathSession == null || stale) {
                    sessionCache.clear();
                    pathSession = IncrementalPathSession.crawling(mob, searchStart, searchGoal, 1024, 2, sessionCache);
                    pathSessionStart = searchStart;
                    pathSessionGoal = searchGoal;
                    pathSessionAgeTicks = 0;
                }

                pathSessionAgeTicks++;
                var status = pathSession.step(CommonMod.getConfig().incrementalPathfindingNodeBudget);

                newPath = switch (status) {
                    case RUNNING -> null;
                    case DONE -> pathSession.result();
                    case FAILED -> Collections.emptyList();
                };

                if (status != IncrementalPathSession.Status.RUNNING) {
                    pathSession = null;
                }
            } else {
                newPath = CrawlingCustomAStar.findPath(mob, searchStart, searchGoal, 1024, 2);
            }

            if (newPath != null) {
                path = newPath;
                pathIndex = path.size() > 1 ? 1 : 0;
                repathCooldown = 15;

                if (path.isEmpty()) {
                    pathFailureCount++;
                    pathAttemptFailedThisTick = true;
                } else {
                    pathFailureCount = 0;
                }
            }
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
}
