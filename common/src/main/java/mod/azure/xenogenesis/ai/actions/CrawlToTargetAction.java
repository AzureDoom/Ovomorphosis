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
            mob.setDeltaMovement(mob.getDeltaMovement().scale(0.5D));
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
        var shouldUseCrawlingNow = CrawlingManager.shouldUseWallCrawlingTo(mob, target)
            || isCrawlingNow;

        var repathInterval = isCrawlingNow ? 20 : 10;

        if (repathCooldown <= 0 || path.isEmpty() || pathIndex >= path.size()) {
            var goalRadius = 1;

            path = CrawlingCustomAStar.findPath(
                mob,
                mob.blockPosition(),
                target.blockPosition(),
                64,
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

            if (
                shouldUseCrawlingNow
                    && target.getY() > mob.getY() + 1.0D
                    && pathIndex < path.size()
            ) {
                while (
                    pathIndex < path.size() - 1
                        && path.get(pathIndex).getY() <= mob.blockPosition().getY()
                ) {
                    pathIndex++;
                }
            }

            if (pathIndex < path.size()) {
                var waypoint = Vec3.atBottomCenterOf(path.get(pathIndex));
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
        CrawlingManager.setWallCrawling(mob, false);
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

        if (yError > 0.45D) {
            return false;
        }

        return true;
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

        var shouldPathUseCrawling = CrawlingManager.shouldUseWallCrawlingTo(mob, target)
            || CrawlingManager.isWallCrawling(mob);
        var canAttachToWall = shouldPathUseCrawling && canAttachToClimbSurface(mob, waypoint);

        if (canAttachToWall) {
            var crawlVelocity = MovementUtils.computeWallCrawlVelocity(mob, waypoint, speed);

            var waypointY = waypoint.y;
            var targetAbove = target.getY() > mob.getY() + 0.75D;

            if (targetAbove) {
                var horizontal = new Vec3(direction.x, 0.0D, direction.z);
                var intoWall = Vec3.ZERO;

                if (horizontal.lengthSqr() > 0.01D) {
                    intoWall = horizontal.normalize().scale(speed * 0.12D);
                }
                crawlVelocity = new Vec3(intoWall.x, speed * 0.85D, intoWall.z);
            } else {
                var yError = waypointY - mob.getY();

                var horizontal = new Vec3(direction.x, 0.0D, direction.z);
                var intoWall = Vec3.ZERO;

                if (horizontal.lengthSqr() > 0.01D) {
                    intoWall = horizontal.normalize().scale(speed * 0.12D);
                }

                if (Math.abs(yError) > 0.10D) {
                    var verticalSpeed = Mth.clamp(
                        yError * 0.45D,
                        -speed * 0.45D,
                        speed
                    );

                    crawlVelocity = new Vec3(intoWall.x, verticalSpeed, intoWall.z);
                }
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
        return !level.noBlockCollision(mob, box.move(checkDistance, 0.0D, 0.0D));
    }
}
