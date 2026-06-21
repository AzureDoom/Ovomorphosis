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
import mod.azure.xenogenesis.ai.util.MovementUtils;

public final class CrawlToTargetAction<E extends Mob> implements Action<E> {

    private static final int DANGER_LEAP_STUCK_TICKS = 8;

    private static final int DANGER_LEAP_COOLDOWN_TICKS = 30;

    private static final double DANGER_LEAP_DISTANCE = 3.0D;

    private static final double DANGER_LEAP_VERTICAL_POWER = 0.75D;

    private static final double DANGER_LEAP_HORIZONTAL_POWER = 0.75D;

    private int dangerLeapCooldown = 0;

    private final double stopDistanceSqr;

    private final double speed;

    private final int priority;

    private final double maxLeapHeight = 5D;

    private final int[] steerBias = { 0 };

    private Vec3 lastPos = Vec3.ZERO;

    private int stuckTicks = 0;

    private Vec3 detourDirection = Vec3.ZERO;

    private int detourTicks = 0;

    private static final int BLOCK_BREAK_STUCK_TICKS = 20;

    private static final int BLOCK_BREAK_COOLDOWN_TICKS = 10;

    private int blockBreakCooldown = 0;

    private List<BlockPos> path = Collections.emptyList();

    private int pathIndex = 0;

    private int repathCooldown = 0;

    public CrawlToTargetAction(
        double stopDistance,
        double speed,
        int priority
    ) {
        this.stopDistanceSqr = stopDistance * stopDistance;
        this.speed = speed;
        this.priority = priority;
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
            if (!CrawlingManager.isWallCrawling(mob)) {
                mob.setDeltaMovement(mob.getDeltaMovement().scale(0.5D));
            }
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

        var isCrawlingNow = CrawlingManager.isWallCrawling(mob);
        var shouldUseCrawlingNow = CrawlingManager.shouldUseWallCrawlingToTarget(mob, target)
            || isCrawlingNow;

        var repathInterval = isCrawlingNow ? 20 : 10;

        if (repathCooldown <= 0 || path.isEmpty() || pathIndex >= path.size()) {
            var goalRadius = 1;

            path = CrawlingCustomAStar.findPath(
                mob,
                mob.blockPosition(),
                target.blockPosition(),
                96,
                goalRadius
            );

            pathIndex = path.size() > 1 ? 1 : 0;
            repathCooldown = repathInterval;

            if (isCrawlingNow && pathIndex < path.size()) {
                while (
                    pathIndex < path.size()
                        && hasReachedWaypoint(mob, path.get(pathIndex), true)
                ) {
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

                Vec3 waypoint;
                if (shouldUseCrawlingNow) {
                    waypoint = snapToNearestWallFace(mob, waypointBlock, waypointCenter);
                } else {
                    waypoint = waypointCenter;
                }

                var direction = waypoint.subtract(mob.position());

                if (direction.lengthSqr() > 0.0001D) {
                    applyPathMovement(mob, target, waypoint, direction);
                    return ActionStatus.RUNNING;
                }
            }
        }

        var directDirection = target.position().subtract(mob.position());

        if (directDirection.lengthSqr() > 0.0001D) {
            var horizontal = new Vec3(directDirection.x, 0.0D, directDirection.z);

            if (horizontal.lengthSqr() > 0.01D) {
                var movement = MovementUtils.steerAwayFromDangerEntities(
                    mob,
                    horizontal.normalize().scale(speed)
                );

                var safe = MovementUtils.findSafeMovement(mob, movement, steerBias);

                if (!safe.equals(Vec3.ZERO)) {
                    mob.setDeltaMovement(safe.x, mob.getDeltaMovement().y, safe.z);
                    mob.hasImpulse = true;
                    faceTarget(mob, target);
                } else {
                    halt(mob);
                    faceTarget(mob, target);
                }
            }

            return ActionStatus.RUNNING;
        }

        halt(mob);
        faceTarget(mob, target);
        return ActionStatus.RUNNING;
    }

    @Override
    public void stop(E mob, Blackboard blackboard, ActionStatus reason) {
        if (!CrawlingManager.isWallCrawling(mob)) {
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

    private boolean hasReachedWaypoint(E mob, BlockPos waypoint, boolean shouldUseCrawling) {
        var waypointCenter = Vec3.atBottomCenterOf(waypoint);

        var dx = mob.getX() - waypointCenter.x;
        var dz = mob.getZ() - waypointCenter.z;
        var horizontalDistSqr = dx * dx + dz * dz;
        var yError = waypointCenter.y - mob.getY();

        if (!shouldUseCrawling) {
            return mob.position().distanceToSqr(waypointCenter) < 0.35D;
        }

        if (horizontalDistSqr > 1.15D * 1.15D) {
            return false;
        }

        return !(yError > 0.45D);
    }

    private void applyPathMovement(E mob, LivingEntity target, Vec3 waypoint, Vec3 direction) {
        if (blockBreakCooldown > 0) {
            blockBreakCooldown--;
        }

        if (dangerLeapCooldown > 0) {
            dangerLeapCooldown--;
        }

        var movedSqr = mob.position().distanceToSqr(lastPos);
        lastPos = mob.position();

        if (movedSqr < 0.0025D) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }

        var shouldPathUseCrawling = CrawlingManager.shouldUseWallCrawlingToTarget(mob, target)
            || CrawlingManager.isWallCrawling(mob);
        var canAttachToWall = shouldPathUseCrawling && canAttachToClimbSurface(mob, waypoint);

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
            } else if (Math.abs(yError) > 0.10D) {
                var verticalSpeed = Mth.clamp(yError * 0.45D, -speed * 0.45D, speed);
                crawlVelocity = new Vec3(intoWall.x, verticalSpeed, intoWall.z);
            } else if (horizontal.lengthSqr() > 0.01D) {
                var moveDir = horizontal.normalize().scale(speed);
                crawlVelocity = new Vec3(
                    moveDir.x + intoWall.x,
                    intoWall.y,
                    moveDir.z + intoWall.z
                );
            } else {
                crawlVelocity = MovementUtils.computeWallCrawlVelocity(mob, waypoint, speed);
            }

            if (crawlVelocity.lengthSqr() < 0.0001D) {
                crawlVelocity = Vec3.ZERO;
            }

            CrawlingManager.setWallCrawling(mob, true);
            CrawlingManager.updateCrawlOrientation(mob, crawlVelocity);

            mob.setDeltaMovement(crawlVelocity);
            mob.hasImpulse = true;
            faceMovementDirection(mob, crawlVelocity);
            return;
        }

        CrawlingManager.setWallCrawling(mob, false);

        if (shouldPathUseCrawling) {
            var approach = new Vec3(direction.x, 0.0D, direction.z);

            if (approach.lengthSqr() > 0.01D) {
                var movement = approach.normalize().scale(speed);

                if (canAttachToClimbSurface(mob, waypoint)) {
                    CrawlingManager.setWallCrawling(mob, true);

                    var climbVelocity = new Vec3(
                        movement.x * 0.35D,
                        speed,
                        movement.z * 0.35D
                    );

                    CrawlingManager.updateCrawlOrientation(mob, climbVelocity);

                    mob.setDeltaMovement(climbVelocity);
                    mob.hasImpulse = true;
                    faceMovementDirection(mob, climbVelocity);
                    return;
                }

                CrawlingManager.setWallCrawling(mob, false);

                mob.setDeltaMovement(
                    movement.x,
                    mob.getDeltaMovement().y,
                    movement.z
                );
                mob.hasImpulse = true;
                faceMovementDirection(mob, movement);
                return;
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
            if (shouldPathUseCrawling && direction.y > 0.1D) {
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
                    blockBreakCooldown = BLOCK_BREAK_COOLDOWN_TICKS;
                    stuckTicks = 0;
                    faceTarget(mob, target);
                    return;
                }
            }

            var left = new Vec3(-forward.z, 0.0D, forward.x);
            var right = new Vec3(forward.z, 0.0D, -forward.x);

            if (!targetBelow && stuckTicks > BLOCK_BREAK_STUCK_TICKS && blockBreakCooldown <= 0) {
                if (tryBreakBlockingPathBlock(mob, target, forward)) {
                    blockBreakCooldown = BLOCK_BREAK_COOLDOWN_TICKS;
                    stuckTicks = 0;
                    faceTarget(mob, target);
                    return;
                }
            }

            if (
                stuckTicks >= DANGER_LEAP_STUCK_TICKS
                    && dangerLeapCooldown <= 0
                    && mob.onGround()
                    && MovementUtils.hasNearbyDangerEntity(mob)
            ) {
                var leapDirection = new Vec3(movement.x, 0.0D, movement.z);

                if (leapDirection.lengthSqr() < 0.0001D) {
                    leapDirection = forward;
                }

                if (MovementUtils.hasSafeLandingAfterLeap(mob, leapDirection, DANGER_LEAP_DISTANCE)) {
                    var leap = leapDirection.normalize().scale(DANGER_LEAP_HORIZONTAL_POWER);

                    mob.setDeltaMovement(
                        leap.x,
                        DANGER_LEAP_VERTICAL_POWER,
                        leap.z
                    );

                    mob.hasImpulse = true;
                    dangerLeapCooldown = DANGER_LEAP_COOLDOWN_TICKS;
                    stuckTicks = 0;
                    detourTicks = 0;

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
                faceTarget(mob, target);
                return;
            }
        }

        var safe = MovementUtils.findSafeMovement(mob, movement, steerBias);

        if (safe.equals(Vec3.ZERO)) {
            halt(mob);
            faceTarget(mob, target);
            return;
        }

        mob.setDeltaMovement(safe.x, mob.getDeltaMovement().y, safe.z);
        mob.hasImpulse = true;
        faceTarget(mob, target);

        AiDebugUtils.sendParticlePath(
            mob,
            mob.position(),
            target.position()
        );
    }

    private void halt(E mob) {
        mob.setDeltaMovement(0.0D, mob.getDeltaMovement().y, 0.0D);
        mob.hasImpulse = false;
    }

    /**
     * Snaps a crawl waypoint from the block center to the nearest wall face, so the mob stays flush against the surface
     * instead of floating 0.5 blocks out.
     */
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

    /**
     * Finds the horizontal direction toward the nearest solid wall face, used to push the mob into a climbable surface
     * when the waypoint is directly above.
     */
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

    private void faceTarget(E mob, LivingEntity target) {
        var dx = target.getX() - mob.getX();
        var dz = target.getZ() - mob.getZ();
        var yaw = (float) (Math.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;

        mob.setYRot(yaw);
        mob.yBodyRot = yaw;
        mob.yHeadRot = yaw;
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
    }

    private boolean tryBreakBlockingPathBlock(E mob, LivingEntity target, Vec3 forward) {
        var level = mob.level();

        var checkPos = mob.position().add(forward.scale(0.9D));

        var feet = BlockPos.containing(
            checkPos.x,
            mob.getBoundingBox().minY,
            checkPos.z
        );

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

        if (state.isAir()) {
            return false;
        }

        if (state.getDestroySpeed(level, pos) < 0.0F) {
            return false;
        }

        if (state.getCollisionShape(level, pos).isEmpty()) {
            return false;
        }

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

        return MovementUtils.isSafeBlock(level, landingFeet)
            && MovementUtils.isSafeBlock(level, landingGround);
    }

    private void faceMovementDirection(E mob, Vec3 movement) {
        if (movement.horizontalDistanceSqr() < 0.0001D) {
            return;
        }

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

    private boolean canAttachToClimbSurface(E mob, Vec3 waypoint) {
        if (MovementUtils.needsWallCrawl(mob, waypoint)) {
            return true;
        }

        if (mob.horizontalCollision) {
            return true;
        }

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
}
