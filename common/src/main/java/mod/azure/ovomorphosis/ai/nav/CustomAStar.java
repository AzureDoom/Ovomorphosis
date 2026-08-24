package mod.azure.ovomorphosis.ai.nav;

import mod.azure.ovomorphosis.ai.util.AiDebugUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.*;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.util.ModTags;

/**
 * A grid-based A* pathfinder for ground-walking mobs.
 * <p>
 * Searches up to 2 000 nodes before returning the best partial path found. Subclasses may override {@link #neighbors},
 * {@link #heuristic}, and {@link #movementCost} to add specialized movement (e.g. wall-crawling — see
 * {@link CrawlingCustomAStar}).
 * <p>
 * All path results are lists of foot-level {@link BlockPos} waypoints.
 */
public class CustomAStar {

    public CustomAStar() {}

    /**
     * An immutable search node used internally by the open-set priority queue.
     *
     * @param pos    the block position this node represents
     * @param g      accumulated movement cost from the start to this node
     * @param f      total estimated cost ({@code g} + heuristic to goal)
     * @param parent the node this was reached from, or {@code null} for the start
     */
    public record Node(
        BlockPos pos,
        double g,
        double f,
        Node parent
    ) {}

    /**
     * Runs A* from {@code start} to {@code goal} and returns an ordered list of waypoints.
     * <p>
     * Returns the best partial path (closest to the goal) when the full path cannot be found within {@code maxSearched}
     * iterations.
     *
     * @param mob        the mob to path for (used for footprint and safety checks)
     * @param start      the starting block position
     * @param goal       the target block position
     * @param maxRange   maximum Manhattan distance from {@code start} any node may be
     * @param goalRadius horizontal radius (blocks) within which the goal is considered reached
     * @return the ordered path as foot-level positions, or an empty list if no path was found
     */
    public static List<BlockPos> findPath(Mob mob, BlockPos start, BlockPos goal, int maxRange, int goalRadius) {
        var level = mob.level();

        var open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
        Map<BlockPos, Double> bestCost = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();

        var startFeet = normalizeFeet(start);
        var goalFeet = normalizeFeet(goal);

        open.add(new Node(startFeet, 0.0D, heuristic(startFeet, goalFeet), null));
        bestCost.put(startFeet, 0.0D);

        var searched = 0;
        var maxSearched = 2000;
        Node bestPartial = null;
        var bestPartialScore = Double.MAX_VALUE;

        while (!open.isEmpty() && searched++ < maxSearched) {
            var current = open.poll();

            var partialScore = heuristic(current.pos(), goalFeet);

            if (
                partialScore < bestPartialScore
                    && !solidlySeparatedVertically(level, current.pos(), goalFeet)
            ) {
                bestPartialScore = partialScore;
                bestPartial = current;
            }

            if (!closed.add(current.pos())) {
                continue;
            }

            if (
                isCloseEnoughToGoal(current.pos(), goalFeet, goalRadius)
                    && !solidlySeparatedVertically(level, current.pos(), goalFeet)
            ) {
                var path = reconstruct(current);
                if (CommonMod.getConfig().enablePathfindingDebug) {
                    for (var i = 0; i < path.size() - 1; i++) {
                        var segFrom = Vec3.atCenterOf(path.get(i));
                        var segTo = Vec3.atCenterOf(path.get(i + 1));
                        AiDebugUtils.sendParticlePath(mob, segFrom, segTo);
                    }
                }
                return path;
            }

            for (var next : neighbors(level, mob, current.pos())) {

                if (closed.contains(next)) {
                    continue;
                }

                if (next.distManhattan(startFeet) > maxRange) {
                    continue;
                }

                var stepCost = movementCost(level, mob, current.pos(), next);

                if (stepCost >= 9999.0D) {
                    continue;
                }

                var newG = current.g() + stepCost;
                var oldG = bestCost.getOrDefault(next, Double.MAX_VALUE);

                if (newG < oldG) {
                    bestCost.put(next, newG);
                    var f = newG + heuristic(next, goalFeet);
                    open.add(new Node(next, newG, f, current));
                }
            }
        }
        if (bestPartial != null && bestPartial.parent() != null) {
            var path = reconstruct(bestPartial);
            if (CommonMod.getConfig().enablePathfindingDebug) {
                for (var i = 0; i < path.size() - 1; i++) {
                    AiDebugUtils.sendParticlePath(mob, Vec3.atCenterOf(path.get(i)), Vec3.atCenterOf(path.get(i + 1)));
                }
            }
            return path;
        }

        return Collections.emptyList();
    }

    /**
     * Returns {@code true} if {@code pos} is within the goal acceptance zone — within {@code goalRadius} blocks
     * horizontally and within two blocks vertically of {@code goal}.
     *
     * @param pos        the position to test
     * @param goal       the goal position
     * @param goalRadius the horizontal acceptance radius in blocks
     * @return {@code true} if the position satisfies the goal condition
     */
    public static boolean isCloseEnoughToGoal(BlockPos pos, BlockPos goal, int goalRadius) {
        var dx = pos.getX() - goal.getX();
        var dz = pos.getZ() - goal.getZ();

        return dx * dx + dz * dz <= goalRadius * goalRadius
            && Math.abs(pos.getY() - goal.getY()) <= 2;
    }

    /**
     * Returns {@code true} if a solid block sits between {@code pos} and {@code goal} in the goal's vertical column. A*
     * accepts a goal within +/-2 blocks vertically ({@link #isCloseEnoughToGoal}), which lets a short mob (facehugger,
     * chestburster) stop on the ground directly beneath a target standing on a raised/floating platform and declare
     * success under the floor. This guard rejects such arrivals — both as a final result and as the "closest so far"
     * partial-path fallback — so the search keeps looking for (or reports failure to find) a route that actually
     * reaches the goal's level, instead of silently stopping under solid geometry.
     */
    protected static boolean solidlySeparatedVertically(Level level, BlockPos pos, BlockPos goal) {
        if (pos.getY() == goal.getY()) {
            return false;
        }
        var loY = Math.min(pos.getY(), goal.getY());
        var hiY = Math.max(pos.getY(), goal.getY());
        for (var y = loY; y < hiY; y++) {
            var check = new BlockPos(goal.getX(), y, goal.getZ());
            if (!level.getBlockState(check).getCollisionShape(level, check).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reconstructs the path by walking parent pointers from {@code node} back to the start.
     *
     * @param node the terminal node of the found path
     * @return an ordered list of positions from start (inclusive) to this node (inclusive)
     */
    public static List<BlockPos> reconstruct(Node node) {
        LinkedList<BlockPos> result = new LinkedList<>();

        var current = node;
        while (current != null) {
            result.addFirst(current.pos());
            current = current.parent();
        }

        return result;
    }

    /**
     * Computes the heuristic cost estimate (weighted Manhattan distance) between two positions. Vertical distance is
     * weighted by 1.5× to discourage unnecessary height changes.
     *
     * @param a the start position
     * @param b the goal position
     * @return the estimated cost
     */
    public static double heuristic(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX())
            + Math.abs(a.getY() - b.getY()) * 1.5D
            + Math.abs(a.getZ() - b.getZ());
    }

    /**
     * Returns the walkable neighbor positions reachable from {@code pos} for a ground-walking mob. Considers one block
     * up and up to three blocks down in each cardinal direction.
     *
     * @param level the world
     * @param mob   the mob being pathed
     * @param pos   the current foot position
     * @return list of valid neighbor positions the mob can step to
     */
    public static List<BlockPos> neighbors(Level level, Mob mob, BlockPos pos) {
        List<BlockPos> result = new ArrayList<>();

        int[][] dirs = {
            { 1, 0 },
            { -1, 0 },
            { 0, 1 },
            { 0, -1 }
        };

        for (var dir : dirs) {
            var base = pos.offset(dir[0], 0, dir[1]);

            tryAdd(level, mob, result, base);

            tryAdd(level, mob, result, base.above());

            for (int drop = 1; drop <= 3; drop++) {
                tryAdd(level, mob, result, base.below(drop));
            }
        }

        return result;
    }

    private static void tryAdd(Level level, Mob mob, List<BlockPos> result, BlockPos feet) {
        if (canStandAt(level, mob, feet)) {
            result.add(feet);
        }
    }

    /**
     * Returns {@code true} if the mob can stand at {@code feet} — delegates to the full footprint check via
     * {@link #isSafeForMobFootprint}.
     *
     * @param level the world
     * @param mob   the mob being evaluated
     * @param feet  the candidate foot position
     * @return {@code true} if the mob's full bounding box fits safely at this position
     */
    public static boolean canStandAt(Level level, Mob mob, BlockPos feet) {
        return isSafeForMobFootprint(level, mob, feet);
    }

    /**
     * Computes the movement cost of stepping from {@code from} to {@code to}.
     * <p>
     * Returns {@code 9999.0} (effectively impassable) if the destination is unsafe. Climbing adds 1.5 to cost;
     * descending adds 0.5. Adjacent danger blocks add a 4-block penalty each to discourage walking near hazards.
     *
     * @param level the world
     * @param mob   the mob being pathed
     * @param from  the current foot position
     * @param to    the candidate next foot position
     * @return the movement cost, or {@code 9999.0} if the step is invalid
     */
    public static double movementCost(Level level, Mob mob, BlockPos from, BlockPos to) {
        if (!MovementUtils.isSafeBlock(level, to)) {
            return 9999.0D;
        }

        var toState = level.getBlockState(to);
        var inFluid = !toState.getFluidState().isEmpty();
        if (!inFluid && !MovementUtils.isSafeBlock(level, to.below())) {
            return 9999.0D;
        }

        var cost = 1.0D;

        var dy = to.getY() - from.getY();

        if (inFluid) {
            cost += 2.0D;
        }

        if (dy > 0) {
            cost += 1.5D;
        } else if (dy < 0) {
            cost += 0.5D;
        }

        var dangerPaddingBlocks = Math.max(1, Mth.ceil(mob.getBbWidth() / 2.0D));

        for (
            var near : BlockPos.betweenClosed(
                to.offset(-dangerPaddingBlocks, -1, -dangerPaddingBlocks),
                to.offset(dangerPaddingBlocks, 1, dangerPaddingBlocks)
            )
        ) {
            if (!MovementUtils.isSafeBlock(level, near)) {
                cost += 4.0D;
            }
        }

        return cost;
    }

    /**
     * Normalizes a block position to foot level. Currently, returns the position unchanged; subclasses may override to
     * snap to a different reference point.
     *
     * @param pos the position to normalize
     * @return the normalized foot position
     */
    public static BlockPos normalizeFeet(BlockPos pos) {
        return pos;
    }

    private static boolean isSafeForMobFootprint(Level level, Mob mob, BlockPos feet) {
        var padding = 0.02D;
        var radius = (mob.getBbWidth() / 2.0D) + padding;

        var centerX = feet.getX() + 0.5D;
        var centerZ = feet.getZ() + 0.5D;

        var minX = Mth.floor(centerX - radius);
        var maxX = Mth.floor(centerX + radius);
        var minZ = Mth.floor(centerZ - radius);
        var maxZ = Mth.floor(centerZ + radius);

        for (var x = minX; x <= maxX; x++) {
            for (var z = minZ; z <= maxZ; z++) {
                var checkFeet = new BlockPos(x, feet.getY(), z);
                var checkHead = checkFeet.above();

                if (!MovementUtils.isSafeBlock(level, checkFeet)) {
                    return false;
                }

                if (!MovementUtils.isSafeBlock(level, checkHead)) {
                    return false;
                }

                if (
                    !level.getBlockState(checkFeet).getCollisionShape(level, checkFeet).isEmpty()
                        && !level.getBlockState(checkFeet).is(ModTags.RESIN)
                ) {
                    return false;
                }

                if (
                    !level.getBlockState(checkHead).getCollisionShape(level, checkHead).isEmpty()
                        && !level.getBlockState(checkHead).is(ModTags.RESIN)
                ) {
                    return false;
                }
            }
        }

        var feetState = level.getBlockState(feet);
        var isInFluid = !feetState.getFluidState().isEmpty();
        var below = feet.below();
        return isInFluid || !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
    }
}
