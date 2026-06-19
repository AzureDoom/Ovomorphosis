package mod.azure.xenogenesis.ai.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * An A* pathfinder that extends {@link CustomAStar} with support for wall-crawling mobs.
 * <p>
 * In addition to ground-walking moves, the neighbour expansion includes climbable positions in all six directions (via
 * {@link MovementUtils#isSafeClimbNode}) when the mob implements {@link WallCrawlingMob} and
 * {@link MovementUtils#canWallCrawl} returns {@code true}.
 * <p>
 * The heuristic applies a heavier penalty for downward vertical movement to encourage mobs to prefer routes that stay
 * on surfaces rather than falling.
 */
public class CrawlingCustomAStar extends CustomAStar {

    /**
     * Runs A* with wall-crawl-aware neighbour expansion from {@code start} to {@code goal}.
     *
     * @param mob        the wall-crawling mob to path for
     * @param start      the starting block position
     * @param goal       the target block position
     * @param maxRange   maximum Manhattan distance from {@code start} any node may be
     * @param goalRadius horizontal radius (blocks) within which the goal is considered reached
     * @return the ordered path as foot/cling-level positions, or an empty list if none was found
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

            if (partialScore < bestPartialScore) {
                bestPartialScore = partialScore;
                bestPartial = current;
            }

            if (!closed.add(current.pos())) {
                continue;
            }

            if (isCloseEnoughToGoal(current.pos(), goalFeet, goalRadius)) {
                return reconstruct(current);
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
            return reconstruct(bestPartial);
        }

        return Collections.emptyList();
    }

    /**
     * Returns reachable neighbour positions for a mob that may walk on the ground or crawl on walls. Includes
     * ground-walking steps (up to one block up, up to three down) and, for wall-crawling mobs, all six face directions
     * plus high/low wall transitions.
     *
     * @param level the world
     * @param mob   the mob being pathed
     * @param pos   the current foot/cling position
     * @return list of valid neighbour positions
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

            tryAddWalk(level, mob, result, base);
            tryAddWalk(level, mob, result, base.above());

            for (var drop = 1; drop <= 3; drop++) {
                tryAddWalk(level, mob, result, base.below(drop));
            }
        }

        if (MovementUtils.canWallCrawl(mob)) {
            for (var dir : Direction.values()) {
                var next = pos.relative(dir);
                tryAddClimb(level, mob, result, next);
            }

            int[][] hDirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };

            for (var dir : hDirs) {
                var side = pos.offset(dir[0], 0, dir[1]);
                for (var rise = 1; rise <= 4; rise++) {
                    var candidate = side.above(rise);
                    if (MovementUtils.isSafeClimbNode(level, mob, candidate)) {
                        result.add(candidate);
                        break;
                    }
                }
            }

            for (var dir : hDirs) {
                var side = pos.offset(dir[0], 0, dir[1]);
                for (var drop = 1; drop <= 6; drop++) {
                    var candidate = side.below(drop);
                    if (MovementUtils.isSafeClimbNode(level, mob, candidate)) {
                        result.add(candidate);
                        break;
                    }
                }
            }
        }

        return result;
    }

    /**
     * Computes the heuristic estimate for crawling mobs. Upward movement is penalised at 1.5× and downward movement at
     * 2.5× to prefer routes that hug surfaces over open drops.
     *
     * @param a the current position
     * @param b the goal position
     * @return the estimated cost
     */
    public static double heuristic(BlockPos a, BlockPos b) {
        var dx = Math.abs(a.getX() - b.getX());
        var dz = Math.abs(a.getZ() - b.getZ());
        var rawDy = b.getY() - a.getY();
        var yPenalty = rawDy >= 0 ? rawDy * 1.5D : Math.abs(rawDy) * 2.5D;

        return dx + yPenalty + dz;
    }

    private static void tryAddWalk(Level level, Mob mob, List<BlockPos> result, BlockPos feet) {
        if (canStandAt(level, mob, feet)) {
            result.add(feet);
        }
    }

    private static void tryAddClimb(Level level, Mob mob, List<BlockPos> result, BlockPos feet) {
        if (MovementUtils.isSafeClimbNode(level, mob, feet)) {
            result.add(feet);
        }
    }

    /**
     * Computes the movement cost for a crawling mob stepping from {@code from} to {@code to}.
     * <p>
     * Returns {@code 9999.0} for unsafe destinations. Climbing upward costs 0.25 extra; crawling horizontally costs 0.5
     * extra; descending costs 1.2 extra. Ground-walking steps additionally incur a danger-proximity penalty.
     *
     * @param level the world
     * @param mob   the mob being pathed
     * @param from  the current foot position
     * @param to    the candidate next position
     * @return the movement cost, or {@code 9999.0} if the step is invalid
     */
    public static double movementCost(Level level, Mob mob, BlockPos from, BlockPos to) {
        if (!MovementUtils.isSafeBlock(level, to)) {
            return 9999.0D;
        }

        var toIsWalkable = canStandAt(level, mob, to);
        var toIsClimbable = MovementUtils.isSafeClimbNode(level, mob, to);

        if (!toIsWalkable && !toIsClimbable) {
            return 9999.0D;
        }

        var cost = 1.0D;

        var dy = to.getY() - from.getY();

        if (toIsClimbable && !toIsWalkable) {
            if (dy > 0) {
                cost += 0.25D;
            } else if (dy < 0) {
                cost += 1.2D;
            } else {
                cost += 0.5D;
            }
        } else {
            if (dy > 0) {
                cost += 1.5D;
            } else if (dy < 0) {
                cost += 0.5D;
            }
        }

        if (toIsWalkable && !toIsClimbable) {
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
        }

        return cost;
    }
}
