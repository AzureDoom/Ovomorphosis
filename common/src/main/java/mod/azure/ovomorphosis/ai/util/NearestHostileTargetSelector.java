package mod.azure.ovomorphosis.ai.util;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.Comparator;

import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.Blackboard;

/**
 * A {@link TargetSelector} that retains the current target if it is still valid, or scans for the nearest hostile
 * {@code LivingEntity} within a fixed range.
 * <p>
 * Validity is determined by {@link TargetingUtils#validTarget}. The scan uses a bounding-box inflation of {@code range}
 * blocks centred on the mob, then picks the closest result.
 *
 * @param <E> the mob type performing the search
 */
public final class NearestHostileTargetSelector<E extends Mob> implements TargetSelector<E> {

    /** Maximum distance (blocks) from the mob within which enemies are scanned. */
    private final double range;

    /**
     * Creates a new selector with the given scan radius.
     *
     * @param range maximum distance in blocks to search for a target
     */
    public NearestHostileTargetSelector(double range) {
        this.range = range;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Returns the existing blackboard target if still valid; otherwise scans within {@link #range} blocks for the
     * nearest valid entity.
     */
    @Override
    public LivingEntity findTarget(E mob, Blackboard blackboard) {
        var current = blackboard.get(AiKeys.TARGET, LivingEntity.class);

        if (TargetingUtils.validTarget(mob).test(current)) {
            return current;
        }

        return mob.level()
            .getEntitiesOfClass(
                LivingEntity.class,
                mob.getBoundingBox().inflate(range),
                entity -> TargetingUtils.validTarget(mob).test(entity)
            )
            .stream()
            .min(Comparator.comparingDouble(mob::distanceToSqr))
            .orElse(null);
    }
}
