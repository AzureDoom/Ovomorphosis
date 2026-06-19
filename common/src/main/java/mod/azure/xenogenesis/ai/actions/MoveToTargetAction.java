package mod.azure.xenogenesis.ai.actions;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.List;

import mod.azure.xenogenesis.ai.core.*;
import mod.azure.xenogenesis.ai.util.AiDebugUtils;
import mod.azure.xenogenesis.ai.util.CustomAStar;
import mod.azure.xenogenesis.ai.util.MovementUtils;

public final class MoveToTargetAction<E extends Mob> implements Action<E> {

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

    public MoveToTargetAction(
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

        var yDiff = target.getY() - mob.getY();

        if (yDiff > maxLeapHeight) {
            blackboard.set(AiKeys.TARGET, null);
            mob.setTarget(null);
            mob.setAggressive(false);
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

        if (repathCooldown <= 0 || path.isEmpty() || pathIndex >= path.size()) {
            var goalRadius = 1;

            path = CustomAStar.findPath(
                mob,
                mob.blockPosition(),
                target.blockPosition(),
                64,
                goalRadius
            );

            pathIndex = path.size() > 1 ? 1 : 0;
            repathCooldown = 10;
        }

        if (!path.isEmpty()) {
            while (
                pathIndex < path.size() && mob.position()
                    .distanceToSqr(Vec3.atBottomCenterOf(path.get(pathIndex))) < 1.0D
            ) {
                pathIndex++;
            }

            if (pathIndex < path.size()) {
                var waypoint = Vec3.atBottomCenterOf(path.get(pathIndex));
                var direction = waypoint.subtract(mob.position());

                if (direction.lengthSqr() > 0.0001D) {
                    applyFlatMovement(mob, target, direction);
                    return ActionStatus.RUNNING;
                }
            }
        }

        var directDirection = target.position().subtract(mob.position());

        if (directDirection.lengthSqr() > 0.0001D) {
            applyFlatMovement(mob, target, directDirection);
            return ActionStatus.RUNNING;
        }

        halt(mob);
        faceTarget(mob, target);
        return ActionStatus.RUNNING;
    }

    @Override
    public void stop(E mob, Blackboard blackboard, ActionStatus reason) {
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

    private void applyFlatMovement(E mob, LivingEntity target, Vec3 direction) {
        if (blockBreakCooldown > 0) {
            blockBreakCooldown--;
        }
        if (dangerLeapCooldown > 0) {
            dangerLeapCooldown--;
        }
        var horizontal = new Vec3(direction.x, 0.0D, direction.z);

        if (horizontal.lengthSqr() < 0.01D) {
            halt(mob);
            return;
        }

        var forward = horizontal.normalize();
        var movement = MovementUtils.steerAwayFromDangerEntities(mob, forward.scale(speed));

        var movedSqr = mob.position().distanceToSqr(lastPos);
        lastPos = mob.position();

        if (movedSqr < 0.0025D) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }

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
}
