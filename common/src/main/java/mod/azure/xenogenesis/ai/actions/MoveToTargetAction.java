package mod.azure.xenogenesis.ai.actions;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.List;

import mod.azure.xenogenesis.ai.core.*;
import mod.azure.xenogenesis.ai.util.AiDebugUtils;
import mod.azure.xenogenesis.ai.util.CrawlingCustomAStar;
import mod.azure.xenogenesis.ai.util.CrawlingManager;
import mod.azure.xenogenesis.ai.util.CustomAStar;
import mod.azure.xenogenesis.ai.util.MovementUtils;

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

        if (mob.distanceToSqr(target) <= stopDistanceSqr) {
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

        var isCrawlingNow = canCrawl && CrawlingManager.isWallCrawling(mob);
        var shouldUseCrawlingNow = canCrawl
            && (CrawlingManager.shouldUseWallCrawlingToTarget(mob, target) || isCrawlingNow);

        // If crawl was requested but the mob is on flat walkable ground with no
        // adjacent climbable surface, disengage crawl mode and use ground pathing.
        // This prevents the mob getting stranded on a pillar top trying to find a
        // wall that isn't there.
        if (shouldUseCrawlingNow && !isCrawlingNow) {
            var level = mob.level();
            var origin = mob.blockPosition();
            var hasAdjacentWall = false;
            for (var dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                if (MovementUtils.isSafeClimbNode(level, mob, origin.relative(dir))) {
                    hasAdjacentWall = true;
                    break;
                }
            }
            if (!hasAdjacentWall && CustomAStar.canStandAt(level, mob, origin)) {
                shouldUseCrawlingNow = false;
            }
        }

        var repathInterval = isCrawlingNow ? 20 : 10;

        if (repathCooldown <= 0 || path.isEmpty() || pathIndex >= path.size()) {
            var goalRadius = 1;

            path = shouldUseCrawlingNow
                ? CrawlingCustomAStar.findPath(mob, mob.blockPosition(), target.blockPosition(), 96, goalRadius)
                : CustomAStar.findPath(mob, mob.blockPosition(), target.blockPosition(), 64, goalRadius);

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

                var waypoint = shouldUseCrawlingNow
                    ? snapToNearestWallFace(mob, waypointBlock, waypointCenter)
                    : waypointCenter;

                var direction = waypoint.subtract(mob.position());

                if (direction.lengthSqr() > 0.0001D) {
                    applyPathMovement(mob, target, waypoint, direction, shouldUseCrawlingNow);
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

    @Override
    public void stop(E mob, Blackboard blackboard, ActionStatus reason) {
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

        var waypointBlock = BlockPos.containing(waypoint);
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
                // Climbing up: bias toward vertical with wall nudge
                crawlVelocity = new Vec3(intoWall.x, speed * 0.85D, intoWall.z);
            } else if (yError < -0.10D) {
                // Descending: always include full horizontal component toward the waypoint
                // so the mob actually moves along the surface rather than producing a
                // purely vertical (negative) velocity that gets zeroed out.
                var verticalSpeed = Mth.clamp(yError * 0.45D, -speed, -speed * 0.15D);
                if (horizontal.lengthSqr() > 0.01D) {
                    var moveDir = horizontal.normalize().scale(speed);
                    crawlVelocity = new Vec3(
                        moveDir.x + intoWall.x,
                        verticalSpeed,
                        moveDir.z + intoWall.z
                    );
                } else {
                    // No horizontal component — pure descent along wall
                    crawlVelocity = new Vec3(intoWall.x, verticalSpeed, intoWall.z);
                    // If still no wall nudge, force gravity off and let it fall
                    if (crawlVelocity.horizontalDistanceSqr() < 0.0001D) {
                        CrawlingManager.setWallCrawling(mob, false);
                        faceTarget(mob, target);
                        return;
                    }
                }
            } else if (yError > 0.10D) {
                // Small upward error with horizontal movement
                var verticalSpeed = Mth.clamp(yError * 0.45D, 0.0D, speed);
                if (horizontal.lengthSqr() > 0.01D) {
                    var moveDir = horizontal.normalize().scale(speed);
                    crawlVelocity = new Vec3(moveDir.x + intoWall.x, verticalSpeed, moveDir.z + intoWall.z);
                } else {
                    crawlVelocity = new Vec3(intoWall.x, verticalSpeed, intoWall.z);
                }
            } else if (horizontal.lengthSqr() > 0.01D) {
                // Level movement along surface
                var moveDir = horizontal.normalize().scale(speed);
                crawlVelocity = new Vec3(moveDir.x + intoWall.x, intoWall.y, moveDir.z + intoWall.z);
            } else {
                crawlVelocity = MovementUtils.computeWallCrawlVelocity(mob, waypoint, speed);
            }

            // Only zero out if genuinely no movement — don't swallow small legitimate velocities
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
            var approach = new Vec3(direction.x, 0.0D, direction.z);

            if (approach.lengthSqr() > 0.01D) {
                var movement = approach.normalize().scale(speed);

                if (canAttachToClimbSurface(mob, waypoint)) {
                    CrawlingManager.setWallCrawling(mob, true);
                    var climbVelocity = new Vec3(movement.x * 0.35D, speed, movement.z * 0.35D);
                    CrawlingManager.updateCrawlOrientation(mob, climbVelocity);
                    mob.setDeltaMovement(climbVelocity);
                    mob.hasImpulse = true;
                    faceMovementDirection(mob, climbVelocity);
                    return;
                }

                CrawlingManager.setWallCrawling(mob, false);
                mob.setDeltaMovement(movement.x, mob.getDeltaMovement().y, movement.z);
                mob.hasImpulse = true;
                faceMovementDirection(mob, movement);
                return;
            }

            if (direction.y < -1.0D) {
                var toTarget = target.position().subtract(mob.position());
                var nudge = new Vec3(toTarget.x, 0.0D, toTarget.z);
                if (nudge.lengthSqr() > 0.0001D) {
                    var nudgeMove = nudge.normalize().scale(speed);
                    mob.setDeltaMovement(nudgeMove.x, mob.getDeltaMovement().y, nudgeMove.z);
                    mob.hasImpulse = true;
                    faceTarget(mob, target);
                    return;
                }
            }

            if (Math.abs(direction.y) > 0.35D && canAttachToClimbSurface(mob, waypoint)) {
                CrawlingManager.setWallCrawling(mob, true);
                var climbVelocity = new Vec3(0.0D, speed, 0.0D);
                CrawlingManager.updateCrawlOrientation(mob, climbVelocity);
                mob.setDeltaMovement(climbVelocity);
                mob.hasImpulse = true;
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

            // Target is below and we're stuck — we're probably on a pillar top or ledge
            // with no safe horizontal movement. Just walk off the edge toward the target;
            // gravity will handle the descent. findSafeMovement rejects these moves because
            // there's no ground ahead, but that's exactly what we want here.
            if (targetBelow && mob.onGround() && stuckTicks > 15) {
                var toTarget = target.position().subtract(mob.position());
                var nudge = new Vec3(toTarget.x, 0.0D, toTarget.z);
                if (nudge.lengthSqr() > 0.0001D) {
                    var walkOff = nudge.normalize().scale(speed);
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
            // No safe horizontal move found — if target is significantly below we're
            // likely on a ledge/pillar top. Walk directly toward target and let gravity
            // take over rather than standing frozen.
            var targetBelow = target.getY() < mob.getY() - 1.5D;
            if (targetBelow && mob.onGround()) {
                var toTarget = target.position().subtract(mob.position());
                var nudge = new Vec3(toTarget.x, 0.0D, toTarget.z);
                if (nudge.lengthSqr() > 0.0001D) {
                    var walkOff = nudge.normalize().scale(speed);
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

        AiDebugUtils.sendParticlePath(mob, mob.position(), target.position());
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
            return mob.position().distanceToSqr(waypointCenter) < 0.35D;
        }

        var dx = mob.getX() - waypointCenter.x;
        var dz = mob.getZ() - waypointCenter.z;
        var horizontalDistSqr = dx * dx + dz * dz;
        var yError = waypointCenter.y - mob.getY();

        if (horizontalDistSqr > 1.15D * 1.15D)
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
}
