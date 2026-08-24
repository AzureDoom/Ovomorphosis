package mod.azure.ovomorphosis.ai.actions;

import mod.azure.ovomorphosis.ai.nav.*;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.ai.core.*;
import mod.azure.ovomorphosis.ai.goap.PlanFailureReason;
import mod.azure.ovomorphosis.ai.util.*;

/**
 * Moves the mob to {@link AiKeys#DESTINATION}, using the same A* path-following and wall-crawl logic as
 * {@link MoveToTargetAction}. The action succeeds when within {@code stopDistance} of the destination, and clears
 * {@link AiKeys#DESTINATION} on SUCCESS or FAILURE.
 */
public final class MoveToDestinationAction<E extends Mob> implements Action<E> {

    /**
     * Hard cap on {@link #noProgressTicks} before this action gives up and bubbles
     * {@link PlanFailureReason#FAILED_STUCK} up to GOAP, instead of retrying local recovery (detours, jumps) forever.
     */
    private static final int HARD_NO_PROGRESS_TICKS = 200;

    /**
     * Soft threshold on {@link #noProgressTicks}: once crossed (but before the hard cap), the action reports
     * {@link ActionOutcome.Blocked} every tick so GOAP gets an early, non-terminal signal.
     */
    private static final int SOFT_NO_PROGRESS_TICKS = HARD_NO_PROGRESS_TICKS / 2;

    private final double stopDistanceSqr;

    private final double speed;

    private final int priority;

    private final boolean canCrawl;

    private final int[] steerBias = { 0 };

    private Vec3 lastPos = Vec3.ZERO;

    private int stuckTicks = 0;

    private Vec3 detourDirection = Vec3.ZERO;

    private int detourTicks = 0;

    private List<BlockPos> path = Collections.emptyList();

    private int pathIndex = 0;

    private int repathCooldown = 0;

    private BlockPos lastPathedDestination = null;

    private double lastDistSqToDestination = Double.MAX_VALUE;

    private int noProgressTicks = 0;

    /**
     * Set by {@link #applyPathMovement} / {@link #applyFlatFallback} when they have an outcome to report this tick (a
     * hard-cap {@link ActionOutcome.Failed}, or a soft-threshold {@link ActionOutcome.Blocked}) rather than a plain
     * {@link ActionOutcome#RUNNING}.
     */
    private ActionOutcome pendingOutcome = null;

    /**
     * The in-progress incremental fallback chain (primary crawl → relaxed crawl → plain ground A*, all sharing one
     * per-tick budget), or {@code null} when none is running. See {@link PhasedPathSession},
     * {@link IncrementalPathSession}, and {@code OvomorphosisConfig#enableIncrementalPathfinding}.
     */
    private PhasedPathSession phasedSession = null;

    /** The start/destination {@link #phasedSession} was created for, used to detect staleness. */
    private BlockPos phasedSessionStart = null;

    private BlockPos phasedSessionGoal = null;

    /** Ticks {@link #phasedSession} has been running; a safety valve so a pathological search can't run forever. */
    private int phasedSessionAgeTicks = 0;

    /**
     * A {@link PathNodeCache} dedicated to {@link #phasedSession}'s lifetime. Cleared only when a new
     * {@link #phasedSession} starts, so a search spanning many ticks keeps the benefit of its own memoized terrain
     * classifications between steps. This action has no self-inflicted block-breaking (unlike
     * {@code MoveToTargetAction}), so there's no equivalent "just broke a wall" falling edge to selectively invalidate
     * around — a change to the destination or a long enough age still forces a fresh session (and therefore a fresh
     * cache) via {@link #PATH_SESSION_GOAL_DRIFT_SQ} / {@link #PATH_SESSION_MAX_AGE_TICKS} below.
     */
    private final PathNodeCache sessionCache = new PathNodeCache();

    /** If the mob's feet moved further than this (blocks, squared) since {@link #phasedSession} started, restart it. */
    private static final double PATH_SESSION_START_DRIFT_SQ = 3.0D * 3.0D;

    /**
     * If the destination moved further than this (blocks, squared) since {@link #phasedSession} started, restart it.
     */
    private static final double PATH_SESSION_GOAL_DRIFT_SQ = 4.0D * 4.0D;

    /** Hard cap on how many ticks a single incremental session may run before it is abandoned and restarted. */
    private static final int PATH_SESSION_MAX_AGE_TICKS = 100;

    public MoveToDestinationAction(
        double stopDistance,
        double speed,
        int priority,
        boolean canCrawl
    ) {
        this.stopDistanceSqr = stopDistance * stopDistance;
        this.speed = speed;
        this.priority = priority;
        this.canCrawl = canCrawl;
    }

    @Override
    public void start(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        cooldowns.set(AiKeys.PASSIVE_DECISION, 1);
        mob.setAggressive(true);
        lastPos = mob.position();
        stuckTicks = 0;
        detourDirection = Vec3.ZERO;
        detourTicks = 0;
        path = Collections.emptyList();
        pathIndex = 0;
        repathCooldown = 0;
        lastPathedDestination = null;
        lastDistSqToDestination = Double.MAX_VALUE;
        noProgressTicks = 0;
        pendingOutcome = null;
        phasedSession = null;
        phasedSessionStart = null;
        phasedSessionGoal = null;
        phasedSessionAgeTicks = 0;
        sessionCache.clear();
    }

    @Override
    public ActionOutcome tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        if (mob.getHealth() <= 0) {
            mob.setAggressive(false);
            return ActionOutcome.failed();
        }

        var destination = blackboard.get(AiKeys.DESTINATION, BlockPos.class);
        if (destination == null) {
            mob.setAggressive(false);
            return ActionOutcome.failed();
        }

        var destVec = Vec3.atBottomCenterOf(destination);

        if (mob.distanceToSqr(destVec) <= stopDistanceSqr) {
            mob.setDeltaMovement(mob.getDeltaMovement().scale(0.4D));
            faceDestination(mob, destination);
            return ActionOutcome.SUCCESS;
        }

        if (repathCooldown > 0)
            repathCooldown--;

        var mobFeetPos = BlockPos.containing(mob.getX(), mob.getBoundingBox().minY, mob.getZ());
        var groundBlock = mobFeetPos.below();
        var mobInFluid = !mob.level().getBlockState(mobFeetPos).getFluidState().isEmpty();
        var mobOnSolidGround = !mobInFluid && !mob.level()
            .getBlockState(groundBlock)
            .getCollisionShape(mob.level(), groundBlock)
            .isEmpty();

        var pathStart = mobFeetPos;
        var belowFeet = mob.level().getBlockState(mobFeetPos.below());
        var feetOrBelowInFluid = mobInFluid || !belowFeet.getFluidState().isEmpty();

        if (feetOrBelowInFluid) {
            if (mobInFluid) {
                if (mob.level().getBlockState(pathStart).getFluidState().isEmpty()) {
                    for (int dy = 1; dy <= 3; dy++) {
                        var candidate = pathStart.above(dy);
                        if (!mob.level().getBlockState(candidate).getFluidState().isEmpty()) {
                            pathStart = candidate;
                            break;
                        }
                    }
                }
            } else {
                pathStart = mobFeetPos.below();
            }
        }

        var fluidGoalRadius = feetOrBelowInFluid ? 2 : 0;

        var mobIsInOrAtTunnel = canCrawl
            && (CrawlingCustomAStar.tunnelCanStandAt(mob.level(), mob, mobFeetPos)
                || CrawlingCustomAStar.tunnelCanStandAt(mob.level(), mob, mobFeetPos.below())
                || CrawlingCustomAStar.tunnelCanStandAt(mob.level(), mob, mobFeetPos.above())
                || (!mobOnSolidGround && CrawlingCustomAStar.verticalShaftCanCrawlAt(mob.level(), mob, mobFeetPos))
                || (!mobOnSolidGround
                    && CrawlingCustomAStar.verticalShaftCanCrawlAt(mob.level(), mob, mobFeetPos.below())));

        var nextWaypointNeedsCrawl = false;
        if (canCrawl && !path.isEmpty() && pathIndex < path.size()) {
            for (var li = pathIndex; li < Math.min(path.size(), pathIndex + 3); li++) {
                var la = path.get(li);
                var belowLa = la.below();
                var laHasGroundBelow = !mob.level()
                    .getBlockState(belowLa)
                    .getCollisionShape(mob.level(), belowLa)
                    .isEmpty();
                if (
                    CrawlingCustomAStar.tunnelCanStandAt(mob.level(), mob, la)
                        || (!laHasGroundBelow && CrawlingCustomAStar.verticalShaftCanCrawlAt(mob.level(), mob, la))
                        || (MovementUtils.isSafeClimbNode(mob.level(), la)
                            && !CustomAStar.canStandAt(mob.level(), mob, la))
                ) {
                    nextWaypointNeedsCrawl = true;
                    break;
                }
            }
        }

        if (mobOnSolidGround && !mobIsInOrAtTunnel && !nextWaypointNeedsCrawl) {
            CrawlingMovementManager.setWallCrawling(mob, false);
        }

        var isCrawlingNow = canCrawl && CrawlingMovementManager.isWallCrawling(mob);

        if (
            canCrawl
                && isCrawlingNow
                && !mobOnSolidGround
                && !mobIsInOrAtTunnel
                && !nextWaypointNeedsCrawl
                && !mob.horizontalCollision
                && !MovementUtils.isSafeClimbNode(mob.level(), mobFeetPos)
        ) {
            CrawlingMovementManager.setWallCrawling(mob, false);
            if (mob instanceof WallCrawlingMob wc) {
                wc.ovomorphosis$setWallCrawlGraceTicks(0);
            }
            isCrawlingNow = false;
        }

        var shouldUseCrawlingNow = canCrawl && (isCrawlingNow
            || mobIsInOrAtTunnel
            || nextWaypointNeedsCrawl);

        var repathInterval = isCrawlingNow ? 60 : 30;

        var destinationMovedFar = lastPathedDestination == null
            || lastPathedDestination.distSqr(destination) > 4;

        if ((repathCooldown <= 0 && destinationMovedFar) || path.isEmpty() || pathIndex >= path.size()) {
            lastPathedDestination = destination;

            final var searchStart = pathStart;
            final var searchGoal = destination;

            List<BlockPos> newPath;

            if (CommonMod.getConfig().enableIncrementalPathfinding) {
                var stale = phasedSession != null
                    && (phasedSessionStart.distSqr(searchStart) > PATH_SESSION_START_DRIFT_SQ
                        || phasedSessionGoal.distSqr(searchGoal) > PATH_SESSION_GOAL_DRIFT_SQ
                        || phasedSessionAgeTicks > PATH_SESSION_MAX_AGE_TICKS);

                if (phasedSession == null || stale) {
                    sessionCache.clear();
                    phasedSession = new PhasedPathSession(
                        buildPhases(mob, searchStart, searchGoal, fluidGoalRadius)
                    );
                    phasedSessionStart = searchStart;
                    phasedSessionGoal = searchGoal;
                    phasedSessionAgeTicks = 0;
                }

                phasedSessionAgeTicks++;
                var status = phasedSession.step(CommonMod.getConfig().incrementalPathfindingNodeBudget);

                newPath = switch (status) {
                    case RUNNING -> null;
                    case DONE -> phasedSession.result();
                    case FAILED -> Collections.emptyList();
                };

                if (status != PhasedPathSession.Status.RUNNING) {
                    phasedSession = null;
                }
            } else {
                if (canCrawl) {
                    newPath = CrawlingCustomAStar.findPath(mob, searchStart, searchGoal, 96, fluidGoalRadius);
                    if (newPath.isEmpty()) {
                        newPath = CrawlingCustomAStar
                            .findPath(mob, searchStart, searchGoal, 96, Math.max(fluidGoalRadius, 1));
                    }
                    if (newPath.isEmpty()) {
                        newPath = CustomAStar.findPath(mob, searchStart, searchGoal, 64, Math.max(fluidGoalRadius, 1));
                    }
                } else {
                    newPath = CustomAStar.findPath(mob, searchStart, searchGoal, 64, Math.max(fluidGoalRadius, 1));
                }
            }

            if (newPath != null) {
                path = newPath;
                pathIndex = path.size() > 1 ? 1 : 0;
                repathCooldown = repathInterval;

                if (isCrawlingNow && pathIndex < path.size()) {
                    while (pathIndex < path.size() && hasReachedWaypoint(mob, path.get(pathIndex), true)) {
                        pathIndex++;
                    }
                }
            }
        }

        if (!path.isEmpty()) {
            while (
                pathIndex < path.size()
                    && hasReachedWaypoint(mob, path.get(pathIndex), shouldUseCrawlingNow)
            ) {
                pathIndex++;
            }

            if (pathIndex < path.size()) {
                var waypointBlock = path.get(pathIndex);
                var waypointCenter = Vec3.atBottomCenterOf(waypointBlock);

                var waypointIsTightPassage =
                    CrawlingCustomAStar.tunnelCanStandAt(mob.level(), mob, waypointBlock)
                        || CrawlingCustomAStar.verticalShaftCanCrawlAt(mob.level(), mob, waypointBlock)
                        || CrawlingCustomAStar.verticalShaftCanCrawlAt(mob.level(), mob, waypointBlock.below())
                        || CrawlingCustomAStar.verticalShaftCanCrawlAt(mob.level(), mob, waypointBlock.below(2));

                var approachingTunnel = waypointIsTightPassage;
                if (!approachingTunnel) {
                    for (var lookahead = pathIndex; lookahead < Math.min(path.size(), pathIndex + 4); lookahead++) {
                        var la = path.get(lookahead);
                        if (
                            CrawlingCustomAStar.tunnelCanStandAt(mob.level(), mob, la)
                                || CrawlingCustomAStar.verticalShaftCanCrawlAt(mob.level(), mob, la)
                        ) {
                            approachingTunnel = true;
                            break;
                        }
                    }
                }

                var tunnelTargetBlock = waypointBlock;
                if (approachingTunnel && !waypointIsTightPassage) {
                    for (var lookahead = pathIndex; lookahead < Math.min(path.size(), pathIndex + 4); lookahead++) {
                        var la = path.get(lookahead);
                        if (
                            CrawlingCustomAStar.tunnelCanStandAt(mob.level(), mob, la)
                                || CrawlingCustomAStar.verticalShaftCanCrawlAt(mob.level(), mob, la)
                        ) {
                            tunnelTargetBlock = la;
                            break;
                        }
                    }
                }

                var waypoint = shouldUseCrawlingNow && !waypointIsTightPassage
                    ? snapToNearestWallFace(mob, waypointBlock, waypointCenter)
                    : waypointCenter;

                var direction = waypoint.subtract(mob.position());

                if ((waypointIsTightPassage || approachingTunnel) && mobOnSolidGround) {
                    var tunnelCenter = Vec3.atBottomCenterOf(tunnelTargetBlock);
                    var horiz = new Vec3(tunnelCenter.x - mob.getX(), 0, tunnelCenter.z - mob.getZ());
                    if (horiz.lengthSqr() > 0.09D) {
                        var toEntrance = horiz.normalize();
                        var move = getTunnelEntryMove(mob, toEntrance, tunnelCenter);

                        var entranceYError = tunnelCenter.y - mob.getY();
                        var closeEnoughToClimb = horiz.lengthSqr() < 2.25D;
                        if (canCrawl && entranceYError > 0.25D && closeEnoughToClimb) {
                            var climbY = Mth.clamp(entranceYError * 0.6D, speed * 0.5D, speed);
                            move = new Vec3(move.x, climbY, move.z);
                            CrawlingMovementManager.setWallCrawling(mob, true);
                            CrawlingMovementManager.updateCrawlOrientation(mob, move);
                        } else if (canCrawl) {
                            if (closeEnoughToClimb) {
                                CrawlingMovementManager.setWallCrawling(mob, true);
                                CrawlingMovementManager.updateCrawlOrientation(mob, move);
                            } else if (mob instanceof WallCrawlingMob wc) {
                                wc.ovomorphosis$setWallCrawlGraceTicks(0);
                            }
                        }
                        mob.setDeltaMovement(move);
                        mob.hasImpulse = true;
                        faceMovementDirection(mob, move);
                        return ActionOutcome.RUNNING;
                    } else if (waypointIsTightPassage) {
                        pathIndex++;
                        if (pathIndex < path.size()) {
                            waypointBlock = path.get(pathIndex);
                            waypointCenter = Vec3.atBottomCenterOf(waypointBlock);
                            waypoint = waypointCenter;
                            direction = waypoint.subtract(mob.position());
                        }
                    }
                }

                if (direction.lengthSqr() > 0.0001D) {
                    applyPathMovement(
                        blackboard,
                        mob,
                        destination,
                        waypointBlock,
                        waypoint,
                        direction,
                        shouldUseCrawlingNow
                    );
                    if (pendingOutcome != null) {
                        var outcome = pendingOutcome;
                        pendingOutcome = null;
                        return outcome;
                    }
                    return ActionOutcome.RUNNING;
                }
            }
        }

        var directDirection = destVec.subtract(mob.position());
        if (directDirection.lengthSqr() > 0.0001D) {
            applyFlatFallback(blackboard, mob, destination, directDirection);
            if (pendingOutcome != null) {
                var outcome = pendingOutcome;
                pendingOutcome = null;
                return outcome;
            }
            return ActionOutcome.RUNNING;
        }

        halt(mob);
        faceDestination(mob, destination);
        return ActionOutcome.RUNNING;
    }

    @Override
    public void stop(E mob, Blackboard blackboard, Cooldowns cooldowns, ActionStatus reason) {
        if (reason == ActionStatus.SUCCESS || reason == ActionStatus.FAILURE) {
            blackboard.remove(AiKeys.DESTINATION);
        }
        if (canCrawl) {
            CrawlingMovementManager.setWallCrawling(mob, false);
        }
        mob.setDeltaMovement(
            mob.getDeltaMovement().x * 0.25D,
            mob.getDeltaMovement().y,
            mob.getDeltaMovement().z * 0.25D
        );
    }

    @Override
    public boolean isInterruptible() {
        return true;
    }

    @Override
    public int priority() {
        return priority;
    }

    private void applyPathMovement(
        Blackboard blackboard,
        E mob,
        BlockPos destination,
        BlockPos waypointBlock,
        Vec3 waypoint,
        Vec3 direction,
        boolean shouldUseCrawlingNow
    ) {
        var movedSqr = mob.position().distanceToSqr(lastPos);
        lastPos = mob.position();

        if (movedSqr < 0.0025D)
            stuckTicks++;
        else
            stuckTicks = 0;

        var currentDistSqToDest = mob.distanceToSqr(Vec3.atBottomCenterOf(destination));
        if (currentDistSqToDest < lastDistSqToDestination - 0.1D) {
            lastDistSqToDestination = currentDistSqToDest;
            noProgressTicks = 0;
        } else {
            noProgressTicks++;
        }

        if (noProgressTicks >= HARD_NO_PROGRESS_TICKS) {
            pendingOutcome = ActionOutcome.failed(PlanFailureReason.FAILED_STUCK, mob.blockPosition());
            return;
        }

        if (noProgressTicks >= SOFT_NO_PROGRESS_TICKS && pendingOutcome == null) {
            pendingOutcome = ActionOutcome.blocked(PlanFailureReason.FAILED_STUCK, mob.blockPosition());
        }

        var mobFeet = BlockPos.containing(mob.getX(), mob.getBoundingBox().minY, mob.getZ());

        var waypointIsVerticalShaft =
            CrawlingCustomAStar.verticalShaftCanCrawlAt(mob.level(), mob, waypointBlock);
        var waypointLeadsIntoVerticalShaft =
            waypointIsVerticalShaft
                || CrawlingCustomAStar.verticalShaftCanCrawlAt(mob.level(), mob, waypointBlock.below())
                || CrawlingCustomAStar.verticalShaftCanCrawlAt(mob.level(), mob, waypointBlock.below(2));

        if (shouldUseCrawlingNow) {
            var waypointIsTopSurface = CustomAStar.canStandAt(mob.level(), mob, waypointBlock)
                && !MovementUtils.isSafeClimbNode(mob.level(), waypointBlock)
                && waypointBlock.getY() > mobFeet.getY();
            var horizToWp = new Vec3(waypoint.x - mob.getX(), 0.0D, waypoint.z - mob.getZ());
            if (
                waypointIsTopSurface
                    && waypoint.y > mob.getY() + 0.05D
                    && waypoint.y <= mob.getY() + 1.4D
                    && horizToWp.lengthSqr() < 1.6D
            ) {
                var overDir = horizToWp.lengthSqr() > 1.0e-4D ? horizToWp.normalize() : Vec3.ZERO;
                var crest = new Vec3(overDir.x * speed, Math.max(speed * 0.6D, 0.3D), overDir.z * speed);
                CrawlingMovementManager.setWallCrawling(mob, true);
                CrawlingMovementManager.updateCrawlOrientation(mob, crest);
                mob.setDeltaMovement(crest);
                mob.hasImpulse = true;
                faceMovementDirection(mob, crest);
                return;
            }
        }

        var verticalStepUp = canCrawl && shouldUseCrawlingNow && needsCrawlStepUp(mob, waypointBlock, mobFeet);
        if (verticalStepUp) {
            var climbTarget = findUpcomingHigherPathNode(mobFeet);
            if (climbTarget == null)
                climbTarget = waypointBlock;

            var climbCenter = Vec3.atBottomCenterOf(climbTarget);
            var horizontalToClimb = new Vec3(climbCenter.x - mob.getX(), 0.0D, climbCenter.z - mob.getZ());

            Vec3 move;
            if (horizontalToClimb.lengthSqr() > 0.01D) {
                var horiz = horizontalToClimb.normalize().scale(speed * 0.75D);
                var corrected = removeBlockedHorizontalComponents(
                    mob,
                    new Vec3(horiz.x, Math.max(mob.getDeltaMovement().y, speed * 0.85D), horiz.z)
                );
                if (corrected.horizontalDistanceSqr() < 0.0001D) {
                    corrected = findCornerEscapeStepUpVelocity(mob, horizontalToClimb);
                }
                move = corrected;
            } else {
                var wallDir = findNearestWallDirection(mob);
                var intoWall = wallDir != null ? wallDir.scale(speed * 0.3D) : Vec3.ZERO;
                move = new Vec3(intoWall.x, Math.max(mob.getDeltaMovement().y, speed * 0.85D), intoWall.z);
            }

            CrawlingMovementManager.setWallCrawling(mob, true);
            CrawlingMovementManager.updateCrawlOrientation(mob, move);
            mob.setDeltaMovement(move);
            mob.hasImpulse = true;
            faceMovementDirection(mob, move);
            return;
        }

        if (waypointLeadsIntoVerticalShaft && destination.getY() < mob.getY() - 0.75D) {
            var shaftBlock = waypointIsVerticalShaft
                ? waypointBlock
                : CrawlingCustomAStar.verticalShaftCanCrawlAt(mob.level(), mob, waypointBlock.below())
                    ? waypointBlock.below()
                    : waypointBlock.below(2);

            var shaftCenter = Vec3.atBottomCenterOf(shaftBlock);
            var centerError = new Vec3(shaftCenter.x - mob.getX(), 0.0D, shaftCenter.z - mob.getZ());
            var centerDistSqr = centerError.lengthSqr();

            Vec3 shaftVelocity;
            if (centerDistSqr > 0.04D) {
                var centerMove = centerError.normalize().scale(speed * 0.65D);
                var yPull = mob.isNoGravity() ? -speed * 0.3D : mob.getDeltaMovement().y * 0.25D;
                shaftVelocity = new Vec3(centerMove.x, yPull, centerMove.z);
            } else {
                if (mob instanceof WallCrawlingMob wc)
                    wc.ovomorphosis$setWallCrawlGraceTicks(0);
                CrawlingMovementManager.setWallCrawling(mob, false);
                shaftVelocity = new Vec3(centerError.x * 0.35D, -speed * 0.85D, centerError.z * 0.35D);
            }

            CrawlingMovementManager.setWallCrawling(mob, true);
            CrawlingMovementManager.updateCrawlOrientation(mob, shaftVelocity);
            mob.setDeltaMovement(shaftVelocity);
            mob.hasImpulse = true;
            faceMovementDirection(mob, shaftVelocity);
            return;
        }

        var waypointIsTunnel = CrawlingCustomAStar.tunnelCanStandAt(mob.level(), mob, waypointBlock);
        var mobIsInTunnel =
            CrawlingCustomAStar.tunnelCanStandAt(mob.level(), mob, mobFeet)
                || CrawlingCustomAStar.tunnelCanStandAt(mob.level(), mob, mobFeet.below())
                || CrawlingCustomAStar.tunnelCanStandAt(mob.level(), mob, mobFeet.above())
                || CrawlingCustomAStar.tunnelCanStandAt(mob.level(), mob, mobFeet.below(2))
                || CrawlingCustomAStar.verticalShaftCanCrawlAt(mob.level(), mob, mobFeet)
                || CrawlingCustomAStar.verticalShaftCanCrawlAt(mob.level(), mob, mobFeet.below())
                || CrawlingCustomAStar.verticalShaftCanCrawlAt(mob.level(), mob, mobFeet.below(2));

        if (waypointIsTunnel || mobIsInTunnel) {
            var horizontal = new Vec3(direction.x, 0.0D, direction.z);
            var yError = waypoint.y - mob.getY();

            if (horizontal.lengthSqr() > 0.0001D) {
                var centerMove = horizontal.normalize().scale(speed);
                double vertical;
                if (yError > 0.20D) {
                    vertical = Mth.clamp(yError * 0.35D, 0.08D, 0.32D);
                } else if (yError < -0.20D) {
                    vertical = Mth.clamp(yError * 0.35D, -0.32D, -0.04D);
                } else {
                    vertical = mob.getDeltaMovement().y * 0.20D;
                }

                var descendingInTunnel = yError < -0.20D;
                CrawlingMovementManager.setWallCrawling(mob, !descendingInTunnel);

                var tunnelSpeed = waypointIsTunnel ? speed : speed * 1.25D;
                var tunnelVelocity = new Vec3(
                    centerMove.normalize().x * tunnelSpeed,
                    vertical,
                    centerMove.normalize().z * tunnelSpeed
                );

                if (!descendingInTunnel)
                    CrawlingMovementManager.updateCrawlOrientation(mob, tunnelVelocity);
                mob.setDeltaMovement(tunnelVelocity);
                mob.hasImpulse = true;
                faceMovementDirection(mob, tunnelVelocity);
                return;
            }

            if (Math.abs(yError) > 0.20D) {
                var vertical = Mth.clamp(yError * 0.35D, -0.32D, 0.32D);
                var tunnelVelocity = new Vec3(0.0D, vertical, 0.0D);
                var descending = yError < -0.20D;
                CrawlingMovementManager.setWallCrawling(mob, !descending);
                if (descending && mob instanceof WallCrawlingMob wc) {
                    wc.ovomorphosis$setWallCrawlGraceTicks(0);
                    vertical = Math.min(vertical, -speed * 0.6D);
                    tunnelVelocity = new Vec3(0.0D, vertical, 0.0D);
                }
                CrawlingMovementManager.updateCrawlOrientation(mob, tunnelVelocity);
                mob.setDeltaMovement(tunnelVelocity);
                mob.hasImpulse = true;
                faceDestination(mob, destination);
                return;
            }
        }

        var waypointIsGroundOnly = CustomAStar.canStandAt(mob.level(), mob, waypointBlock)
            && !MovementUtils.isSafeClimbNode(mob.level(), waypointBlock)
            && waypointBlock.getY() <= mobFeet.getY();
        var canAttachToWall = shouldUseCrawlingNow
            && !waypointIsGroundOnly
            && canAttachToClimbSurface(mob, waypoint);

        if (canAttachToWall) {
            var waypointY = waypoint.y;
            var destAbove = destination.getY() > mob.getY() + 0.75D;
            var yError = waypointY - mob.getY();
            var horizontal = new Vec3(direction.x, 0.0D, direction.z);
            var wallNudge = findNearestWallDirection(mob);
            var intoWall = wallNudge != null ? wallNudge.scale(speed * 0.35D) : Vec3.ZERO;

            Vec3 crawlVelocity;
            if (destAbove) {
                crawlVelocity = new Vec3(intoWall.x, speed * 0.85D, intoWall.z);
            } else if (yError < -0.10D) {
                var verticalSpeed = Mth.clamp(yError * 0.45D, -speed, -speed * 0.15D);
                if (horizontal.lengthSqr() > 0.01D) {
                    var moveDir = horizontal.normalize().scale(speed);
                    crawlVelocity = new Vec3(moveDir.x + intoWall.x, verticalSpeed, moveDir.z + intoWall.z);
                } else {
                    crawlVelocity = new Vec3(intoWall.x, verticalSpeed, intoWall.z);
                    if (crawlVelocity.horizontalDistanceSqr() < 0.0001D) {
                        CrawlingMovementManager.setWallCrawling(mob, false);
                        faceDestination(mob, destination);
                        return;
                    }
                }
            } else if (yError > 0.10D) {
                var verticalSpeed = Mth.clamp(yError * 0.45D, 0.0D, speed);
                if (horizontal.lengthSqr() > 0.01D) {
                    var moveDir = horizontal.normalize().scale(speed);
                    crawlVelocity = new Vec3(moveDir.x + intoWall.x, verticalSpeed, moveDir.z + intoWall.z);
                } else {
                    crawlVelocity = new Vec3(intoWall.x, verticalSpeed, intoWall.z);
                }
            } else if (horizontal.lengthSqr() > 0.01D) {
                var moveDir = horizontal.normalize().scale(speed);
                crawlVelocity = new Vec3(moveDir.x + intoWall.x, intoWall.y, moveDir.z + intoWall.z);
            } else {
                crawlVelocity = MovementUtils.computeWallCrawlVelocity(mob, waypoint, speed);
            }

            if (crawlVelocity.lengthSqr() < 1e-6D)
                crawlVelocity = Vec3.ZERO;

            CrawlingMovementManager.setWallCrawling(mob, true);
            CrawlingMovementManager.updateCrawlOrientation(mob, crawlVelocity);
            mob.setDeltaMovement(crawlVelocity);
            mob.hasImpulse = true;
            faceMovementDirection(mob, crawlVelocity);
            return;
        }

        if (canCrawl)
            CrawlingMovementManager.setWallCrawling(mob, false);

        if (shouldUseCrawlingNow) {
            var toWaypoint = new Vec3(direction.x, 0.0D, direction.z);
            if (toWaypoint.lengthSqr() > 0.01D) {
                CrawlingMovementManager.setWallCrawling(mob, false);
                if (mob instanceof WallCrawlingMob wc)
                    wc.ovomorphosis$setWallCrawlGraceTicks(0);
                var move = toWaypoint.normalize().scale(speed);
                var yVel = Math.min(mob.getDeltaMovement().y, -0.15D);
                mob.setDeltaMovement(move.x, yVel, move.z);
                mob.hasImpulse = true;
                faceMovementDirection(mob, move);
                return;
            }
        }

        var horizontal = new Vec3(direction.x, 0.0D, direction.z);

        if (horizontal.lengthSqr() < 0.01D) {
            if (shouldUseCrawlingNow && direction.y > 0.1D) {
                var wallDir = findNearestWallDirection(mob);
                if (wallDir != null) {
                    CrawlingMovementManager.setWallCrawling(mob, true);
                    var pushVelocity = new Vec3(wallDir.x * speed * 0.3D, speed * 0.6D, wallDir.z * speed * 0.3D);
                    CrawlingMovementManager.updateCrawlOrientation(mob, pushVelocity);
                    mob.setDeltaMovement(pushVelocity);
                    mob.hasImpulse = true;
                    faceDestination(mob, destination);
                    return;
                }
            }
            halt(mob);
            faceDestination(mob, destination);
            return;
        }

        if (!shouldUseCrawlingNow && waypointBlock.getY() < mob.blockPosition().getY()) {
            var toGoal = new Vec3(direction.x, 0.0D, direction.z);
            if (toGoal.lengthSqr() > 0.01D) {
                if (canCrawl && mob instanceof WallCrawlingMob wc)
                    wc.ovomorphosis$setWallCrawlGraceTicks(0);
                var stepDown = toGoal.normalize().scale(speed);
                mob.setDeltaMovement(stepDown.x, Math.min(mob.getDeltaMovement().y, -0.15D), stepDown.z);
                mob.hasImpulse = true;
                faceMovementDirection(mob, stepDown);
                return;
            }
        }

        var forward = horizontal.normalize();
        var movement = MovementUtils.steerAwayFromDangerEntities(mob, forward.scale(speed));

        if (detourTicks > 0) {
            detourTicks--;
            var detourMove = detourDirection.scale(speed);
            var detourSafe = MovementUtils.findSafeMovement(mob, detourMove, steerBias);
            if (!detourSafe.equals(Vec3.ZERO)) {
                mob.setDeltaMovement(detourSafe.x, mob.getDeltaMovement().y, detourSafe.z);
                mob.hasImpulse = true;
                faceDestination(mob, destination);
                return;
            }
            detourTicks = 0;
        }

        if (stuckTicks > 10) {
            if (
                shouldUseCrawlingNow
                    && CrawlingMovementManager.isWallCrawling(mob)
                    && destination.getY() < mob.getY() - 1.0D
                    && waypointBlock.getY() < mob.blockPosition().getY()
            ) {
                CrawlingMovementManager.setWallCrawling(mob, false);
                var downForward = new Vec3(direction.x, 0.0D, direction.z);
                if (downForward.lengthSqr() > 0.0001D)
                    downForward = downForward.normalize().scale(speed * 0.6D);
                mob.setDeltaMovement(downForward.x, -Math.max(0.18D, speed * 0.45D), downForward.z);
                mob.hasImpulse = true;
                stuckTicks = 0;
                repathCooldown = 0;
                if (mob instanceof WallCrawlingMob wc)
                    wc.ovomorphosis$setWallCrawlGraceTicks(0);
                faceDestination(mob, destination);
                return;
            }

            var destBelow = destination.getY() < mob.getY() - 1.0D;

            if (!destBelow && mob.onGround() && isStairBlockAhead(mob, forward)) {
                mob.setDeltaMovement(forward.x * speed * 0.9D, 0.32D, forward.z * speed * 0.9D);
                mob.hasImpulse = true;
                stuckTicks = 0;
                faceDestination(mob, destination);
                return;
            }

            if (destBelow && stuckTicks > 15) {
                var nudge = new Vec3(direction.x, 0.0D, direction.z);
                if (nudge.lengthSqr() > 0.0001D) {
                    if (mob instanceof WallCrawlingMob wc)
                        wc.ovomorphosis$setWallCrawlGraceTicks(0);
                    mob.setDeltaMovement(
                        nudge.normalize().scale(speed).x,
                        mob.getDeltaMovement().y,
                        nudge.normalize().scale(speed).z
                    );
                    mob.hasImpulse = true;
                    stuckTicks = 0;
                    repathCooldown = 0;
                    faceDestination(mob, destination);
                    return;
                }
            }

            var left = new Vec3(-forward.z, 0.0D, forward.x);
            var right = new Vec3(forward.z, 0.0D, -forward.x);

            if (!destBelow && MovementUtils.isSafeAhead(mob, left, 1.25D)) {
                detourDirection = left;
                detourTicks = 20;
                stuckTicks = 0;
            } else if (!destBelow && MovementUtils.isSafeAhead(mob, right, 1.25D)) {
                detourDirection = right;
                detourTicks = 20;
                stuckTicks = 0;
            } else if (mob.onGround() && destination.getY() >= mob.getY() - 0.5D) {
                mob.setDeltaMovement(movement.x * 0.8D, 0.42D, movement.z * 0.8D);
                mob.hasImpulse = true;
                stuckTicks = 0;
                repathCooldown = 0;
                faceDestination(mob, destination);
                return;
            }
        }

        var safe = MovementUtils.findSafeMovement(mob, movement, steerBias);
        if (safe.equals(Vec3.ZERO)) {
            halt(mob);
            faceDestination(mob, destination);
            return;
        }

        mob.setDeltaMovement(safe.x, mob.getDeltaMovement().y, safe.z);
        mob.hasImpulse = true;
        faceDestination(mob, destination);
    }

    private void applyFlatFallback(Blackboard blackboard, E mob, BlockPos destination, Vec3 direction) {
        var currentDistSqFlat = mob.distanceToSqr(Vec3.atBottomCenterOf(destination));
        if (currentDistSqFlat < lastDistSqToDestination - 0.1D) {
            lastDistSqToDestination = currentDistSqFlat;
            noProgressTicks = 0;
        } else {
            noProgressTicks++;
        }

        if (noProgressTicks >= HARD_NO_PROGRESS_TICKS) {
            pendingOutcome = ActionOutcome.failed(PlanFailureReason.FAILED_STUCK, mob.blockPosition());
            return;
        }

        if (noProgressTicks >= SOFT_NO_PROGRESS_TICKS && pendingOutcome == null) {
            pendingOutcome = ActionOutcome.blocked(PlanFailureReason.FAILED_STUCK, mob.blockPosition());
        }

        var horizontal = new Vec3(direction.x, 0.0D, direction.z);
        if (horizontal.lengthSqr() > 0.01D) {
            var movement = MovementUtils.steerAwayFromDangerEntities(mob, horizontal.normalize().scale(speed));
            var safe = MovementUtils.findSafeMovement(mob, movement, steerBias);
            if (!safe.equals(Vec3.ZERO)) {
                mob.setDeltaMovement(safe.x, mob.getDeltaMovement().y, safe.z);
                mob.hasImpulse = true;
            } else {
                halt(mob);
            }
        } else {
            halt(mob);
        }

        faceDestination(mob, destination);
    }

    /**
     * Builds the fallback chain of {@link PhasedPathSession.Phase}s tried for a repath toward a plain destination (as
     * opposed to a moving target — see {@code MoveToTargetAction#buildPhases} for the richer tunnel-entry-aware
     * version), all sharing one per-tick node budget: for a crawling mob, the primary crawl-aware route, then a
     * relaxed-goal-radius crawl route, then a plain ground A* route as a last resort; for a non-crawling mob, just the
     * plain ground A* route.
     */
    private List<PhasedPathSession.Phase> buildPhases(
        E mob,
        BlockPos searchStart,
        BlockPos searchGoal,
        int fluidGoalRadius
    ) {
        if (!canCrawl) {
            return List.of(
                new PhasedPathSession.Phase(
                    "NORMAL_ASTAR",
                    () -> IncrementalPathSession
                        .normal(mob, searchStart, searchGoal, 64, Math.max(fluidGoalRadius, 1))
                )
            );
        }

        List<PhasedPathSession.Phase> phases = new ArrayList<>(3);

        phases.add(
            new PhasedPathSession.Phase(
                "PRIMARY_CRAWL",
                () -> IncrementalPathSession.crawling(mob, searchStart, searchGoal, 96, fluidGoalRadius, sessionCache)
            )
        );

        phases.add(
            new PhasedPathSession.Phase(
                "RELAXED_CRAWL",
                () -> IncrementalPathSession
                    .crawling(mob, searchStart, searchGoal, 96, Math.max(fluidGoalRadius, 1), sessionCache)
            )
        );

        phases.add(
            new PhasedPathSession.Phase(
                "NORMAL_ASTAR",
                () -> IncrementalPathSession
                    .normal(mob, searchStart, searchGoal, 64, Math.max(fluidGoalRadius, 1))
            )
        );

        return phases;
    }

    private boolean hasReachedWaypoint(E mob, BlockPos waypoint, boolean shouldUseCrawling) {
        var waypointCenter = Vec3.atBottomCenterOf(waypoint);

        if (!shouldUseCrawling) {
            var dx = mob.getX() - waypointCenter.x;
            var dz = mob.getZ() - waypointCenter.z;
            var horizontalDistSqr = dx * dx + dz * dz;
            var yError = waypointCenter.y - mob.getY();
            return horizontalDistSqr < 1.0D && yError > -2.0D && yError < 1.5D;
        }

        var dx = mob.getX() - waypointCenter.x;
        var dz = mob.getZ() - waypointCenter.z;
        var horizontalDistSqr = dx * dx + dz * dz;
        var yError = waypointCenter.y - mob.getY();

        var level = mob.level();
        var isTunnel = CrawlingCustomAStar.tunnelCanStandAt(level, mob, waypoint);
        var isVerticalShaft = CrawlingCustomAStar.verticalShaftCanCrawlAt(level, mob, waypoint);

        if (isVerticalShaft) {
            return horizontalDistSqr <= 0.40D * 0.40D && Math.abs(yError) < 0.55D;
        }

        var waypointAbove = waypoint.getY() > BlockPos.containing(
            mob.getX(),
            mob.getBoundingBox().minY,
            mob.getZ()
        ).getY();

        var reachRadius = isTunnel ? 0.6D : waypointAbove ? 0.45D : 1.15D;
        if (horizontalDistSqr > reachRadius * reachRadius)
            return false;
        if (yError > 0.45D)
            return false;
        return !(yError < -1.0D);
    }

    /**
     * A single-block rise onto walkable ground is an ordinary step-up (a hill/slope/stair) that normal ground movement
     * and the stair-jump recovery handle. It must NOT engage the gravity-suppressed wall-climb: with gravity off there
     * is nothing to arrest the forced upward velocity, so on open slopes the mob overshoots, cannot settle, and
     * oscillates/floats in the air. Only rises of 2+ blocks, or nodes with no walkable floor (true wall-cling nodes),
     * count as needing a vertical climb.
     */
    private boolean isOrdinaryStepUp(E mob, BlockPos node, BlockPos mobFeet) {
        return node.getY() - mobFeet.getY() == 1
            && CustomAStar.canStandAt(mob.level(), mob, node);
    }

    private boolean needsCrawlStepUp(E mob, BlockPos waypointBlock, BlockPos mobFeet) {
        if (waypointBlock.getY() > mobFeet.getY() && !isOrdinaryStepUp(mob, waypointBlock, mobFeet))
            return true;
        if (path == null || path.isEmpty())
            return false;
        var maxLookahead = Math.min(path.size(), pathIndex + 4);
        for (var i = pathIndex; i < maxLookahead; i++) {
            var candidate = path.get(i);
            if (candidate.getY() > mobFeet.getY() && !isOrdinaryStepUp(mob, candidate, mobFeet)) {
                var dx = candidate.getX() + 0.5D - mob.getX();
                var dz = candidate.getZ() + 0.5D - mob.getZ();
                return (dx * dx + dz * dz) < 4.0D;
            }
        }
        return false;
    }

    private BlockPos findUpcomingHigherPathNode(BlockPos mobFeet) {
        if (path == null || path.isEmpty())
            return null;
        var currentY = mobFeet.getY();
        var maxLookahead = Math.min(path.size(), pathIndex + 4);
        for (var i = pathIndex; i < maxLookahead; i++) {
            var candidate = path.get(i);
            if (candidate.getY() > currentY)
                return candidate;
        }
        return null;
    }

    private Vec3 snapToNearestWallFace(E mob, BlockPos block, Vec3 center) {
        var level = mob.level();
        var faceOffset = 0.5D - mob.getBbWidth() / 2.0D;
        var pushX = 0.0D;
        var pushZ = 0.0D;
        var solidFaces = 0;
        if (!level.getBlockState(block.east()).getCollisionShape(level, block.east()).isEmpty()) {
            pushX -= faceOffset;
            solidFaces++;
        }
        if (!level.getBlockState(block.west()).getCollisionShape(level, block.west()).isEmpty()) {
            pushX += faceOffset;
            solidFaces++;
        }
        if (!level.getBlockState(block.south()).getCollisionShape(level, block.south()).isEmpty()) {
            pushZ -= faceOffset;
            solidFaces++;
        }
        if (!level.getBlockState(block.north()).getCollisionShape(level, block.north()).isEmpty()) {
            pushZ += faceOffset;
            solidFaces++;
        }
        return solidFaces == 0 ? center : new Vec3(center.x + pushX, center.y, center.z + pushZ);
    }

    private Vec3 findNearestWallDirection(E mob) {
        var level = mob.level();
        var box = mob.getBoundingBox();
        var probe = (mob.getBbWidth() / 2.0D) + 0.6D;
        var standingBox = box.move(0.0D, 1.0D, 0.0D);
        Vec3 best = null;
        var bestDist = Double.MAX_VALUE;
        for (var dir : new Vec3[] { new Vec3(1, 0, 0), new Vec3(-1, 0, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1) }) {
            var hitCurrent = !level.noCollision(mob, box.move(dir.scale(probe)));
            var hitStanding = !level.noCollision(mob, standingBox.move(dir.scale(probe)));
            if (hitCurrent || hitStanding) {
                var dist = probe;
                for (var d = 0.1D; d <= probe; d += 0.1D) {
                    if (!level.noCollision(mob, box.move(dir.scale(d)))) {
                        dist = d;
                        break;
                    }
                }
                if (dist < bestDist) {
                    bestDist = dist;
                    best = dir;
                }
            }
        }
        return best;
    }

    private boolean canAttachToClimbSurface(E mob, Vec3 waypoint) {
        if (MovementUtils.needsWallCrawl(mob, waypoint))
            return true;
        if (mob.horizontalCollision)
            return true;
        var level = mob.level();
        var box = mob.getBoundingBox();
        var d = (mob.getBbWidth() / 2.0D) + 0.5D;
        if (!level.noCollision(mob, box.move(0, 0, -d)))
            return true;
        if (!level.noCollision(mob, box.move(0, 0, d)))
            return true;
        if (!level.noCollision(mob, box.move(-d, 0, 0)))
            return true;
        if (!level.noCollision(mob, box.move(d, 0, 0)))
            return true;
        if (waypoint.y > mob.getY() + 0.5D) {
            var toWaypoint = new Vec3(waypoint.x - mob.getX(), 0.0D, waypoint.z - mob.getZ());
            if (toWaypoint.lengthSqr() < 0.25D) {
                var sb = box.move(0, 1, 0);
                var sp = (mob.getBbWidth() / 2.0D) + 0.6D;
                if (!level.noCollision(mob, sb.move(sp, 0, 0)))
                    return true;
                if (!level.noCollision(mob, sb.move(-sp, 0, 0)))
                    return true;
                if (!level.noCollision(mob, sb.move(0, 0, sp)))
                    return true;
                return !level.noCollision(mob, sb.move(0, 0, -sp));
            } else if (toWaypoint.lengthSqr() > 0.0001D) {
                var pd = toWaypoint.normalize();
                var pd2 = (mob.getBbWidth() / 2.0D) + 1.0D;
                if (!level.noCollision(mob, box.move(pd.scale(pd2))))
                    return true;
                return !level.noCollision(mob, box.move(0, 1, 0).move(pd.scale(pd2)));
            }
        }
        return false;
    }

    private boolean isStairBlockAhead(E mob, Vec3 forward) {
        var level = mob.level();
        var checkPos = mob.position().add(forward.scale(0.6D));
        var feetY = mob.getBoundingBox().minY;
        var feet = BlockPos.containing(checkPos.x, feetY, checkPos.z);
        var head = feet.above();
        if (level.getBlockState(feet).getCollisionShape(level, feet).isEmpty())
            return false;
        if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty())
            return false;
        var landing = head.above();
        return level.getBlockState(landing).getCollisionShape(level, landing).isEmpty();
    }

    private Vec3 removeBlockedHorizontalComponents(E mob, Vec3 desired) {
        var level = mob.level();
        var box = mob.getBoundingBox();
        var probe = Math.max(0.08D, mob.getBbWidth() * 0.25D);
        var x = desired.x;
        var z = desired.z;
        if (Math.abs(x) > 0.0001D && !level.noCollision(mob, box.move(Math.copySign(probe, x), 0, 0)))
            x = 0.0D;
        if (Math.abs(z) > 0.0001D && !level.noCollision(mob, box.move(0, 0, Math.copySign(probe, z))))
            z = 0.0D;
        return new Vec3(x, desired.y, z);
    }

    private Vec3 findCornerEscapeStepUpVelocity(E mob, Vec3 desiredHorizontal) {
        var level = mob.level();
        var box = mob.getBoundingBox();
        var desired = new Vec3(desiredHorizontal.x, 0.0D, desiredHorizontal.z);
        if (desired.lengthSqr() < 0.0001D)
            desired = new Vec3(mob.getLookAngle().x, 0.0D, mob.getLookAngle().z);
        if (desired.lengthSqr() < 0.0001D)
            return new Vec3(0.0D, speed * 0.85D, 0.0D);
        desired = desired.normalize();
        var candidates = new Vec3[] {
            new Vec3(desired.x, 0, desired.z),
            new Vec3(-desired.z, 0, desired.x),
            new Vec3(desired.z, 0, -desired.x),
            new Vec3(desired.x - desired.z, 0, desired.z + desired.x),
            new Vec3(desired.x + desired.z, 0, desired.z - desired.x)
        };
        Vec3 best = Vec3.ZERO;
        var bestScore = -Double.MAX_VALUE;
        for (var candidate : candidates) {
            if (candidate.lengthSqr() < 0.0001D)
                continue;
            var dir = candidate.normalize();
            var testMove = dir.scale(speed * 0.55D);
            if (!level.noCollision(mob, box.move(testMove.x, 0.05D, testMove.z)))
                continue;
            var score = dir.dot(desired);
            if (score > bestScore) {
                bestScore = score;
                best = testMove;
            }
        }
        return new Vec3(best.x, Math.max(mob.getDeltaMovement().y, speed * 0.9D), best.z);
    }

    private Vec3 getTunnelEntryMove(E mob, Vec3 toEntrance, Vec3 tunnelCenter) {
        var absX = Math.abs(toEntrance.x);
        var absZ = Math.abs(toEntrance.z);
        if (absZ > absX) {
            var xError = tunnelCenter.x - mob.getX();
            var xCorrect = Mth.clamp(xError * 2.0D, -speed * 0.5D, speed * 0.5D);
            return new Vec3(xCorrect, mob.getDeltaMovement().y, Math.copySign(speed, toEntrance.z));
        } else {
            var zError = tunnelCenter.z - mob.getZ();
            var zCorrect = Mth.clamp(zError * 2.0D, -speed * 0.5D, speed * 0.5D);
            return new Vec3(Math.copySign(speed, toEntrance.x), mob.getDeltaMovement().y, zCorrect);
        }
    }

    private void halt(E mob) {
        mob.setDeltaMovement(0.0D, mob.getDeltaMovement().y, 0.0D);
        mob.hasImpulse = false;
    }

    private void faceDestination(E mob, BlockPos destination) {
        var dx = destination.getX() + 0.5 - mob.getX();
        var dz = destination.getZ() + 0.5 - mob.getZ();
        var yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        mob.setYRot(yaw);
        mob.yBodyRot = yaw;
        mob.yHeadRot = yaw;
    }

    private void faceMovementDirection(E mob, Vec3 movement) {
        if (movement.horizontalDistanceSqr() < 0.0001D)
            return;
        var yaw = (float) (Math.atan2(movement.z, movement.x) * (180.0D / Math.PI)) - 90.0F;
        mob.setYRot(yaw);
        mob.yBodyRot = yaw;
        mob.yHeadRot = yaw;
        mob.getLookControl().setLookAt(mob.getX() + movement.x, mob.getEyeY() + movement.y, mob.getZ() + movement.z);
    }
}
