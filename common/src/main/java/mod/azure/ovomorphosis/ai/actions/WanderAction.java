package mod.azure.ovomorphosis.ai.actions;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import mod.azure.ovomorphosis.ai.core.*;
import mod.azure.ovomorphosis.ai.util.AiDebugUtils;
import mod.azure.ovomorphosis.ai.util.MovementUtils;

public final class WanderAction<E extends Mob> implements Action<E> {

    private static final int DESTINATION_ATTEMPTS = 16;

    private final double speed;

    private final int priority;

    private final int minDuration;

    private final int maxDuration;

    private final double radius;

    private final int[] steerBias = { 0 };

    private Vec3 destination;

    private int age;

    private int duration;

    public WanderAction(double speed, int priority, double radius, int minDuration, int maxDuration) {
        this.speed = speed;
        this.priority = priority;
        this.radius = radius;
        this.minDuration = minDuration;
        this.maxDuration = maxDuration;
    }

    @Override
    public void start(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        this.age = 0;
        this.duration = minDuration + mob.getRandom().nextInt(maxDuration - minDuration + 1);
        this.destination = findWanderDestination(mob);
        mob.setAggressive(false);
        cooldowns.set(AiKeys.PASSIVE_DECISION, 180);
    }

    @Override
    public ActionStatus tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        if (mob.getHealth() <= 0) {
            return ActionStatus.INTERRUPTED;
        }

        var target = blackboard.get(AiKeys.TARGET, LivingEntity.class);
        if (target != null && target.isAlive()) {
            return ActionStatus.INTERRUPTED;
        }

        age++;

        if (destination == null) {
            destination = findWanderDestination(mob);
            if (destination == null) {
                return ActionStatus.RUNNING;
            }
        }

        var delta = destination.subtract(mob.position());
        if (delta.lengthSqr() < 0.5D || age >= duration) {
            return ActionStatus.SUCCESS;
        }

        var movement = delta.normalize().scale(speed);
        var safeMovement = MovementUtils.findSafeMovement(mob, movement, steerBias);

        if (safeMovement.equals(Vec3.ZERO)) {
            return ActionStatus.FAILURE;
        }

        mob.setDeltaMovement(safeMovement.x, mob.getDeltaMovement().y, safeMovement.z);
        mob.hasImpulse = true;
        faceMovementDirection(mob, safeMovement);

        AiDebugUtils.sendParticlePath(
            mob,
            mob.position(),
            destination
        );
        return ActionStatus.RUNNING;
    }

    @Override
    public void stop(E mob, Blackboard blackboard, Cooldowns cooldowns, ActionStatus reason) {
        mob.setDeltaMovement(
            mob.getDeltaMovement().x * 0.25D,
            mob.getDeltaMovement().y,
            mob.getDeltaMovement().z * 0.25D
        );
        cooldowns.set(AiKeys.PASSIVE_DECISION, 1);
    }

    @Override
    public boolean isInterruptible() {
        return true;
    }

    @Override
    public int priority() {
        return priority;
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
                mob.getEyeY(),
                mob.getZ() + movement.z
            );
    }

    private Vec3 findWanderDestination(E mob) {
        var origin = mob.blockPosition();

        for (var i = 0; i < DESTINATION_ATTEMPTS; i++) {
            var xOffset = (mob.getRandom().nextDouble() * 2.0D - 1.0D) * radius;
            var zOffset = (mob.getRandom().nextDouble() * 2.0D - 1.0D) * radius;

            var candidate = BlockPos.containing(
                origin.getX() + xOffset,
                origin.getY(),
                origin.getZ() + zOffset
            );

            var ground = findSafeGround(mob, candidate);
            if (ground != null) {
                return Vec3.atCenterOf(ground);
            }
        }

        return null;
    }

    private BlockPos findSafeGround(E mob, BlockPos candidate) {
        var level = mob.level();

        for (var yOffset = 3; yOffset >= -4; yOffset--) {
            var feet = candidate.offset(0, yOffset, 0);
            var below = feet.below();
            var head = feet.above();

            if (isSafeStandPosition(level, feet, head, below)) {
                return feet;
            }
        }

        return null;
    }

    private boolean isSafeStandPosition(Level level, BlockPos feet, BlockPos head, BlockPos below) {
        if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty())
            return false;

        if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty())
            return false;

        if (level.getBlockState(below).getCollisionShape(level, below).isEmpty())
            return false;

        return MovementUtils.isSafeBlock(level, feet)
            && MovementUtils.isSafeBlock(level, head);
    }
}
