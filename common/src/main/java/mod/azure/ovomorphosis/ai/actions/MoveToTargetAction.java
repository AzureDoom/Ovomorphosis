package mod.azure.ovomorphosis.ai.actions;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

import mod.azure.ovomorphosis.ai.core.*;
import mod.azure.ovomorphosis.ai.util.*;
import mod.azure.ovomorphosis.level.TunnelEntryRegistry;
import mod.azure.ovomorphosis.util.ModTags;

public final class MoveToTargetAction<E extends Mob> implements Action<E> {

    private int dangerLeapCooldown = 0;

    private final double stopDistanceSqr;

    private final double speed;

    private final int priority;

    private final boolean canCrawl;

    private final int[] steerBias = { 0 };

    private Vec3 lastPos = Vec3.ZERO;

    private int stuckTicks = 0;

    private Vec3 detourDirection = Vec3.ZERO;

    private int detourTicks = 0;

    private int blockBreakCooldown = 0;

    private List<BlockPos> path = Collections.emptyList();

    private int pathIndex = 0;

    private int repathCooldown = 0;

    private double lastDistSqToTarget = Double.MAX_VALUE;

    private int noProgressTicks = 0;

    private final PathNodeCache nodeCache = new PathNodeCache();

    private BlockPos cachedTunnelEntry = null;

    private BlockPos tunnelScanOrigin = null;

    private int tunnelRescanCooldown = 0;

    private static final int TUNNEL_RESCAN_TICKS = 20;

    private static final int TUNNEL_SCAN_RADIUS = 32;

    private static final int TUNNEL_SCAN_MIN_DY = -3;

    private static final int TUNNEL_SCAN_MAX_DY = 1;

    public MoveToTargetAction(
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
        blockBreakCooldown = 0;
        dangerLeapCooldown = 0;
        path = Collections.emptyList();
        pathIndex = 0;
        repathCooldown = 0;
        lastDistSqToTarget = Double.MAX_VALUE;
        noProgressTicks = 0;
        nodeCache.clear();
        cachedTunnelEntry = null;
        tunnelScanOrigin = null;
        tunnelRescanCooldown = 0;
    }

    @Override
    public ActionStatus tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        nodeCache.clear();

        if (mob.getHealth() <= 0) {
            mob.setAggressive(false);
            return ActionStatus.INTERRUPTED;
        }

        var target = blackboard.get(AiKeys.TARGET, LivingEntity.class);

        if (target == null || !target.isAlive()) {
            if (!canCrawl || !CrawlingMovementManager.isWallCrawling(mob)) {
                mob.setDeltaMovement(mob.getDeltaMovement().scale(0.5D));
            }
            return ActionStatus.FAILURE;
        }

        var yDiff = target.getY() - mob.getY();
        if (!canCrawl && yDiff > 12.0D) {
            mob.getNavigation().stop();
            return ActionStatus.FAILURE;
        }

        if (mob.distanceToSqr(target) <= stopDistanceSqr && TargetingUtils.hasMeleeLineOfSight(mob, target)) {
            var dangerMove = MovementUtils.steerAwayFromDangerEntities(mob, Vec3.ZERO);

            if (dangerMove.lengthSqr() > 0.0001D) {
                var safe = MovementUtils.findSafeMovement(mob, dangerMove, steerBias);

                if (!safe.equals(Vec3.ZERO)) {
                    mob.setDeltaMovement(safe.x, mob.getDeltaMovement().y, safe.z);
                    mob.hasImpulse = true;
                    faceTarget(mob, target);
                    return ActionStatus.RUNNING;
                }
            }

            mob.setDeltaMovement(mob.getDeltaMovement().scale(0.4D));
            faceTarget(mob, target);
            return ActionStatus.SUCCESS;
        }

        if (repathCooldown > 0) {
            repathCooldown--;
        }

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

        var mobIsInOrAtTunnel = canCrawl
            && (nodeCache.tunnelCanStandAt(mob.level(), mob, mobFeetPos)
                || nodeCache.tunnelCanStandAt(mob.level(), mob, mobFeetPos.below())
                || nodeCache.tunnelCanStandAt(mob.level(), mob, mobFeetPos.above())
                || nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, mobFeetPos)
                || nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, mobFeetPos.below()));

        var nextWaypointIsTunnel = false;
        if (canCrawl && !path.isEmpty() && pathIndex < path.size()) {
            for (var li = pathIndex; li < Math.min(path.size(), pathIndex + 3); li++) {
                var la = path.get(li);
                if (
                    nodeCache.tunnelCanStandAt(mob.level(), mob, la)
                        || nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, la)
                ) {
                    nextWaypointIsTunnel = true;
                    break;
                }
            }
        }
        if (mobOnSolidGround && !mobIsInOrAtTunnel && !nextWaypointIsTunnel) {
            CrawlingMovementManager.setWallCrawling(mob, false);
        }

        var isCrawlingNow = canCrawl && CrawlingMovementManager.isWallCrawling(mob);
        var nearbyTunnelEntry = canCrawl ? findNearbyTunnelEntry(mob) : null;
        if (nearbyTunnelEntry != null && mobOnSolidGround && !mobIsInOrAtTunnel) {
            var toTunnel = Vec3.atBottomCenterOf(nearbyTunnelEntry).subtract(mob.position());
            var toTarget = target.position().subtract(mob.position());
            var t2d = toTunnel.multiply(1, 0, 1);
            var g2d = toTarget.multiply(1, 0, 1);
            if (
                t2d.lengthSqr() < 0.01D || g2d.lengthSqr() < 0.01D
                    || t2d.normalize().dot(g2d.normalize()) <= -0.3D
            ) {
                nearbyTunnelEntry = null;
            }
        }

        var shouldUseCrawlingNow = canCrawl && (isCrawlingNow
            || mobIsInOrAtTunnel
            || (nearbyTunnelEntry != null)
            || CrawlingMovementManager.shouldUseWallCrawlingToTarget(mob, target));

        var tunnelBiasedGoal = (shouldUseCrawlingNow && (!mobOnSolidGround || mobIsInOrAtTunnel
            || nearbyTunnelEntry != null))
                ? findBestTunnelBiasedGoal(mob, target)
                : null;

        var repathInterval = (isCrawlingNow ? 40 : 20) + (mob.getId() & 7);

        if (repathCooldown <= 0 || path.isEmpty() || pathIndex >= path.size()) {
            var crawlGoal = (tunnelBiasedGoal != null) ? tunnelBiasedGoal : target.blockPosition();
            if (
                mob.level().getBlockState(crawlGoal).isAir()
                    && !mob.level().getBlockState(crawlGoal.below()).getFluidState().isEmpty()
            ) {
                crawlGoal = crawlGoal.below();
            }
            var fluidGoalRadius = feetOrBelowInFluid ? 2 : 0;

            if (canCrawl) {
                path = CrawlingCustomAStar.findPath(mob, pathStart, crawlGoal, 96, fluidGoalRadius, nodeCache);
                if (path.isEmpty() && nearbyTunnelEntry != null && !nearbyTunnelEntry.equals(crawlGoal)) {
                    path = CrawlingCustomAStar.findPath(
                        mob,
                        pathStart,
                        nearbyTunnelEntry,
                        96,
                        fluidGoalRadius,
                        nodeCache
                    );
                }
                if (path.isEmpty()) {
                    path = CrawlingCustomAStar.findPath(
                        mob,
                        pathStart,
                        crawlGoal,
                        96,
                        Math.max(fluidGoalRadius, 1),
                        nodeCache
                    );
                }
                if (path.isEmpty()) {
                    path = CustomAStar.findPath(
                        mob,
                        pathStart,
                        target.blockPosition(),
                        64,
                        Math.max(fluidGoalRadius, 1)
                    );
                }
            } else {
                path = CustomAStar.findPath(mob, pathStart, target.blockPosition(), 64, Math.max(fluidGoalRadius, 1));
            }

            pathIndex = path.size() > 1 ? 1 : 0;
            repathCooldown = repathInterval;

            if (isCrawlingNow && pathIndex < path.size()) {
                while (pathIndex < path.size() && hasReachedWaypoint(mob, path.get(pathIndex), true)) {
                    pathIndex++;
                }
            }

            if (!blackboard.has(AiKeys.BREAK_TO_TARGET_TRIGGER) && !path.isEmpty()) {
                checkPathForBreakableWall(mob, blackboard, path, pathIndex);
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
                    nodeCache.tunnelCanStandAt(mob.level(), mob, waypointBlock)
                        || nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, waypointBlock)
                        || nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, waypointBlock.below())
                        || nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, waypointBlock.below(2));

                var approachingTunnel = waypointIsTightPassage;
                if (!approachingTunnel) {
                    for (var lookahead = pathIndex; lookahead < Math.min(path.size(), pathIndex + 4); lookahead++) {
                        var la = path.get(lookahead);
                        if (
                            nodeCache.tunnelCanStandAt(mob.level(), mob, la)
                                || nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, la)
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
                            nodeCache.tunnelCanStandAt(mob.level(), mob, la)
                                || nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, la)
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
                        var move = getMove(mob, toEntrance, tunnelCenter);

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
                        return ActionStatus.RUNNING;
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
                        target,
                        waypointBlock,
                        waypoint,
                        direction,
                        shouldUseCrawlingNow
                    );
                    return ActionStatus.RUNNING;
                }
            }
        }

        var directDirection = target.position().subtract(mob.position());

        if (directDirection.lengthSqr() > 0.0001D) {
            applyFlatFallback(mob, blackboard, target, directDirection);
            return ActionStatus.RUNNING;
        }

        halt(mob);
        faceTarget(mob, target);
        return ActionStatus.RUNNING;
    }

    private @NotNull Vec3 getMove(E mob, Vec3 toEntrance, Vec3 tunnelCenter) {
        var absX = Math.abs(toEntrance.x);
        var absZ = Math.abs(toEntrance.z);
        Vec3 move;
        if (absZ > absX) {
            var xError = tunnelCenter.x - mob.getX();
            var xCorrect = Mth.clamp(xError * 2.0D, -speed * 0.5D, speed * 0.5D);
            move = new Vec3(xCorrect, mob.getDeltaMovement().y, Math.copySign(speed, toEntrance.z));
        } else {
            var zError = tunnelCenter.z - mob.getZ();
            var zCorrect = Mth.clamp(zError * 2.0D, -speed * 0.5D, speed * 0.5D);
            move = new Vec3(Math.copySign(speed, toEntrance.x), mob.getDeltaMovement().y, zCorrect);
        }
        return move;
    }

    @Override
    public void stop(E mob, Blackboard blackboard, Cooldowns cooldowns, ActionStatus reason) {
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
        LivingEntity target,
        BlockPos waypointBlock,
        Vec3 waypoint,
        Vec3 direction,
        boolean shouldUseCrawlingNow
    ) {
        if (blockBreakCooldown > 0)
            blockBreakCooldown--;
        if (dangerLeapCooldown > 0)
            dangerLeapCooldown--;

        var movedSqr = mob.position().distanceToSqr(lastPos);
        lastPos = mob.position();

        if (movedSqr < 0.0025D)
            stuckTicks++;
        else
            stuckTicks = 0;

        var currentDistSq = mob.distanceToSqr(target);
        if (currentDistSq < lastDistSqToTarget - 0.1D) {
            lastDistSqToTarget = currentDistSq;
            noProgressTicks = 0;
        } else {
            noProgressTicks++;
        }

        if (mob.horizontalCollision && blockBreakCooldown <= 0) {
            var forwardDir = new Vec3(direction.x, 0.0D, direction.z);
            if (forwardDir.lengthSqr() > 0.01D) {
                forwardDir = forwardDir.normalize();
                var toTarget = target.position().subtract(mob.position());
                var toTargetH = new Vec3(toTarget.x, 0.0D, toTarget.z);
                if (toTargetH.lengthSqr() > 0.01D && forwardDir.dot(toTargetH.normalize()) > 0.5D) {
                    if (tryBreakBlockingPathBlock(mob, blackboard, target, forwardDir)) {
                        blockBreakCooldown = 10;
                        noProgressTicks = 0;
                        stuckTicks = 0;
                        repathCooldown = 0;
                        faceTarget(mob, target);
                        return;
                    }
                }
            }
        }

        if (noProgressTicks > 5 && blockBreakCooldown <= 0) {
            var forwardDir = new Vec3(direction.x, 0.0D, direction.z);
            if (forwardDir.lengthSqr() > 0.01D) {
                forwardDir = forwardDir.normalize();
                if (tryBreakBlockingPathBlock(mob, blackboard, target, forwardDir)) {
                    blockBreakCooldown = 10;
                    noProgressTicks = 0;
                    stuckTicks = 0;
                    repathCooldown = 0;
                    faceTarget(mob, target);
                    return;
                }
            }
        }

        var waypointIsVerticalShaft =
            nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, waypointBlock);

        var waypointLeadsIntoVerticalShaft =
            waypointIsVerticalShaft
                || nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, waypointBlock.below())
                || nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, waypointBlock.below(2));

        var mobFeet = BlockPos.containing(
            mob.getX(),
            mob.getBoundingBox().minY,
            mob.getZ()
        );
        var mobInFluid = !mob.level().getBlockState(mobFeet).getFluidState().isEmpty();

        var verticalStepUp =
            canCrawl
                && shouldUseCrawlingNow
                && needsCrawlStepUp(mob, waypointBlock, mobFeet);

        if (verticalStepUp) {
            var climbTarget = findUpcomingHigherPathNode(mobFeet);
            if (climbTarget == null) {
                climbTarget = waypointBlock;
            }

            var climbCenter = Vec3.atBottomCenterOf(climbTarget);
            var horizontalToClimb = new Vec3(
                climbCenter.x - mob.getX(),
                0.0D,
                climbCenter.z - mob.getZ()
            );

            Vec3 move;

            if (horizontalToClimb.lengthSqr() > 0.01D) {
                var horiz = horizontalToClimb.normalize().scale(speed * 0.75D);

                var corrected = removeBlockedHorizontalComponents(
                    mob,
                    new Vec3(
                        horiz.x,
                        Math.max(mob.getDeltaMovement().y, speed * 0.85D),
                        horiz.z
                    )
                );

                if (corrected.horizontalDistanceSqr() < 0.0001D) {
                    var raisedBox = mob.getBoundingBox().move(horiz.x, 1.05D, horiz.z);
                    if (mob.level().noCollision(mob, raisedBox)) {
                        corrected = new Vec3(0.0D, Math.max(mob.getDeltaMovement().y, speed * 0.95D), 0.0D);
                    } else {
                        corrected = findCornerEscapeStepUpVelocity(mob, horizontalToClimb);
                    }
                }

                move = corrected;
            } else {
                var wallDir = findNearestWallDirection(mob);
                var intoWall = wallDir != null ? wallDir.scale(speed * 0.3D) : Vec3.ZERO;
                move = new Vec3(
                    intoWall.x,
                    Math.max(mob.getDeltaMovement().y, speed * 0.85D),
                    intoWall.z
                );
            }

            CrawlingMovementManager.setWallCrawling(mob, true);
            CrawlingMovementManager.updateCrawlOrientation(mob, move);
            mob.setDeltaMovement(move);
            mob.hasImpulse = true;
            faceMovementDirection(mob, move);

            return;
        }

        if (waypointLeadsIntoVerticalShaft && target.getY() < mob.getY() - 0.75D) {
            var shaftBlock = waypointIsVerticalShaft
                ? waypointBlock
                : nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, waypointBlock.below())
                    ? waypointBlock.below()
                    : waypointBlock.below(2);

            var shaftCenter = Vec3.atBottomCenterOf(shaftBlock);
            var centerError = new Vec3(
                shaftCenter.x - mob.getX(),
                0.0D,
                shaftCenter.z - mob.getZ()
            );

            var centerDistSqr = centerError.lengthSqr();

            Vec3 shaftVelocity;

            if (centerDistSqr > 0.04D) {
                var centerMove = centerError.normalize().scale(speed * 0.65D);
                var yPull = mob.isNoGravity() ? -speed * 0.3D : mob.getDeltaMovement().y * 0.25D;
                shaftVelocity = new Vec3(
                    centerMove.x,
                    yPull,
                    centerMove.z
                );
            } else {
                if (mob instanceof WallCrawlingMob wc) {
                    wc.ovomorphosis$setWallCrawlGraceTicks(0);
                }
                CrawlingMovementManager.setWallCrawling(mob, false);
                shaftVelocity = new Vec3(
                    centerError.x * 0.35D,
                    -speed * 0.85D,
                    centerError.z * 0.35D
                );
            }

            CrawlingMovementManager.setWallCrawling(mob, true);
            CrawlingMovementManager.updateCrawlOrientation(mob, shaftVelocity);
            mob.setDeltaMovement(shaftVelocity);
            mob.hasImpulse = true;
            faceMovementDirection(mob, shaftVelocity);
            return;
        }

        var waypointIsTunnel =
            nodeCache.tunnelCanStandAt(mob.level(), mob, waypointBlock);

        var mobIsInTunnel =
            nodeCache.tunnelCanStandAt(mob.level(), mob, mobFeet)
                || nodeCache.tunnelCanStandAt(mob.level(), mob, mobFeet.below())
                || nodeCache.tunnelCanStandAt(mob.level(), mob, mobFeet.above())
                || nodeCache.tunnelCanStandAt(mob.level(), mob, mobFeet.below(2))
                || nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, mobFeet)
                || nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, mobFeet.below())
                || nodeCache.verticalShaftCanCrawlAt(mob.level(), mob, mobFeet.below(2));

        if (waypointIsTunnel || mobIsInTunnel) {
            var horizontal = new Vec3(direction.x, 0.0D, direction.z);
            var yError = waypoint.y - mob.getY();

            if (horizontal.lengthSqr() > 0.0001D) {
                var centerMove = horizontal.normalize().scale(speed);

                var vertical = 0.0D;

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

                if (!descendingInTunnel) {
                    CrawlingMovementManager.updateCrawlOrientation(mob, tunnelVelocity);
                }
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
                faceMovementDirection(mob, tunnelVelocity);
                return;
            }
        }

        var waypointIsGroundOnly = nodeCache.canStandAt(mob.level(), mob, waypointBlock)
            && !nodeCache.isSafeClimbNode(mob.level(), waypointBlock)
            && waypointBlock.getY() <= mobFeet.getY();
        var canAttachToWall = shouldUseCrawlingNow
            && !waypointIsGroundOnly
            && canAttachToClimbSurface(mob, waypoint);

        if (canAttachToWall) {
            var waypointY = waypoint.y;
            var targetAbove = target.getY() > mob.getY() + 0.75D;
            var yError = waypointY - mob.getY();
            var horizontal = new Vec3(direction.x, 0.0D, direction.z);

            var wallNudge = findNearestWallDirection(mob);
            var intoWall = wallNudge != null ? wallNudge.scale(speed * 0.35D) : Vec3.ZERO;

            Vec3 crawlVelocity;
            if (targetAbove) {
                crawlVelocity = new Vec3(intoWall.x, speed * 0.85D, intoWall.z);
            } else if (yError < -0.10D) {
                var verticalSpeed = Mth.clamp(yError * 0.45D, -speed, -speed * 0.15D);
                if (horizontal.lengthSqr() > 0.01D) {
                    var moveDir = horizontal.normalize().scale(speed);
                    crawlVelocity = new Vec3(
                        moveDir.x + intoWall.x,
                        verticalSpeed,
                        moveDir.z + intoWall.z
                    );
                } else {
                    crawlVelocity = new Vec3(intoWall.x, verticalSpeed, intoWall.z);
                    if (crawlVelocity.horizontalDistanceSqr() < 0.0001D) {
                        CrawlingMovementManager.setWallCrawling(mob, false);
                        faceTarget(mob, target);
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

        if (canCrawl) {
            CrawlingMovementManager.setWallCrawling(mob, false);
        }

        if (shouldUseCrawlingNow) {
            var toWaypoint = new Vec3(direction.x, 0.0D, direction.z);
            if (toWaypoint.lengthSqr() > 0.01D) {
                CrawlingMovementManager.setWallCrawling(mob, false);
                if (mob instanceof WallCrawlingMob wc) {
                    wc.ovomorphosis$setWallCrawlGraceTicks(0);
                }
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
                    faceMovementDirection(mob, pushVelocity);
                    return;
                }
            }
            halt(mob);
            faceMovementDirection(mob, horizontal);
            return;
        }

        if (!shouldUseCrawlingNow && waypointBlock.getY() < mob.blockPosition().getY()) {
            BlockPos lookAheadBlock = waypointBlock;
            for (var i = pathIndex; i < Math.min(path.size(), pathIndex + 6); i++) {
                var candidate = path.get(i);
                if (candidate.getY() <= mob.blockPosition().getY()) {
                    lookAheadBlock = candidate;
                } else {
                    break;
                }
            }
            var lookAheadCenter = Vec3.atBottomCenterOf(lookAheadBlock);
            var toGoal = new Vec3(lookAheadCenter.x - mob.getX(), 0.0D, lookAheadCenter.z - mob.getZ());
            if (toGoal.lengthSqr() > 0.01D) {
                if (canCrawl && mob instanceof WallCrawlingMob wc) {
                    wc.ovomorphosis$setWallCrawlGraceTicks(0);
                }
                var stepDown = toGoal.normalize().scale(speed);
                var yVel = Math.min(mob.getDeltaMovement().y, -0.15D);

                mob.setDeltaMovement(stepDown.x, yVel, stepDown.z);
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
                faceTarget(mob, target);
                return;
            }
            detourTicks = 0;
        }

        if (stuckTicks > 10) {
            if (
                shouldUseCrawlingNow
                    && CrawlingMovementManager.isWallCrawling(mob)
                    && target.getY() < mob.getY() - 1.0D
                    && waypointBlock.getY() < mob.blockPosition().getY()
            ) {
                CrawlingMovementManager.setWallCrawling(mob, false);

                var downForward = new Vec3(direction.x, 0.0D, direction.z);
                if (downForward.lengthSqr() > 0.0001D) {
                    downForward = downForward.normalize().scale(speed * 0.6D);
                }

                mob.setDeltaMovement(
                    downForward.x,
                    -Math.max(0.18D, speed * 0.45D),
                    downForward.z
                );

                mob.hasImpulse = true;
                stuckTicks = 0;
                repathCooldown = 0;
                faceTarget(mob, target);
                if (mob instanceof WallCrawlingMob wc) {
                    wc.ovomorphosis$setWallCrawlGraceTicks(0);
                }
                return;
            }

            var targetBelow = target.getY() < mob.getY() - 1.0D;

            if (!targetBelow && mob.onGround() && isStairBlockAhead(mob, forward)) {
                mob.setDeltaMovement(forward.x * speed * 0.9D, 0.32D, forward.z * speed * 0.9D);
                mob.hasImpulse = true;
                stuckTicks = 0;
                faceTarget(mob, target);
                return;
            }

            if (targetBelow && blockBreakCooldown <= 0) {
                if (tryBreakBlockingPathBlock(mob, blackboard, target, forward)) {
                    blockBreakCooldown = 10;
                    stuckTicks = 0;
                    repathCooldown = 0;
                    faceTarget(mob, target);
                    return;
                }
            }

            if (targetBelow && stuckTicks > 15) {
                var toTarget = target.position().subtract(mob.position());
                var nudge = new Vec3(toTarget.x, 0.0D, toTarget.z);
                if (nudge.lengthSqr() > 0.0001D) {
                    var walkOff = nudge.normalize().scale(speed);
                    if (mob instanceof WallCrawlingMob wc) {
                        wc.ovomorphosis$setWallCrawlGraceTicks(0);
                    }
                    mob.setDeltaMovement(walkOff.x, mob.getDeltaMovement().y, walkOff.z);
                    mob.hasImpulse = true;
                    stuckTicks = 0;
                    repathCooldown = 0;
                    faceTarget(mob, target);
                    return;
                }
            }

            var left = new Vec3(-forward.z, 0.0D, forward.x);
            var right = new Vec3(forward.z, 0.0D, -forward.x);

            if (!targetBelow && blockBreakCooldown <= 0) {
                if (tryBreakBlockingPathBlock(mob, blackboard, target, forward)) {
                    blockBreakCooldown = 10;
                    stuckTicks = 0;
                    repathCooldown = 0;
                    faceTarget(mob, target);
                    return;
                }
            }

            if (
                stuckTicks >= 8
                    && dangerLeapCooldown <= 0
                    && mob.onGround()
                    && MovementUtils.hasNearbyDangerEntity(mob)
            ) {
                var leapDirection = new Vec3(movement.x, 0.0D, movement.z);
                if (leapDirection.lengthSqr() < 0.0001D)
                    leapDirection = forward;

                if (MovementUtils.hasSafeLandingAfterLeap(mob, leapDirection, 3.0D)) {
                    var leap = leapDirection.normalize().scale(0.75D);
                    mob.setDeltaMovement(leap.x, 0.75D, leap.z);
                    mob.hasImpulse = true;
                    dangerLeapCooldown = 30;
                    stuckTicks = 0;
                    detourTicks = 0;
                    repathCooldown = 0;
                    faceTarget(mob, target);
                    return;
                }
            }

            if (!targetBelow && MovementUtils.isSafeAhead(mob, left, 1.25D)) {
                detourDirection = left;
                detourTicks = 20;
                stuckTicks = 0;
            } else if (!targetBelow && MovementUtils.isSafeAhead(mob, right, 1.25D)) {
                detourDirection = right;
                detourTicks = 20;
                stuckTicks = 0;
            } else if (mob.onGround() && target.getY() >= mob.getY() - 0.5D) {
                mob.setDeltaMovement(movement.x * 0.8D, 0.42D, movement.z * 0.8D);
                mob.hasImpulse = true;
                stuckTicks = 0;
                repathCooldown = 0;
                faceTarget(mob, target);
                return;
            }
        }

        if (mobInFluid) {
            var climbBoost = 0.0D;
            if (mob.horizontalCollision) {
                var aboveFeet = mobFeet.above();
                var headClear = mob.level()
                    .getBlockState(aboveFeet)
                    .getCollisionShape(mob.level(), aboveFeet)
                    .isEmpty();
                if (headClear) {
                    climbBoost = 0.4D;
                }
            }

            var yVel = climbBoost > 0.0D
                ? Math.max(mob.getDeltaMovement().y, climbBoost)
                : mob.getDeltaMovement().y;

            mob.setDeltaMovement(movement.x, yVel, movement.z);
            mob.hasImpulse = true;
            faceTarget(mob, target);
            return;
        }

        var safe = MovementUtils.findSafeMovement(mob, movement, steerBias);

        if (safe.equals(Vec3.ZERO)) {
            var targetBelow = target.getY() < mob.getY() - 1.5D;
            if (targetBelow) {
                var toTarget = target.position().subtract(mob.position());
                var nudge = new Vec3(toTarget.x, 0.0D, toTarget.z);
                if (nudge.lengthSqr() > 0.0001D) {
                    var walkOff = nudge.normalize().scale(speed);
                    if (mob instanceof WallCrawlingMob wc) {
                        wc.ovomorphosis$setWallCrawlGraceTicks(0);
                    }
                    mob.setDeltaMovement(walkOff.x, mob.getDeltaMovement().y, walkOff.z);
                    mob.hasImpulse = true;
                    faceTarget(mob, target);
                    return;
                }
            }
            halt(mob);
            faceTarget(mob, target);
            return;
        }

        mob.setDeltaMovement(safe.x, mob.getDeltaMovement().y, safe.z);
        mob.hasImpulse = true;
        faceTarget(mob, target);
    }

    private void applyFlatFallback(E mob, Blackboard blackboard, LivingEntity target, Vec3 direction) {
        var movedSqr = mob.position().distanceToSqr(lastPos);
        lastPos = mob.position();
        if (movedSqr < 0.0025D)
            stuckTicks++;
        else
            stuckTicks = 0;

        if (blockBreakCooldown > 0)
            blockBreakCooldown--;

        var horizontal = new Vec3(direction.x, 0.0D, direction.z);
        var forward = horizontal.lengthSqr() > 0.01D ? horizontal.normalize() : Vec3.ZERO;

        var currentDistSqFlat = mob.distanceToSqr(target);
        if (currentDistSqFlat < lastDistSqToTarget - 0.1D) {
            lastDistSqToTarget = currentDistSqFlat;
            noProgressTicks = 0;
        } else {
            noProgressTicks++;
        }

        if ((stuckTicks > 10 || noProgressTicks > 20) && blockBreakCooldown <= 0 && !forward.equals(Vec3.ZERO)) {
            if (tryBreakBlockingPathBlock(mob, blackboard, target, forward)) {
                blockBreakCooldown = 10;
                stuckTicks = 0;
                noProgressTicks = 0;
                faceTarget(mob, target);
                return;
            }
        }

        if (!forward.equals(Vec3.ZERO)) {
            var movement = MovementUtils.steerAwayFromDangerEntities(mob, forward.scale(speed));
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

        faceTarget(mob, target);
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
        var isTunnel = nodeCache.tunnelCanStandAt(level, mob, waypoint);
        var isVerticalShaft = nodeCache.verticalShaftCanCrawlAt(level, mob, waypoint);

        if (isVerticalShaft) {
            var shaftRadius = 0.40D;

            if (horizontalDistSqr > shaftRadius * shaftRadius)
                return false;

            return Math.abs(yError) < 0.55D;
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

    private Vec3 snapToNearestWallFace(E mob, BlockPos block, Vec3 center) {
        var level = mob.level();
        var halfMob = mob.getBbWidth() / 2.0D;
        var faceOffset = 0.5D - halfMob;

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

        if (solidFaces == 0) {
            return center;
        }

        return new Vec3(center.x + pushX, center.y, center.z + pushZ);
    }

    private Vec3 findNearestWallDirection(E mob) {
        var level = mob.level();
        var box = mob.getBoundingBox();
        var probe = (mob.getBbWidth() / 2.0D) + 0.6D;
        var standingBox = box.move(0.0D, 1.0D, 0.0D);

        Vec3 best = null;
        var bestDist = Double.MAX_VALUE;

        var dirs = new Vec3[] {
            new Vec3(1, 0, 0),
            new Vec3(-1, 0, 0),
            new Vec3(0, 0, 1),
            new Vec3(0, 0, -1)
        };
        for (var dir : dirs) {
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

    private boolean isStairBlockAhead(E mob, Vec3 forward) {
        var level = mob.level();
        var checkPos = mob.position().add(forward.scale(0.6D));
        var feetY = mob.getBoundingBox().minY;

        var feet = BlockPos.containing(checkPos.x, feetY, checkPos.z);
        var head = feet.above();

        var feetState = level.getBlockState(feet);
        var headState = level.getBlockState(head);

        if (feetState.getCollisionShape(level, feet).isEmpty())
            return false;
        if (!headState.getCollisionShape(level, head).isEmpty())
            return false;

        var landing = head.above();
        var landingState = level.getBlockState(landing);
        return landingState.getCollisionShape(level, landing).isEmpty();
    }

    private boolean canAttachToClimbSurface(E mob, Vec3 waypoint) {
        if (MovementUtils.needsWallCrawl(mob, waypoint))
            return true;
        if (mob.horizontalCollision)
            return true;

        var level = mob.level();
        var box = mob.getBoundingBox();
        var checkDistance = (mob.getBbWidth() / 2.0D) + 0.5D;

        if (!level.noCollision(mob, box.move(0.0D, 0.0D, -checkDistance)))
            return true;
        if (!level.noCollision(mob, box.move(0.0D, 0.0D, checkDistance)))
            return true;
        if (!level.noCollision(mob, box.move(-checkDistance, 0.0D, 0.0D)))
            return true;
        if (!level.noCollision(mob, box.move(checkDistance, 0.0D, 0.0D)))
            return true;

        if (waypoint.y > mob.getY() + 0.5D) {
            var toWaypoint = new Vec3(waypoint.x - mob.getX(), 0.0D, waypoint.z - mob.getZ());

            if (toWaypoint.lengthSqr() < 0.25D) {
                var standingBox = box.move(0.0D, 1.0D, 0.0D);
                var sideProbe = (mob.getBbWidth() / 2.0D) + 0.6D;
                if (!level.noCollision(mob, standingBox.move(sideProbe, 0.0D, 0.0D)))
                    return true;
                if (!level.noCollision(mob, standingBox.move(-sideProbe, 0.0D, 0.0D)))
                    return true;
                if (!level.noCollision(mob, standingBox.move(0.0D, 0.0D, sideProbe)))
                    return true;
                return !level.noCollision(mob, standingBox.move(0.0D, 0.0D, -sideProbe));
            } else if (toWaypoint.lengthSqr() > 0.0001D) {
                var probeDir = toWaypoint.normalize();
                var probeDistance = (mob.getBbWidth() / 2.0D) + 1.0D;
                if (!level.noCollision(mob, box.move(probeDir.scale(probeDistance))))
                    return true;
                var standingBox = box.move(0.0D, 1.0D, 0.0D);
                return !level.noCollision(mob, standingBox.move(probeDir.scale(probeDistance)));
            }
        }

        return false;
    }

    private boolean tryBreakBlockingPathBlock(E mob, Blackboard blackboard, LivingEntity target, Vec3 forward) {
        var checkPos = mob.position().add(forward.scale(0.9D));
        var feet = BlockPos.containing(checkPos.x, mob.getBoundingBox().minY, checkPos.z);
        var head = feet.above();
        var targetBelow = target.getY() < mob.getY() - 1.0D;

        BlockPos toBreak = null;

        if (targetBelow) {
            var downForward = feet.below();
            if (canBreakPathBlock(mob, downForward)) {
                toBreak = downForward;
            } else {
                var downCurrent = mob.blockPosition().below();
                if (canBreakDownPathBlock(mob, downCurrent)) {
                    toBreak = downCurrent;
                }
            }
        }

        if (toBreak == null && canBreakPathBlock(mob, feet)) {
            toBreak = feet;
        }
        if (toBreak == null && canBreakPathBlock(mob, head)) {
            toBreak = head;
        }

        if (toBreak != null) {
            blackboard.set(AiKeys.BREAK_TO_TARGET_SCAN, toBreak);
            blackboard.set(AiKeys.BREAK_TO_TARGET_TRIGGER, Boolean.TRUE);
            return true;
        }
        return false;
    }

    private boolean canBreakPathBlock(E mob, BlockPos pos) {
        var level = mob.level();
        var state = level.getBlockState(pos);
        if (state.isAir())
            return false;
        if (!state.is(ModTags.WEAK_BLOCKS))
            return false;
        if (state.getDestroySpeed(level, pos) < 0.0F)
            return false;
        if (state.getCollisionShape(level, pos).isEmpty())
            return false;
        var below = pos.below();
        return level.getBlockState(below).getCollisionShape(level, below).isEmpty()
            || pos.getY() >= mob.blockPosition().getY();
    }

    private boolean canBreakDownPathBlock(E mob, BlockPos pos) {
        var level = mob.level();
        var state = level.getBlockState(pos);
        if (state.isAir())
            return false;
        if (!state.is(ModTags.WEAK_BLOCKS))
            return false;
        if (state.getDestroySpeed(level, pos) < 0.0F)
            return false;
        if (state.getCollisionShape(level, pos).isEmpty())
            return false;

        var landingFeet = pos.below();
        var landingGround = landingFeet.below();
        if (!level.getBlockState(landingFeet).getCollisionShape(level, landingFeet).isEmpty())
            return false;
        if (level.getBlockState(landingGround).getCollisionShape(level, landingGround).isEmpty())
            return false;
        return MovementUtils.isSafeBlock(level, landingFeet) && MovementUtils.isSafeBlock(level, landingGround);
    }

    private void halt(E mob) {
        mob.setDeltaMovement(0.0D, mob.getDeltaMovement().y, 0.0D);
        mob.hasImpulse = false;
    }

    private void faceTarget(E mob, LivingEntity target) {
        var dx = target.getX() - mob.getX();
        var dz = target.getZ() - mob.getZ();
        var yaw = (float) (Math.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        mob.setYRot(yaw);
        mob.yBodyRot = yaw;
        mob.yHeadRot = yaw;
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
    }

    private void faceMovementDirection(E mob, Vec3 movement) {
        if (movement.horizontalDistanceSqr() < 0.0001D)
            return;
        var yaw = (float) (Math.atan2(movement.z, movement.x) * (180.0D / Math.PI)) - 90.0F;
        mob.setYRot(yaw);
        mob.yBodyRot = yaw;
        mob.yHeadRot = yaw;
        mob.getLookControl()
            .setLookAt(
                mob.getX() + movement.x,
                mob.getEyeY() + movement.y,
                mob.getZ() + movement.z
            );
    }

    private BlockPos findBestTunnelBiasedGoal(E mob, LivingEntity target) {
        var level = mob.level();
        var targetPos = target.blockPosition();

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (
            var pos : BlockPos.betweenClosed(
                targetPos.offset(-8, -1, -8),
                targetPos.offset(8, 1, 8)
            )
        ) {
            var isTunnelish =
                nodeCache.tunnelCanStandAt(level, mob, pos)
                    || nodeCache.verticalShaftCanCrawlAt(level, mob, pos);

            if (!isTunnelish && !nodeCache.canStandAt(level, mob, pos)) {
                continue;
            }

            if (!isTunnelish && !hasClearPathToTarget(mob, target, pos)) {
                continue;
            }

            var nearTunnel =
                isTunnelish
                    || nodeCache.tunnelCanStandAt(level, mob, pos.north())
                    || nodeCache.tunnelCanStandAt(level, mob, pos.south())
                    || nodeCache.tunnelCanStandAt(level, mob, pos.east())
                    || nodeCache.tunnelCanStandAt(level, mob, pos.west());

            var score =
                pos.distSqr(targetPos)
                    + pos.distSqr(mob.blockPosition()) * 0.01D
                    + (nearTunnel ? -8.0D : 0.0D);

            if (score < bestScore) {
                bestScore = score;
                best = pos.immutable();
            }
        }

        return best;
    }

    private boolean hasClearPathToTarget(E mob, LivingEntity target, BlockPos pos) {
        var level = mob.level();

        var from = Vec3.atBottomCenterOf(pos).add(0.0D, mob.getBbHeight() * 0.5D, 0.0D);
        var to = target.getEyePosition();

        var hit = level.clip(
            new ClipContext(
                from,
                to,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                mob
            )
        );

        return hit.getType() == HitResult.Type.MISS;
    }

    /**
     * Returns the nearest tunnel entry the mob could use, or {@code null}.
     * <p>
     * Cost tiers, cheapest first: (1) the cached result from a recent scan, revalidated with two checks; (2) the
     * {@link TunnelEntryRegistry} — a few chunk-bucket lookups over known entries; (3) a full expanding-ring world
     * scan, at most once every {@value #TUNNEL_RESCAN_TICKS} ticks. The old implementation ran tier 3 over the entire
     * 65x5x65 volume (~21k positions) every single tick.
     */
    private BlockPos findNearbyTunnelEntry(E mob) {
        var level = mob.level();
        var origin = mob.blockPosition();

        if (tunnelRescanCooldown > 0 && tunnelScanOrigin != null && origin.distSqr(tunnelScanOrigin) <= 16.0D) {
            tunnelRescanCooldown--;

            if (cachedTunnelEntry == null) {
                return null;
            }

            if (
                nodeCache.tunnelCanStandAt(level, mob, cachedTunnelEntry)
                    || nodeCache.verticalShaftCanCrawlAt(level, mob, cachedTunnelEntry)
            ) {
                return cachedTunnelEntry;
            }

            TunnelEntryRegistry.unregister(level, cachedTunnelEntry);
        }

        tunnelScanOrigin = origin;
        tunnelRescanCooldown = TUNNEL_RESCAN_TICKS;

        var known = TunnelEntryRegistry.findNearestValid(
            level,
            mob,
            origin,
            TUNNEL_SCAN_RADIUS,
            TUNNEL_SCAN_MIN_DY,
            TUNNEL_SCAN_MAX_DY,
            nodeCache
        );
        if (known != null) {
            cachedTunnelEntry = known;
            return known;
        }

        cachedTunnelEntry = scanForTunnelEntry(mob, origin);
        return cachedTunnelEntry;
    }

    /**
     * Expanding-square-ring scan for the nearest tunnel entry. The first ring containing a hit holds the nearest entry
     * (to within ring granularity), so the common cases — an entry close by, or open terrain rejected cheaply by the
     * reordered {@code tunnelCanStandAt} — terminate after a small fraction of the full volume. Every hit is registered
     * so future lookups (by this mob or any other) resolve from the registry instead.
     */
    private BlockPos scanForTunnelEntry(E mob, BlockPos origin) {
        var level = mob.level();
        var cursor = new BlockPos.MutableBlockPos();

        for (var r = 0; r <= TUNNEL_SCAN_RADIUS; r++) {
            BlockPos best = null;
            var bestDist = Double.MAX_VALUE;

            for (var dx = -r; dx <= r; dx++) {
                var onXEdge = Math.abs(dx) == r;

                for (var dz = -r; dz <= r; dz++) {
                    if (!onXEdge && Math.abs(dz) != r) {
                        continue;
                    }

                    for (var dy = TUNNEL_SCAN_MIN_DY; dy <= TUNNEL_SCAN_MAX_DY; dy++) {
                        cursor.setWithOffset(origin, dx, dy, dz);

                        if (
                            !nodeCache.tunnelCanStandAt(level, mob, cursor)
                                && !nodeCache.verticalShaftCanCrawlAt(level, mob, cursor)
                        ) {
                            continue;
                        }

                        var hit = cursor.immutable();
                        TunnelEntryRegistry.register(level, hit);

                        var dist = hit.distSqr(origin);
                        if (dist < bestDist) {
                            bestDist = dist;
                            best = hit;
                        }
                    }
                }
            }

            if (best != null) {
                return best;
            }
        }

        return null;
    }

    private boolean needsCrawlStepUp(E mob, BlockPos waypointBlock, BlockPos mobFeet) {
        var currentY = mobFeet.getY();

        if (waypointBlock.getY() > currentY) {
            return true;
        }

        if (path == null || path.isEmpty()) {
            return false;
        }

        var maxLookahead = Math.min(path.size(), pathIndex + 4);

        for (var i = pathIndex; i < maxLookahead; i++) {
            var candidate = path.get(i);

            if (candidate.getY() > currentY) {
                var dx = candidate.getX() + 0.5D - mob.getX();
                var dz = candidate.getZ() + 0.5D - mob.getZ();
                var horizontalDistSqr = dx * dx + dz * dz;

                return horizontalDistSqr < 4.0D;
            }
        }

        return false;
    }

    private BlockPos findUpcomingHigherPathNode(BlockPos mobFeet) {
        if (path == null || path.isEmpty()) {
            return null;
        }

        var currentY = mobFeet.getY();
        var maxLookahead = Math.min(path.size(), pathIndex + 4);

        for (var i = pathIndex; i < maxLookahead; i++) {
            var candidate = path.get(i);

            if (candidate.getY() > currentY) {
                return candidate;
            }
        }

        return null;
    }

    private Vec3 removeBlockedHorizontalComponents(E mob, Vec3 desired) {
        var level = mob.level();
        var box = mob.getBoundingBox();

        var x = desired.x;
        var z = desired.z;

        var probe = Math.max(0.08D, mob.getBbWidth() * 0.25D);

        if (Math.abs(x) > 0.0001D) {
            var xProbe = box.move(Math.copySign(probe, x), 0.0D, 0.0D);
            if (!level.noCollision(mob, xProbe)) {
                x = 0.0D;
            }
        }

        if (Math.abs(z) > 0.0001D) {
            var zProbe = box.move(0.0D, 0.0D, Math.copySign(probe, z));
            if (!level.noCollision(mob, zProbe)) {
                z = 0.0D;
            }
        }

        return new Vec3(x, desired.y, z);
    }

    /**
     * Scans upcoming path nodes for a wall that A* routed over (consecutive nodes with a Y step of 2+). If the bottom
     * block of that wall face is a breakable weak block, sets BREAK_TO_TARGET_SCAN + BREAK_TO_TARGET_TRIGGER so
     * BreakToTargetAction will chip it out on the next tree tick, letting the mob walk through at ground level instead
     * of climbing over.
     * <p>
     * Only called on a fresh repath and only when no break is already pending, so it won't thrash. The 2-block
     * threshold means normal step-ups and hills are never considered.
     */
    private void checkPathForBreakableWall(E mob, Blackboard blackboard, List<BlockPos> path, int fromIndex) {
        if (fromIndex < 0 || fromIndex >= path.size()) {
            return;
        }

        var scanLimit = Math.min(path.size(), fromIndex + 8);
        var baseY = path.get(fromIndex).getY();

        for (var i = fromIndex + 1; i < scanLimit; i++) {
            var node = path.get(i);
            var rise = node.getY() - baseY;

            if (rise < 2) {
                continue;
            }

            for (var wallY = baseY; wallY < node.getY(); wallY++) {
                var candidate = new BlockPos(node.getX(), wallY, node.getZ());
                if (canBreakPathBlock(mob, candidate)) {
                    blackboard.set(AiKeys.BREAK_TO_TARGET_SCAN, candidate);
                    blackboard.set(AiKeys.BREAK_TO_TARGET_TRIGGER, Boolean.TRUE);
                    return;
                }
            }
            return;
        }
    }

    private Vec3 findCornerEscapeStepUpVelocity(E mob, Vec3 desiredHorizontal) {
        var level = mob.level();
        var box = mob.getBoundingBox();

        var desired = new Vec3(desiredHorizontal.x, 0.0D, desiredHorizontal.z);
        if (desired.lengthSqr() < 0.0001D) {
            desired = new Vec3(mob.getLookAngle().x, 0.0D, mob.getLookAngle().z);
        }

        if (desired.lengthSqr() < 0.0001D) {
            return new Vec3(0.0D, speed * 0.85D, 0.0D);
        }

        desired = desired.normalize();

        var candidates = new Vec3[] {
            new Vec3(desired.x, 0.0D, desired.z),
            new Vec3(-desired.z, 0.0D, desired.x),
            new Vec3(desired.z, 0.0D, -desired.x),
            new Vec3(desired.x - desired.z, 0.0D, desired.z + desired.x),
            new Vec3(desired.x + desired.z, 0.0D, desired.z - desired.x)
        };

        Vec3 best = Vec3.ZERO;
        double bestScore = -Double.MAX_VALUE;

        var stepClearY = 1.05D;

        for (var candidate : candidates) {
            if (candidate.lengthSqr() < 0.0001D) {
                continue;
            }

            var dir = candidate.normalize();
            var testMove = dir.scale(speed * 0.55D);
            var probeBox = box.move(testMove.x, stepClearY, testMove.z);

            if (!level.noCollision(mob, probeBox)) {
                continue;
            }

            var score = dir.dot(desired);

            if (score > bestScore) {
                bestScore = score;
                best = testMove;
            }
        }

        return new Vec3(
            best.x,
            Math.max(mob.getDeltaMovement().y, speed * 0.9D),
            best.z
        );
    }
}
