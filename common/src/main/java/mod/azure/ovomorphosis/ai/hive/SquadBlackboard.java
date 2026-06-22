package mod.azure.ovomorphosis.ai.hive;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import java.util.*;

/**
 * Shared mutable state for a group of mobs fighting together as a squad.
 * <p>
 * One {@code SquadBlackboard} exists per squad and is managed by {@link SquadRegistry}. All squad members read from and
 * write to the same instance, so field access is intentionally package-visible to keep coordination code concise.
 */
public final class SquadBlackboard {

    /** Enemies sorted by proximity to the squad centroid; index 0 is the primary target. */
    public List<LivingEntity> targetPriority = new ArrayList<>();

    /** Maps each {@link TacticalRole} to the specific entity that role should attack. */
    public Map<TacticalRole, LivingEntity> roleTargets = new EnumMap<>(TacticalRole.class);

    /** Maps each squad member UUID to their currently assigned {@link TacticalRole}. */
    public Map<UUID, TacticalRole> roles = new HashMap<>();

    /** Block positions already claimed by squad members this tick, preventing stacking. */
    public Set<BlockPos> reservedPositions = new HashSet<>();

    /** Current number of living members tracked in this squad. */
    public int squadSize = 1;

    /** Game tick on which {@link #reservedPositions} was last cleared. */
    public long lastReservationTick = -1;

    /** Game tick on which the target priority list was last re-evaluated. */
    public long lastTargetEvalTick = -1;

    /**
     * Returns the highest-priority target, or {@code null} if the list is empty.
     *
     * @return the first entry in {@link #targetPriority}, or {@code null}
     */
    public LivingEntity primaryTarget() {
        return targetPriority == null || targetPriority.isEmpty()
            ? null
            : targetPriority.getFirst();
    }

    /**
     * Returns the second-highest-priority target, or {@code null} if fewer than two targets exist.
     *
     * @return the second entry in {@link #targetPriority}, or {@code null}
     */
    public LivingEntity secondaryTarget() {
        return targetPriority == null || targetPriority.size() <= 1
            ? null
            : targetPriority.get(1);
    }

    /**
     * Returns {@code true} if the squad has a primary target that is still alive.
     *
     * @return {@code true} if {@link #primaryTarget()} is non-null and alive
     */
    public boolean hasPrimaryTarget() {
        var t = primaryTarget();
        return t != null && t.isAlive();
    }
}
