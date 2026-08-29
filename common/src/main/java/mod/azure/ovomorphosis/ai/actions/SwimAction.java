package mod.azure.ovomorphosis.ai.actions;

import com.azure.azurecortex.api.action.Action;
import com.azure.azurecortex.api.action.ActionOutcome;
import com.azure.azurecortex.api.action.ActionStatus;
import com.azure.azurecortex.api.blackboard.Blackboard;
import com.azure.azurecortex.api.blackboard.CommonBlackboardKeys;
import com.azure.azurecortex.navigation.traversal.TraversalQueries;
import com.azure.azurecortex.runtime.CooldownTracker;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

public record SwimAction<E extends Mob, G>(
    int priority,
    boolean pursueTarget
) implements Action<E, G> {

    /**
     * Convenience constructor for predator mobs (Xenomorph, Runner, Facehugger) whose {@code AiKeys.TARGET} is
     * something to chase — swimming toward it is the desired behavior.
     */
    public SwimAction(int priority) {
        this(priority, true);
    }

    /**
     * Ticks a mob must be continuously idle (in water, no target, no destination) before {@link #tick} starts actively
     * seeking the nearest shore instead of just leaving it to bob in place like normal idle behavior.
     */
    private static final long STRANDED_GRACE_TICKS = 60L;

    @Override
    public void start(E mob, Blackboard blackboard, CooldownTracker cooldowns) {}

    @Override
    public ActionOutcome<G> tick(E mob, Blackboard blackboard, CooldownTracker cooldowns) {
        if (mob.isDeadOrDying())
            return ActionOutcome.success();
        if (!mob.isInWater() && !mob.isInLava())
            return ActionOutcome.success();

        var target = pursueTarget ? blackboard.get(CommonBlackboardKeys.TARGET) : null;
        var destPos = target != null && target.isAlive()
            ? target.position().add(0, target.getBbHeight() * 0.5, 0)
            : blackboard.get(CommonBlackboardKeys.DESTINATION) != null
                ? Vec3.atBottomCenterOf(Objects.requireNonNull(blackboard.get(CommonBlackboardKeys.DESTINATION)))
                : null;

        if (destPos == null) {
            var now = (int) mob.level().getGameTime();
            var strandedSince = blackboard.get(CommonBlackboardKeys.SWIM_STRANDED_SINCE_TICK);

            if (strandedSince == null) {
                blackboard.set(CommonBlackboardKeys.SWIM_STRANDED_SINCE_TICK, now);
            } else if (now - strandedSince >= STRANDED_GRACE_TICKS) {
                var shore = TraversalQueries.findNearbyGroundPos(mob);
                if (shore != null) {
                    destPos = Vec3.atBottomCenterOf(shore);
                }
            }
        } else {
            blackboard.remove(CommonBlackboardKeys.SWIM_STRANDED_SINCE_TICK);
        }

        if (destPos != null) {
            var toTarget = destPos.subtract(mob.position());
            var dist = toTarget.length();

            if (dist > 0.5D) {
                var movement = toTarget.normalize().scale(0.22D);

                var climbingLedge = false;
                if (mob.horizontalCollision) {
                    var liftedBox = mob.getBoundingBox().move(0.0D, 0.6D, 0.0D);
                    if (mob.level().noCollision(mob, liftedBox)) {
                        movement = new Vec3(movement.x, 0.5D, movement.z);
                        climbingLedge = true;
                    }
                }

                mob.setDeltaMovement(movement);
                mob.hasImpulse = true;
                faceMovementDirection(mob, movement);

                if (climbingLedge) {
                    mob.setDeltaMovement(mob.getDeltaMovement().x * 0.8, movement.y, mob.getDeltaMovement().z * 0.8);
                    return ActionOutcome.running();
                }
            } else {
                mob.setDeltaMovement(Vec3.ZERO);
            }
        } else {
            var current = mob.getDeltaMovement();
            mob.setDeltaMovement(current.x * 0.5, 0.03, current.z * 0.5);
        }

        mob.setDeltaMovement(mob.getDeltaMovement().multiply(0.8, 0.8, 0.8));
        return ActionOutcome.running();
    }

    @Override
    public void stop(E mob, Blackboard blackboard, CooldownTracker cooldowns, ActionStatus reason) {}

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
