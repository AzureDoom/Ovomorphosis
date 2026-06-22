package mod.azure.ovomorphosis.ai.hive;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

/**
 * An immutable directive issued to a single mob by a {@link TacticalCoordinator}.
 * <p>
 * The order specifies the mob's tactical role, which entity to attack, where to move, and how urgently it should act.
 * Actions that consume tactical orders should call {@link #hasTarget()} and {@link #hasDestination()} before using the
 * respective fields.
 *
 * @param role        the {@link TacticalRole} assigned to the mob for this order
 * @param target      the entity the mob should attack, or {@code null} if no target is assigned
 * @param destination the block position the mob should move toward, or {@code null}
 * @param priority    the numeric priority of this order; higher values override lower ones
 */
public record TacticalOrder(
    TacticalRole role,
    LivingEntity target,
    BlockPos destination,
    int priority
) {

    /**
     * Returns an empty order with no role, target, or destination and zero priority.
     *
     * @return a no-op tactical order
     */
    public static TacticalOrder none() {
        return new TacticalOrder(null, null, null, 0);
    }

    /**
     * Returns {@code true} if this order includes a living target.
     *
     * @return {@code true} if {@link #target()} is non-null and alive
     */
    public boolean hasTarget() {
        return target != null && target.isAlive();
    }

    /**
     * Returns {@code true} if this order includes a movement destination.
     *
     * @return {@code true} if {@link #destination()} is non-null
     */
    public boolean hasDestination() {
        return destination != null;
    }
}
