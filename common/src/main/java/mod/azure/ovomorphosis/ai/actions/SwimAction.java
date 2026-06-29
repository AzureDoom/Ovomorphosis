package mod.azure.ovomorphosis.ai.actions;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

import mod.azure.ovomorphosis.ai.core.*;

public record SwimAction<E extends Mob>(int priority) implements Action<E> {

    @Override
    public void start(E mob, Blackboard blackboard, Cooldowns cooldowns) {}

    @Override
    public ActionStatus tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        if (mob.isDeadOrDying())
            return ActionStatus.SUCCESS;
        if (!mob.isInWater() && !mob.isInLava())
            return ActionStatus.SUCCESS;

        var target = blackboard.get(AiKeys.TARGET, LivingEntity.class);
        var destPos = target != null && target.isAlive()
            ? target.position().add(0, target.getBbHeight() * 0.5, 0)
            : blackboard.get(AiKeys.DESTINATION, BlockPos.class) != null
                ? Vec3.atBottomCenterOf(Objects.requireNonNull(blackboard.get(AiKeys.DESTINATION, BlockPos.class)))
                : null;

        if (destPos != null) {
            var toTarget = destPos.subtract(mob.position());
            var dist = toTarget.length();

            if (dist > 0.5D) {
                var movement = toTarget.normalize().scale(0.22D);
                mob.setDeltaMovement(movement);
                mob.hasImpulse = true;
                faceMovementDirection(mob, movement);
            } else {
                mob.setDeltaMovement(Vec3.ZERO);
            }
        } else {
            var current = mob.getDeltaMovement();
            mob.setDeltaMovement(current.x * 0.5, 0.03, current.z * 0.5);
        }

        mob.setDeltaMovement(mob.getDeltaMovement().multiply(0.8, 0.8, 0.8));
        return ActionStatus.RUNNING;
    }

    @Override
    public void stop(E mob, Blackboard blackboard, Cooldowns cooldowns, ActionStatus reason) {}

    @Override
    public boolean isInterruptible() {
        return true;
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
}
