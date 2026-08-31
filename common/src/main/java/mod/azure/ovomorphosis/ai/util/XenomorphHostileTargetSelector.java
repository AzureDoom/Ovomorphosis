package mod.azure.ovomorphosis.ai.util;

import com.azure.azurecortex.api.blackboard.Blackboard;
import com.azure.azurecortex.api.blackboard.CommonBlackboardKeys;
import com.azure.azurecortex.sensing.TargetSensor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;

import mod.azure.ovomorphosis.entities.AbstractAlienEntity;

/**
 * {@link TargetSensor.Selector} shared by {@code XenomorphEntity} and {@code RunnerEntity}.
 * <p>
 * A brand-new target is only ever acquired from what the mob can actually perceive: genuine, unobstructed line of sight
 * to a valid candidate somewhere within {@link #range} blocks. This deliberately does <em>not</em> fall back to
 * "anything within range, sight or no sight" the way a plain distance scan would — a candidate hiding behind a wall or
 * around a corner is invisible to this selector no matter how close it is, exactly like it would be to the mob itself.
 * <p>
 * Once a target is acquired, it's retained a little more generously via {@link #canSense} (line of sight OR close
 * enough to notice by proximity alone) so a target that ducks out of view for a moment isn't dropped instantly. When a
 * target is lost outright, {@code TargetSensor}'s own visibility-gated {@code LAST_SEEN_POS}/{@code LAST_SEEN_TICK}
 * tracking (see the {@code TargetSensor.lineOfSight()} predicate both entities are constructed with) is what backs the
 * goal planner's INVESTIGATE behavior — this selector doesn't need to reimplement any of that itself.
 * <p>
 * When nothing is currently visible, this selector falls back to clues instead of knowing where a hidden target is:
 * {@link #hearSound} queues the position of recent significant noises (see the {@code onGameEvent} hooks on
 * {@code XenomorphEntity}/{@code RunnerEntity}), and {@link #findTarget} pops the nearest one onto
 * {@link CommonBlackboardKeys#DESTINATION} so the mob walks over to investigate — giving it a genuine chance to spot
 * whatever made the noise once it's close enough, rather than teleporting its attention straight to the source.
 */
public final class XenomorphHostileTargetSelector<E extends AbstractAlienEntity> implements TargetSensor.Selector<E> {

    /**
     * How close a candidate must be, in blocks, for an already-acquired target to be retained without genuine line of
     * sight — close enough that the mob would still notice it by proximity (scent, sound, touch) even if a thin
     * obstruction is technically blocking a clean sightline.
     */
    private static final double CLOSE_SENSE_RANGE = 4.0D;

    private final double range;

    private final ArrayDeque<BlockPos> heardQueue = new ArrayDeque<>();

    public XenomorphHostileTargetSelector(double range) {
        this.range = range;
    }

    public void hearSound(Vec3 pos) {
        var incoming = BlockPos.containing(pos);

        for (var queued : heardQueue) {
            var dx = queued.getX() - incoming.getX();
            var dy = queued.getY() - incoming.getY();
            var dz = queued.getZ() - incoming.getZ();
            if (dx * dx + dy * dy + dz * dz < 4.0 * 4.0) {
                return;
            }
        }

        if (heardQueue.size() >= 15) {
            heardQueue.pollFirst();
        }

        heardQueue.addLast(incoming);
    }

    @Override
    public LivingEntity findTarget(E mob, Blackboard blackboard) {
        var current = blackboard.get(CommonBlackboardKeys.TARGET);

        if (
            current != null && current.isAlive()
                && TargetingUtils.validTarget(mob).test(current)
                && canSense(mob, current)
        ) {
            return current;
        }

        var spotted = findVisibleCandidate(mob);

        if (spotted != null) {
            heardQueue.clear();
            return spotted;
        }

        if (!heardQueue.isEmpty() && blackboard.get(CommonBlackboardKeys.DESTINATION) == null) {
            var nextPos = heardQueue.pollFirst();
            blackboard.set(CommonBlackboardKeys.DESTINATION, nextPos);
        }

        return null;
    }

    /**
     * Whether {@code mob} can currently perceive {@code candidate} well enough to keep hunting it — genuine,
     * unobstructed line of sight, or close enough that it would notice regardless of a clean sightline.
     */
    private boolean canSense(E mob, LivingEntity candidate) {
        if (mob.hasLineOfSight(candidate)) {
            return true;
        }
        return mob.distanceToSqr(candidate) <= CLOSE_SENSE_RANGE * CLOSE_SENSE_RANGE;
    }

    /**
     * Scans for the nearest valid candidate within {@link #range} that {@code mob} has direct line of sight to right
     * now. Replaces the old omniscient behavior of picking the nearest valid entity in range regardless of visibility.
     */
    private LivingEntity findVisibleCandidate(E mob) {
        var nearby = mob.level()
            .getEntitiesOfClass(
                LivingEntity.class,
                mob.getBoundingBox().inflate(range),
                candidate -> isValidCandidate(mob, candidate)
            );

        LivingEntity closest = null;
        double closestDistSq = Double.MAX_VALUE;
        for (var candidate : nearby) {
            if (!mob.hasLineOfSight(candidate)) {
                continue;
            }
            var distSq = mob.distanceToSqr(candidate);
            if (distSq < closestDistSq) {
                closestDistSq = distSq;
                closest = candidate;
            }
        }
        return closest;
    }

    private boolean isValidCandidate(E mob, LivingEntity candidate) {
        if (!TargetingUtils.validTarget(mob).test(candidate)) {
            return false;
        }
        var distSq = mob.distanceToSqr(candidate);
        return distSq <= range * range;
    }

    public void onTargetKilled() {
        heardQueue.clear();
    }
}
