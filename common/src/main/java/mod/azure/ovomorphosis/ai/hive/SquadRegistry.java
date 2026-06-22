package mod.azure.ovomorphosis.ai.hive;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

import mod.azure.ovomorphosis.ai.util.TargetingUtils;

/**
 * Singleton that manages squad formation, membership, and shared target evaluation for all mobs.
 * <p>
 * Mobs call {@link #getOrJoinSquad} once per tick to retrieve (and lazily create) the {@link SquadBlackboard} they
 * belong to. The registry automatically merges nearby squads, prunes dead members, and periodically re-evaluates target
 * priorities so that all members in a squad focus on the same set of enemies.
 * <p>
 * Returns {@code null} from {@link #getOrJoinSquad} when the squad is smaller than {@link #MIN_SQUAD_SIZE}, signalling
 * that the mob should act solo rather than use squad tactics.
 */
public final class SquadRegistry {

    private static final SquadRegistry INSTANCE = new SquadRegistry();

    /**
     * Returns the global {@code SquadRegistry} instance.
     *
     * @return the singleton registry
     */
    public static SquadRegistry get() {
        return INSTANCE;
    }

    /** Maximum distance (blocks) within which mobs are considered part of the same squad. */
    private static final double SQUAD_RADIUS = 24.0;

    /** Radius (blocks) used when scanning for enemy targets during squad target evaluation. */
    private static final double TARGET_SCAN_RADIUS = 32.0;

    /** Number of ticks between full target re-evaluations for a squad. */
    public static final int TARGET_EVAL_INTERVAL = 40;

    /** Minimum number of living members required for squad tactics to activate. */
    public static final int MIN_SQUAD_SIZE = 2;

    private final Map<UUID, SquadBlackboard> squadToBoard = new HashMap<>();

    private final Map<UUID, Set<UUID>> squadToMembers = new HashMap<>();

    private final Map<UUID, UUID> mobToSquad = new HashMap<>();

    private long lastPruneTick = -1;

    private SquadRegistry() {}

    /**
     * Returns the {@link SquadBlackboard} for the squad {@code mob} belongs to, creating or merging squads as
     * necessary.
     * <p>
     * Also refreshes target priority and updates the mob's Minecraft target each call.
     *
     * @param <E> the mob type
     * @param mob the mob requesting squad membership
     * @return the shared blackboard, or {@code null} if the squad is below {@link #MIN_SQUAD_SIZE}
     */
    public <E extends Mob> SquadBlackboard getOrJoinSquad(E mob) {
        pruneDeadMobs(mob);

        var mobId = mob.getUUID();
        var squadId = mobToSquad.get(mobId);

        if (squadId == null) {
            squadId = findNearbySquad(mob);
        }

        if (squadId == null) {
            squadId = mobId;
            squadToMembers.put(squadId, new HashSet<>(Collections.singleton(mobId)));
            squadToBoard.put(squadId, new SquadBlackboard());
        }

        mobToSquad.put(mobId, squadId);
        squadToMembers.computeIfAbsent(squadId, k -> new HashSet<>()).add(mobId);

        mergeNearbySquads(mob, squadId);

        var board = squadToBoard.get(squadId);
        if (board != null) {
            if (board.targetPriority == null) {
                board.targetPriority = new ArrayList<>();
            }

            if (board.roleTargets == null) {
                board.roleTargets = new EnumMap<>(TacticalRole.class);
            }

            var now = mob.level().getGameTime();
            if (now - board.lastTargetEvalTick >= TARGET_EVAL_INTERVAL) {
                evaluateTargets(mob, squadId, board);
                board.lastTargetEvalTick = now;
            }

            board.targetPriority.removeIf(
                target -> target == null || !target.isAlive() || !TargetingUtils.validTarget(mob).test(target)
            );

            var primary = board.targetPriority.isEmpty() ? null : board.targetPriority.getFirst();

            if (primary != null) {
                mob.setTarget(primary);
            } else {
                var fallbackTarget = mob.getTarget();

                if (TargetingUtils.validTarget(mob).test(fallbackTarget)) {
                    board.targetPriority.addFirst(fallbackTarget);
                    mob.setTarget(fallbackTarget);
                } else {
                    mob.setTarget(null);
                    board.roleTargets.clear();
                }
            }
        }

        var members = squadToMembers.get(squadId);
        if (members == null || members.size() < MIN_SQUAD_SIZE) {
            return null;
        }

        return board;
    }

    /**
     * Removes {@code mob} from its squad, cleaning up its role and any reserved positions. If the squad becomes empty
     * after removal it is dissolved.
     *
     * @param mob the mob to remove
     */
    public void remove(Mob mob) {
        var mobId = mob.getUUID();
        var squadId = mobToSquad.remove(mobId);
        if (squadId == null) {
            return;
        }

        var board = squadToBoard.get(squadId);
        if (board != null) {
            board.roles.remove(mobId);
            board.reservedPositions.remove(mobId);

            if (board.roleTargets != null) {
                board.roleTargets.values()
                    .removeIf(
                        target -> target == null || !target.isAlive()
                    );
            }

            if (board.targetPriority != null) {
                board.targetPriority.removeIf(
                    target -> target == null || !target.isAlive()
                );
            }
        }

        var members = squadToMembers.get(squadId);
        if (members != null) {
            members.remove(mobId);
            if (members.isEmpty()) {
                squadToMembers.remove(squadId);
                squadToBoard.remove(squadId);
            }
        }
    }

    /**
     * Returns the number of living members in {@code mob}'s squad, or {@code 1} if the mob has no squad entry.
     *
     * @param mob the mob to query
     * @return the squad member count
     */
    public int squadSizeFor(Mob mob) {
        var squadId = mobToSquad.get(mob.getUUID());
        if (squadId == null)
            return 1;
        var members = squadToMembers.get(squadId);
        return members == null ? 1 : members.size();
    }

    /**
     * Returns {@code true} if {@code mob} belongs to a squad that meets the {@link #MIN_SQUAD_SIZE} threshold.
     *
     * @param mob the mob to check
     * @return {@code true} if the mob is part of a sufficiently large squad
     */
    public boolean isInSquad(Mob mob) {
        var squadId = mobToSquad.get(mob.getUUID());
        if (squadId == null)
            return false;
        var members = squadToMembers.get(squadId);
        return members != null && members.size() >= MIN_SQUAD_SIZE;
    }

    /**
     * Returns the {@link TacticalRole} currently assigned to {@code mob} within {@code board}, or {@code null} if the
     * mob has not been assigned a role yet.
     *
     * @param mob   the mob to query
     * @param board the squad blackboard the mob belongs to
     * @return the assigned role, or {@code null}
     */
    public TacticalRole getRoleFor(Mob mob, SquadBlackboard board) {
        return board.roles.get(mob.getUUID());
    }

    private <E extends Mob> void evaluateTargets(E mob, UUID squadId, SquadBlackboard board) {
        if (!(mob.level() instanceof ServerLevel serverLevel))
            return;

        var members = squadToMembers.get(squadId);
        if (members == null || members.isEmpty())
            return;

        var centroid = computeCentroid(members, serverLevel);
        if (centroid == null)
            return;

        Set<LivingEntity> candidates = new LinkedHashSet<>();

        for (var memberId : members) {
            var entity = serverLevel.getEntity(memberId);
            if (!(entity instanceof Mob squadMob))
                continue;

            if (TargetingUtils.validTarget(squadMob).test(squadMob.getTarget())) {
                candidates.add(squadMob.getTarget());
            }

            candidates.addAll(
                serverLevel.getEntitiesOfClass(
                    LivingEntity.class,
                    new AABB(entity.blockPosition()).inflate(TARGET_SCAN_RADIUS),
                    e -> TargetingUtils.validTarget(squadMob).test(e)
                )
            );
        }

        List<LivingEntity> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(e -> e.position().distanceToSqr(centroid)));

        board.targetPriority.clear();
        board.targetPriority.addAll(sorted);

        board.roleTargets.clear();
        var primary = board.primaryTarget();
        var secondary = board.secondaryTarget();

        if (primary != null) {
            board.roleTargets.put(TacticalRole.FRONTLINE, primary);
            board.roleTargets.put(TacticalRole.SUPPORT, primary);
            board.roleTargets.put(TacticalRole.RETREATING, primary);
            board.roleTargets.put(TacticalRole.FLANKER, secondary != null ? secondary : primary);
        }
    }

    private Vec3 computeCentroid(Set<UUID> members, ServerLevel level) {
        double x = 0, y = 0, z = 0;
        var count = 0;

        for (var id : members) {
            var e = level.getEntity(id);
            if (e == null || !e.isAlive())
                continue;
            x += e.getX();
            y += e.getY();
            z += e.getZ();
            count++;
        }

        return count == 0 ? null : new Vec3(x / count, y / count, z / count);
    }

    private void pruneDeadMobs(Mob reference) {
        if (!(reference.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        var now = serverLevel.getGameTime();
        if (now == lastPruneTick) {
            return;
        }
        lastPruneTick = now;

        Iterator<Map.Entry<UUID, UUID>> it = mobToSquad.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            var memberId = entry.getKey();
            var squadId = entry.getValue();
            var entity = serverLevel.getEntity(memberId);

            if (entity == null || !entity.isAlive()) {
                it.remove();

                var members = squadToMembers.get(squadId);
                if (members != null) {
                    members.remove(memberId);
                }

                var board = squadToBoard.get(squadId);
                if (board != null) {
                    board.roles.remove(memberId);
                    board.reservedPositions.remove(memberId);

                    if (board.targetPriority != null) {
                        board.targetPriority.removeIf(
                            target -> target == null || !target.isAlive() || !TargetingUtils.validTarget(reference)
                                .test(target)
                        );
                    }

                    if (board.roleTargets != null) {
                        board.roleTargets.values()
                            .removeIf(
                                target -> target == null || !target.isAlive() || !TargetingUtils.validTarget(reference)
                                    .test(target)
                            );
                    }
                }

                if (members != null && members.isEmpty()) {
                    squadToMembers.remove(squadId);
                    squadToBoard.remove(squadId);
                }
            }
        }
    }

    private <E extends Mob> UUID findNearbySquad(E mob) {
        var nearby = mob.level()
            .getEntitiesOfClass(
                mob.getClass(),
                new AABB(mob.blockPosition()).inflate(SQUAD_RADIUS)
            );

        for (var other : nearby) {
            if (other == mob)
                continue;
            var existingSquad = mobToSquad.get(other.getUUID());
            if (existingSquad != null)
                return existingSquad;
        }

        return null;
    }

    private <E extends Mob> void mergeNearbySquads(E mob, UUID targetSquadId) {
        var nearby = mob.level()
            .getEntitiesOfClass(
                mob.getClass(),
                new AABB(mob.blockPosition()).inflate(SQUAD_RADIUS)
            );

        for (var other : nearby) {
            if (other == mob)
                continue;

            var otherSquadId = mobToSquad.get(other.getUUID());
            if (otherSquadId == null || otherSquadId.equals(targetSquadId))
                continue;

            var otherMembers = squadToMembers.remove(otherSquadId);
            var otherBoard = squadToBoard.remove(otherSquadId);

            if (otherMembers != null) {
                var targetMembers = squadToMembers.computeIfAbsent(targetSquadId, k -> new HashSet<>());
                for (var m : otherMembers) {
                    targetMembers.add(m);
                    mobToSquad.put(m, targetSquadId);
                }
            }

            if (otherBoard != null) {
                var targetBoard = squadToBoard.get(targetSquadId);

                if (targetBoard != null) {
                    if (targetBoard.targetPriority == null) {
                        targetBoard.targetPriority = new ArrayList<>();
                    }
                    if (targetBoard.roleTargets == null) {
                        targetBoard.roleTargets = new EnumMap<>(TacticalRole.class);
                    }
                    if (otherBoard.targetPriority == null) {
                        otherBoard.targetPriority = new ArrayList<>();
                    }
                    if (otherBoard.roleTargets == null) {
                        otherBoard.roleTargets = new EnumMap<>(TacticalRole.class);
                    }

                    if (!targetBoard.hasPrimaryTarget()) {
                        targetBoard.targetPriority.addAll(otherBoard.targetPriority);
                        targetBoard.roleTargets.putAll(otherBoard.roleTargets);
                    }
                }
            }
        }
    }
}
