package mod.azure.xenogenesis.ai.actions;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import mod.azure.xenogenesis.ai.core.*;
import mod.azure.xenogenesis.ai.util.AiDebugUtils;
import mod.azure.xenogenesis.ai.util.MovementUtils;

public final class MoveToDestinationAction<E extends Mob> implements Action<E> {

    private final double stopDistanceSqr;

    private final double speed;

    private final int priority;

    private final double maxLeapHeight;

    private final double minLeapHeight;

    private final double leapVerticalPower;

    private final double leapHorizontalPower;

    private final int[] steerBias = { 0 };

    public MoveToDestinationAction(
        double stopDistance,
        double speed,
        int priority,
        double maxLeapHeight,
        double minLeapHeight,
        double leapVerticalPower,
        double leapHorizontalPower
    ) {
        this.stopDistanceSqr = stopDistance * stopDistance;
        this.speed = speed;
        this.priority = priority;
        this.maxLeapHeight = maxLeapHeight;
        this.minLeapHeight = minLeapHeight;
        this.leapVerticalPower = leapVerticalPower;
        this.leapHorizontalPower = leapHorizontalPower;
    }

    @Override
    public void start(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        mob.setAggressive(true);
    }

    @Override
    public ActionStatus tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        if (mob.getHealth() <= 0) {
            mob.setAggressive(false);
            return ActionStatus.INTERRUPTED;
        }

        var destination = blackboard.get(AiKeys.DESTINATION, BlockPos.class);
        if (destination == null) {
            mob.setAggressive(false);
            return ActionStatus.INTERRUPTED;
        }

        var destVec = Vec3.atBottomCenterOf(destination);
        var direction = destVec.subtract(mob.position());

        if (mob.distanceToSqr(destVec) <= stopDistanceSqr) {
            mob.setDeltaMovement(mob.getDeltaMovement().scale(0.4D));
            return ActionStatus.SUCCESS;
        }

        var horizontal = new Vec3(direction.x, 0.0D, direction.z);
        if (horizontal.lengthSqr() < 0.0001D) {
            return ActionStatus.SUCCESS;
        }

        var yDiff = destination.getY() - mob.getY();

        if (
            yDiff >= minLeapHeight && yDiff <= maxLeapHeight
                && mob.onGround()
                && horizontal.lengthSqr() > 0.01D
                && !cooldowns.isOnCooldown(AiKeys.LEAP_COOLDOWN)
        ) {

            var leap = horizontal.normalize().scale(leapHorizontalPower);
            mob.setDeltaMovement(leap.x, leapVerticalPower, leap.z);
            mob.hasImpulse = true;
            cooldowns.set(AiKeys.LEAP_COOLDOWN, 20);
            return ActionStatus.RUNNING;
        }

        var movement = MovementUtils.steerAwayFromDangerEntities(
            mob,
            horizontal.normalize().scale(speed)
        );
        var safe = MovementUtils.findSafeMovement(mob, movement, steerBias);

        if (safe.equals(Vec3.ZERO)) {
            mob.setDeltaMovement(0.0D, mob.getDeltaMovement().y, 0.0D);
            mob.hasImpulse = false;
            return ActionStatus.RUNNING;
        }

        mob.setDeltaMovement(safe.x, mob.getDeltaMovement().y, safe.z);
        mob.hasImpulse = true;

        var dx = destination.getX() + 0.5 - mob.getX();
        var dz = destination.getZ() + 0.5 - mob.getZ();
        var yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        mob.setYRot(yaw);
        mob.yBodyRot = yaw;
        mob.yHeadRot = yaw;

        AiDebugUtils.sendParticlePath(
            mob,
            mob.position(),
            destVec
        );
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
}
