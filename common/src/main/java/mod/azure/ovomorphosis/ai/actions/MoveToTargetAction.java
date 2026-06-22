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
    }

    @Override
    public ActionStatus tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        if (mob.getHealth() <= 0) {
            mob.setAggressive(false);
            return ActionStatus.INTERRUPTED;
        }

        var target = blackboard.get(AiKeys.TARGET, LivingEntity.class);

        if (target == null || !target.isAlive()) {
            if (!canCrawl || !CrawlingManager.isWallCrawling(mob)) {
                mob.setDeltaMovement(mob.getDeltaMovement().scale(0.5D));
            }
            return ActionStatus.FAILURE;
        }

        var yDiff = target.getY() - mob.getY();
        if (!canCrawl && yDiff > 12.0D) {
            mob.getNavigation().stop();
            return ActionStatus.FAILURE;
        }

        if (mob.distanceToSqr(target) <= stopDistanceSqr && hasMeleeLineOfSight(mob, target)) {
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
        var mobOnSolidGround = !mob.level()
            .getBlockState(groundBlock)
            .getCollisionShape(mob.level(), groundBlock)
            .isEmpty();

        var mobIsInOrAtTunnel = canCrawl
            && (CrawlingCustomAStar.tunnelCanStandAt(mob.level(), mob, mobFeetPos)
                || CrawlingCustomAStar.tunnelCanStandAt(mob.level(), mob, mobFeetPos.below())
                || CrawlingCustomAStar.tunnelCanStandAt(mob.level(), mob, mobFeetPos.above())
                || CrawlingCustomAStar.verticalShaftCanCrawlAt(mob.level(), mob, mobFeetPos)
                || CrawlingCustomAStar.verticalShaftCanCrawlAt(mob.level(), mob, mobFeetPos.below()));

        var nextWaypointIsTunnel = false;
        if (canCrawl && !path.isEmpty() && pathIndex < path.size()) {
            for (var li = pathIndex; li < Math.min(path.size(), pathIndex + 3); li++) {
                var la = path.get(li);
                if (
                    CrawlingCustomAStar.tunnelCanStandAt(mob.level(), mob, la)
                        || CrawlingCustomAStar.verticalShaftCanCrawlAt(mob.level(), mob, la)
                ) {
                    nextWaypointIsTunnel = true;
                    break;
                }
            }
        }
        if (mobOnSolidGround && !mobIsInOrAtTunnel && !nextWaypointIsTunnel) {
            CrawlingManager.setWallCrawling(mob, false);
            if (mob instanceof WallCrawlingMob wc) {
                wc.ovomorphosis$setWallCrawlGraceTicks(0);
            }
        }

        var isCrawlingNow = canCrawl && CrawlingManager.isWallCrawling(mob);
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
            || (!mobOnSolidGround && CrawlingManager.shouldUseWallCrawlingToTarget(mob, target)));

        var tunnelBiasedGoal = (shouldUseCrawlingNow && (!mobOnSolidGround || mobIsInOrAtTunnel
            || nearbyTunnelEntry != null))
                ? findBestTunnelBiasedGoal(mob, target)
                : null;

        var repathInterval = isCrawlingNow ? 40 : 20;

        if (repathCooldown <= 0 || path.isEmpty() || pathIndex >= path.size()) {
            var crawlGoal = (tunnelBiasedGoal != null) ? tunnelBiasedGoal : target.blockPosition();

            if (canCrawl) {
                path = CrawlingCustomAStar.findPath(mob, mob.blockPosition(), crawlGoal, 96, 0);
                if (path.isEmpty() && nearbyTunnelEntry != null && !nearbyTunnelEntry.equals(crawlGoal)) {
                    path = CrawlingCustomAStar.findPath(mob, mob.blockPosition(), nearbyTunnelEntry, 96, 0);
                }
                if (path.isEmpty()) {
                    path = CrawlingCustomAStar.findPath(mob, mob.blockPosition(), crawlGoal, 96, 1);
                }
                if (path.isEmpty()) {
                    path = CustomAStar.findPath(mob, mob.blockPosition(), target.blockPosition(), 64, 1);
                }
            } else {
                path = CustomAStar.findPath(mob, mob.blockPosition(), target.blockPosition(), 64, 1);
            }

            pathIndex = path.size() > 1 ? 1 : 0;
            repathCooldown = repathInterval;

            if (isCrawlingNow && pathIndex < path.size()) {
                while (pathIndex < path.size() && hasReachedWaypoint(mob, path.get(pathIndex), true)) {
                    pathIndex++;
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
                        Vec3 move = getMove(mob, toEntrance, tunnelCenter);
                        if (canCrawl) {
                            if (horiz.lengthSqr() < 2.25D) {
                                CrawlingManager.setWallCrawling(mob, true);
                                CrawlingManager.updateCrawlOrientation(mob, move);
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
            applyFlatFallback(mob, target, directDirection);
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
            CrawlingManager.setWallCrawling(mob, false);
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

        var waypointIsVerticalShaft =
            CrawlingCustomAStar.verticalShaftCanCrawlAt(mob.level(), mob, waypointBlock);

        var waypointLeadsIntoVerticalShaft =
            waypointIsVerticalShaft
                || CrawlingCustomAStar.verticalShaftCanCrawlAt(mob.level(), mob, waypointBlock.below())
                || CrawlingCustomAStar.verticalShaftCanCrawlAt(mob.level(), mob, waypointBlock.below(2));

        if (waypointLeadsIntoVerticalShaft && target.getY() < mob.getY() - 0.75D) {
            var shaftBlock = waypointIsVerticalShaft
                ? waypointBlock
                : CrawlingCustomAStar.verticalShaftCanCrawlAt(mob.level(), mob, waypointBlock.below())
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
                CrawlingManager.setWallCrawling(mob, false);
                shaftVelocity = new Vec3(
                    centerError.x * 0.35D,
                    -speed * 0.85D,
                    centerError.z * 0.35D
                );
            }

            CrawlingManager.setWallCrawling(mob, true);
            CrawlingManager.updateCrawlOrientation(mob, shaftVelocity);
            mob.setDeltaMovement(shaftVelocity);
            mob.hasImpulse = true;
            faceMovementDirection(mob, shaftVelocity);
            return;
        }

        var mobFeet = BlockPos.containing(
            mob.getX(),
            mob.getBoundingBox().minY,
            mob.getZ()
        );

        var waypointIsTunnel =
            CrawlingCustomAStar.tunnelCanStandAt(mob.level(), mob, waypointBlock);

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

                var vertical = 0.0D;

                if (yError > 0.20D) {
                    vertical = Mth.clamp(yError * 0.35D, 0.08D, 0.32D);
                } else if (yError < -0.20D) {
                    vertical = Mth.clamp(yError * 0.35D, -0.32D, -0.04D);
                } else {
                    vertical = mob.getDeltaMovement().y * 0.20D;
                }

                var descendingInTunnel = yError < -0.20D;

                CrawlingManager.setWallCrawling(mob, !descendingInTunnel);

                var tunnelSpeed = waypointIsTunnel ? speed : speed * 1.25D;

                var tunnelVelocity = new Vec3(
                    centerMove.normalize().x * tunnelSpeed,
                    vertical,
                    centerMove.normalize().z * tunnelSpeed
                );

                if (!descendingInTunnel) {
                    CrawlingManager.updateCrawlOrientation(mob, tunnelVelocity);
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
                CrawlingManager.setWallCrawling(mob, !descending);
                if (descending && mob instanceof WallCrawlingMob wc) {
                    wc.ovomorphosis$setWallCrawlGraceTicks(0);
                    vertical = Math.min(vertical, -speed * 0.6D);
                    tunnelVelocity = new Vec3(0.0D, vertical, 0.0D);
                }
                CrawlingManager.updateCrawlOrientation(mob, tunnelVelocity);
                mob.setDeltaMovement(tunnelVelocity);
                mob.hasImpulse = true;
                return;
            }
        }

        var waypointIsGroundOnly = CustomAStar.canStandAt(mob.level(), mob, waypointBlock)
            && !MovementUtils.isSafeClimbNode(mob.level(), mob, waypointBlock);
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
                        CrawlingManager.setWallCrawling(mob, false);
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

            CrawlingManager.setWallCrawling(mob, true);
            CrawlingManager.updateCrawlOrientation(mob, crawlVelocity);
            mob.setDeltaMovement(crawlVelocity);
            mob.hasImpulse = true;
            faceMovementDirection(mob, crawlVelocity);
            return;
        }

        if (canCrawl) {
            CrawlingManager.setWallCrawling(mob, false);
        }

        if (shouldUseCrawlingNow) {
            var toWaypoint = new Vec3(direction.x, 0.0D, direction.z);
            if (toWaypoint.lengthSqr() > 0.01D) {
                CrawlingManager.setWallCrawling(mob, false);
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
                    CrawlingManager.setWallCrawling(mob, true);
                    var pushVelocity = new Vec3(wallDir.x * speed * 0.3D, speed * 0.6D, wallDir.z * speed * 0.3D);
                    CrawlingManager.updateCrawlOrientation(mob, pushVelocity);
                    mob.setDeltaMovement(pushVelocity);
                    mob.hasImpulse = true;
                    return;
                }
            }
            halt(mob);
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
                    && CrawlingManager.isWallCrawling(mob)
                    && target.getY() < mob.getY() - 1.0D
                    && waypointBlock.getY() < mob.blockPosition().getY()
            ) {
                CrawlingManager.setWallCrawling(mob, false);

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
                if (tryBreakBlockingPathBlock(mob, target, forward)) {
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

            if (!targetBelow && stuckTicks > 20 && blockBreakCooldown <= 0) {
                if (tryBreakBlockingPathBlock(mob, target, forward)) {
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

    private void applyFlatFallback(E mob, LivingEntity target, Vec3 direction) {
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
        var isTunnel = CrawlingCustomAStar.tunnelCanStandAt(level, mob, waypoint);
        var isVerticalShaft = CrawlingCustomAStar.verticalShaftCanCrawlAt(level, mob, waypoint);

        if (isVerticalShaft) {
            var shaftRadius = 0.40D;

            if (horizontalDistSqr > shaftRadius * shaftRadius)
                return false;

            return Math.abs(yError) < 0.55D;
        }

        var reachRadius = isTunnel ? 0.6D : 1.15D;

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

        var dirs = new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
        var bestDist = Double.MAX_VALUE;
        Vec3 bestSnap = center;

        for (var dir : dirs) {
            var neighbor = block.offset(dir[0], 0, dir[1]);
            var neighborState = level.getBlockState(neighbor);
            if (!neighborState.getCollisionShape(level, neighbor).isEmpty()) {
                var snapX = center.x - dir[0] * faceOffset;
                var snapZ = center.z - dir[1] * faceOffset;
                var snap = new Vec3(snapX, center.y, snapZ);
                var dist = mob.position().distanceToSqr(snap);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestSnap = snap;
                }
            }
        }
        return bestSnap;
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
            var hitCurrent = !level.noBlockCollision(mob, box.move(dir.scale(probe)));
            var hitStanding = !level.noBlockCollision(mob, standingBox.move(dir.scale(probe)));
            if (hitCurrent || hitStanding) {
                var dist = probe;
                for (var d = 0.1D; d <= probe; d += 0.1D) {
                    if (!level.noBlockCollision(mob, box.move(dir.scale(d)))) {
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

        if (!level.noBlockCollision(mob, box.move(0.0D, 0.0D, -checkDistance)))
            return true;
        if (!level.noBlockCollision(mob, box.move(0.0D, 0.0D, checkDistance)))
            return true;
        if (!level.noBlockCollision(mob, box.move(-checkDistance, 0.0D, 0.0D)))
            return true;
        if (!level.noBlockCollision(mob, box.move(checkDistance, 0.0D, 0.0D)))
            return true;

        if (waypoint.y > mob.getY() + 0.5D) {
            var toWaypoint = new Vec3(waypoint.x - mob.getX(), 0.0D, waypoint.z - mob.getZ());

            if (toWaypoint.lengthSqr() < 0.25D) {
                var standingBox = box.move(0.0D, 1.0D, 0.0D);
                var sideProbe = (mob.getBbWidth() / 2.0D) + 0.6D;
                if (!level.noBlockCollision(mob, standingBox.move(sideProbe, 0.0D, 0.0D)))
                    return true;
                if (!level.noBlockCollision(mob, standingBox.move(-sideProbe, 0.0D, 0.0D)))
                    return true;
                if (!level.noBlockCollision(mob, standingBox.move(0.0D, 0.0D, sideProbe)))
                    return true;
                return !level.noBlockCollision(mob, standingBox.move(0.0D, 0.0D, -sideProbe));
            } else if (toWaypoint.lengthSqr() > 0.0001D) {
                var probeDir = toWaypoint.normalize();
                var probeDistance = (mob.getBbWidth() / 2.0D) + 1.0D;
                if (!level.noBlockCollision(mob, box.move(probeDir.scale(probeDistance))))
                    return true;
                var standingBox = box.move(0.0D, 1.0D, 0.0D);
                return !level.noBlockCollision(mob, standingBox.move(probeDir.scale(probeDistance)));
            }
        }

        return false;
    }

    private boolean tryBreakBlockingPathBlock(E mob, LivingEntity target, Vec3 forward) {
        var level = mob.level();
        var checkPos = mob.position().add(forward.scale(0.9D));
        var feet = BlockPos.containing(checkPos.x, mob.getBoundingBox().minY, checkPos.z);
        var head = feet.above();
        var targetBelow = target.getY() < mob.getY() - 1.0D;

        if (targetBelow) {
            var downForward = feet.below();
            if (canBreakDownPathBlock(mob, downForward)) {
                level.destroyBlock(downForward, true, mob);
                return true;
            }
            var downCurrent = mob.blockPosition().below();
            if (canBreakDownPathBlock(mob, downCurrent)) {
                level.destroyBlock(downCurrent, true, mob);
                return true;
            }
        }

        if (canBreakPathBlock(mob, feet)) {
            level.destroyBlock(feet, true, mob);
            return true;
        }
        if (canBreakPathBlock(mob, head)) {
            level.destroyBlock(head, true, mob);
            return true;
        }
        return false;
    }

    private boolean canBreakPathBlock(E mob, BlockPos pos) {
        var level = mob.level();
        var state = level.getBlockState(pos);
        if (state.isAir())
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
            var reachable =
                CustomAStar.canStandAt(level, mob, pos)
                    || CrawlingCustomAStar.tunnelCanStandAt(level, mob, pos)
                    || CrawlingCustomAStar.verticalShaftCanCrawlAt(level, mob, pos);

            if (!reachable) {
                continue;
            }

            var isTunnelish =
                CrawlingCustomAStar.tunnelCanStandAt(level, mob, pos)
                    || CrawlingCustomAStar.verticalShaftCanCrawlAt(level, mob, pos);

            if (!isTunnelish && !hasClearPathToTarget(mob, target, pos)) {
                continue;
            }

            var nearTunnel =
                CrawlingCustomAStar.tunnelCanStandAt(level, mob, pos)
                    || CrawlingCustomAStar.tunnelCanStandAt(level, mob, pos.north())
                    || CrawlingCustomAStar.tunnelCanStandAt(level, mob, pos.south())
                    || CrawlingCustomAStar.tunnelCanStandAt(level, mob, pos.east())
                    || CrawlingCustomAStar.tunnelCanStandAt(level, mob, pos.west());

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

    private BlockPos findNearbyTunnelEntry(E mob) {
        var level = mob.level();
        var origin = mob.blockPosition();

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (
            var pos : BlockPos.betweenClosed(
                origin.offset(-32, -3, -32),
                origin.offset(32, 1, 32)
            )
        ) {
            if (
                !CrawlingCustomAStar.tunnelCanStandAt(level, mob, pos)
                    && !CrawlingCustomAStar.verticalShaftCanCrawlAt(level, mob, pos)
            ) {
                continue;
            }

            var dist = pos.distSqr(origin);
            if (dist < bestDist) {
                bestDist = dist;
                best = pos.immutable();
            }
        }

        return best;
    }

    private static boolean hasMeleeLineOfSight(Mob mob, LivingEntity target) {
        var level = mob.level();

        var from = mob.getEyePosition();
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
}
