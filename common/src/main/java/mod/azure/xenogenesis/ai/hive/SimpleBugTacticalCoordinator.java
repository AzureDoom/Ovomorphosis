package mod.azure.xenogenesis.ai.hive;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;

import java.util.EnumMap;

/**
 * A {@link TacticalCoordinator} tailored for insect-type enemies that fight in swarms.
 * <p>
 * Divides squad members across three roles using fixed ratio targets:
 * <ul>
 * <li>40 % {@link TacticalRole#FRONTLINE} — charge the primary target directly.</li>
 * <li>40 % {@link TacticalRole#FLANKER} — attack from the side or peel onto a secondary target when within
 * {@code FLANKER_PEEL_RANGE} blocks.</li>
 * <li>20 % {@link TacticalRole#SUPPORT} — harass from a safe offset position.</li>
 * </ul>
 * Position reservations prevent multiple mobs from stacking on the same block in a single tick.
 *
 * @param <E> the mob type being coordinated
 */
public final class SimpleBugTacticalCoordinator<E extends Mob> implements TacticalCoordinator<E> {

    /** Distance (blocks) within which a flanker switches from the primary to the secondary target. */
    private static final double FLANKER_PEEL_RANGE = 20.0;

    private static final double FLANKER_PEEL_RANGE_SQ = FLANKER_PEEL_RANGE * FLANKER_PEEL_RANGE;

    /** Perpendicular offset (blocks) applied to the flanker's destination relative to the target. */
    private static final int FLANKER_LATERAL_OFFSET = 4;

    /** Fraction of the squad that should be assigned the frontline role. */
    private static final double RATIO_FRONTLINE = 0.40;

    /** Fraction of the squad that should be assigned the flanker role. */
    private static final double RATIO_FLANKER = 0.40;

    /** Fraction of the squad that should be assigned the support role. */
    private static final double RATIO_SUPPORT = 0.20;

    /**
     * {@inheritDoc}
     * <p>
     * Clears expired reservations once per tick, assigns or looks up the mob's role, resolves the target entity for
     * that role, and computes a destination block that avoids positions already claimed by other squad members this
     * tick.
     */
    @Override
    public TacticalOrder getOrder(E mob, SquadBlackboard squad) {
        var currentTick = mob.level().getGameTime();
        var primary = squad.primaryTarget();

        if (primary == null || !primary.isAlive()) {
            squad.targetPriority.clear();
            squad.roleTargets.clear();
            return TacticalOrder.none();
        }
        if (squad.lastReservationTick != currentTick) {
            squad.reservedPositions.clear();
            squad.lastReservationTick = currentTick;
        }

        if (!squad.hasPrimaryTarget()) {
            return TacticalOrder.none();
        }

        ensureFrontline(mob, squad);

        var role = squad.roles.computeIfAbsent(mob.getUUID(), uuid -> pickRole(mob, squad));

        var assignedTarget = squad.roleTargets.getOrDefault(role, squad.primaryTarget());

        if (assignedTarget == null || !assignedTarget.isAlive()) {
            squad.roleTargets.remove(role);
            assignedTarget = squad.primaryTarget();

            if (assignedTarget == null || !assignedTarget.isAlive()) {
                return TacticalOrder.none();
            }
        }

        if (role == TacticalRole.FLANKER) {
            var secondary = squad.secondaryTarget();
            if (secondary != null && secondary.isAlive()) {
                double distSq = mob.position().distanceToSqr(secondary.position());
                assignedTarget = distSq <= FLANKER_PEEL_RANGE_SQ ? secondary : squad.primaryTarget();
            } else {
                assignedTarget = squad.primaryTarget();
            }
        }

        var targetPos = assignedTarget.blockPosition();
        var destination = switch (role) {
            case FRONTLINE -> targetPos;
            case FLANKER -> flankerOffset(mob, targetPos);
            case RETREATING -> mob.blockPosition().offset(-4, 0, -4);
            case SUPPORT -> targetPos.offset(-3, 0, 3);
        };

        if (!squad.reservedPositions.add(destination)) {
            destination = destination.offset(
                mob.getRandom().nextInt(5) - 2,
                0,
                mob.getRandom().nextInt(5) - 2
            );
        }

        return new TacticalOrder(role, assignedTarget, destination, 25);
    }

    /**
     * Computes a lateral offset position for a flanker relative to the target's block position.
     *
     * @param mob       the flanking mob
     * @param targetPos the target's block position
     * @return a block position perpendicular to the mob–target axis, offset by {@link #FLANKER_LATERAL_OFFSET} blocks
     */
    private BlockPos flankerOffset(E mob, BlockPos targetPos) {
        var dx = targetPos.getX() - mob.getBlockX();
        var dz = targetPos.getZ() - mob.getBlockZ();
        var len = Math.max(1, (int) Math.sqrt(dx * dx + dz * dz));

        var perpX = -dz / len;
        var perpZ = dx / len;

        if (perpX == 0 && perpZ == 0)
            perpX = 1;

        return targetPos.offset(perpX * FLANKER_LATERAL_OFFSET, 0, perpZ * FLANKER_LATERAL_OFFSET);
    }

    /**
     * Selects the most under-filled role for {@code mob} based on the current role distribution versus the target
     * ratios.
     *
     * @param mob   the mob needing a role assignment
     * @param squad the shared squad blackboard
     * @return the role the mob should take
     */
    private TacticalRole pickRole(E mob, SquadBlackboard squad) {
        var total = squad.squadSize;
        var counts = new EnumMap<TacticalRole, Integer>(TacticalRole.class);
        for (TacticalRole r : squad.roles.values()) {
            counts.merge(r, 1, Integer::sum);
        }

        var currentFrontline = counts.getOrDefault(TacticalRole.FRONTLINE, 0);
        var currentFlanker = counts.getOrDefault(TacticalRole.FLANKER, 0);
        var currentSupport = counts.getOrDefault(TacticalRole.SUPPORT, 0);

        var wantFrontline = Math.max(1, (int) Math.round(total * RATIO_FRONTLINE));
        var wantFlanker = Math.max(1, (int) Math.round(total * RATIO_FLANKER));
        var wantSupport = Math.max(0, (int) Math.round(total * RATIO_SUPPORT));

        if (currentFrontline < wantFrontline)
            return TacticalRole.FRONTLINE;
        if (currentFlanker < wantFlanker)
            return TacticalRole.FLANKER;
        if (currentSupport < wantSupport)
            return TacticalRole.SUPPORT;

        return TacticalRole.FRONTLINE;
    }

    /**
     * Guarantees that at least one squad member holds the {@link TacticalRole#FRONTLINE} role. If none exist, promotes
     * {@code mob} (or the first already-assigned member) to frontline.
     *
     * @param mob   the current mob (used as the fallback promotion candidate)
     * @param squad the shared squad blackboard
     */
    private void ensureFrontline(E mob, SquadBlackboard squad) {
        if (squad.roles.containsValue(TacticalRole.FRONTLINE)) {
            return;
        }

        var mobId = mob.getUUID();

        if (squad.roles.containsKey(mobId)) {
            squad.roles.put(mobId, TacticalRole.FRONTLINE);
            return;
        }

        var firstAssigned = squad.roles.keySet().stream().findFirst();
        if (firstAssigned.isPresent()) {
            squad.roles.put(firstAssigned.get(), TacticalRole.FRONTLINE);
            return;
        }

        squad.roles.put(mobId, TacticalRole.FRONTLINE);
    }
}
