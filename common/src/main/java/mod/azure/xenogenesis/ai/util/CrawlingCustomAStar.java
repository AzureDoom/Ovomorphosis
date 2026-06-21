package mod.azure.xenogenesis.ai.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

import java.util.*;

import mod.azure.xenogenesis.CommonMod;

/**
 * An A* pathfinder that extends {@link CustomAStar} with support for wall-crawling mobs.
 * <p>
 * In addition to ground-walking moves, the neighbor expansion includes climbable positions in all six directions (via
 * {@link MovementUtils#isSafeClimbNode}) when the mob implements {@link WallCrawlingMob} and
 * {@link MovementUtils#canWallCrawl} returns {@code true}.
 * <p>
 * The heuristic applies a heavier penalty for downward vertical movement to encourage mobs to prefer routes that stay
 * on surfaces rather than falling.
 */
public class CrawlingCustomAStar extends CustomAStar {

    /**
     * Runs A* with wall-crawl-aware neighbor expansion from {@code start} to {@code goal}.
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
        var maxSearched = 5000;
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
                var fullPath = filterTransitionNodes(reconstruct(current), level, mob);
                debugParticlePath(mob, fullPath, true);
                return fullPath;
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
            var partialPath = filterTransitionNodes(reconstruct(bestPartial), level, mob);
            debugParticlePath(mob, partialPath, false);
            return partialPath;
        }

        return Collections.emptyList();
    }

    /**
     * Removes ambiguous transition nodes (stair tops that are neither cleanly walkable nor climbable) from the path.
     * The stair-hop in CrawlToTargetAction handles crossing these physically, so keeping them as waypoints only causes
     * the action to stall trying to attach to a wall that isn't there yet.
     */
    private static List<BlockPos> filterTransitionNodes(List<BlockPos> path, Level level, Mob mob) {
        if (path.size() <= 2)
            return path;
        var filtered = new ArrayList<BlockPos>();
        for (var i = 0; i < path.size(); i++) {
            var pos = path.get(i);
            var isWalk = canStandAt(level, mob, pos);
            var isClimb = MovementUtils.isSafeClimbNode(level, mob, pos);
            if (i == path.size() - 1 || isWalk || isClimb) {
                filtered.add(pos);
            }
        }
        if (filtered.isEmpty() && !path.isEmpty()) {
            filtered.add(path.get(path.size() - 1));
        }
        return filtered;
    }

    /**
     * Spawns debug particles along a computed path. Green flame = full path reached goal. Red flame =
     * partial/best-effort path. A white dust particle marks each waypoint node center.
     */
    private static void debugParticlePath(Mob mob, List<BlockPos> path, boolean fullPath) {
        if (!(mob.level() instanceof ServerLevel serverLevel))
            return;
        if (path.isEmpty())
            return;
        if (!CommonMod.getConfig().enablePathfindingDebug)
            return;

        var level = mob.level();

        for (var pos : path) {
            var cx = pos.getX() + 0.5D;
            var cy = pos.getY() + 0.5D;
            var cz = pos.getZ() + 0.5D;

            var isClimb = MovementUtils.isSafeClimbNode(level, mob, pos);
            var isWalk = canStandAt(level, mob, pos);

            // BLUE = climb node, YELLOW = walk node, WHITE = both/unknown
            var markerParticle = isClimb && !isWalk
                ? ParticleTypes.DRIPPING_WATER // blue tint = climb
                : isWalk && !isClimb
                    ? ParticleTypes.FLAME // orange = walk (should not appear in wall route)
                    : ParticleTypes.END_ROD; // white = both or neither

            serverLevel.sendParticles(markerParticle, cx, cy, cz, 3, 0.0D, 0.0D, 0.0D, 0.0D);
        }

        // Green line connecting nodes
        for (var i = 0; i < path.size() - 1; i++) {
            var a = path.get(i);
            var b = path.get(i + 1);
            var ax = a.getX() + 0.5D;
            var ay = a.getY() + 0.5D;
            var az = a.getZ() + 0.5D;
            var bx = b.getX() + 0.5D;
            var by = b.getY() + 0.5D;
            var bz = b.getZ() + 0.5D;
            for (var s = 0; s <= 4; s++) {
                var t = s / 4.0D;
                serverLevel.sendParticles(
                    fullPath ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.SMOKE,
                    ax + (bx - ax) * t,
                    ay + (by - ay) * t,
                    az + (bz - az) * t,
                    1,
                    0.0D,
                    0.0D,
                    0.0D,
                    0.0D
                );
            }
        }
    }

    /**
     * Returns reachable neighbor positions for a mob that may walk on the ground or crawl on walls. Includes
     * ground-walking steps (up to one block up, up to three down) and, for wall-crawling mobs, all six face directions
     * plus high/low wall transitions.
     *
     * @param level the world
     * @param mob   the mob being pathed
     * @param pos   the current foot/cling position
     * @return list of valid neighbor positions
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
                    if (
                        MovementUtils.isSafeClimbNode(level, mob, candidate) && hasClimbClearance(level, mob, candidate)
                    ) {
                        result.add(candidate);
                        break;
                    }
                }
            }

            for (var dir : hDirs) {
                var side = pos.offset(dir[0], 0, dir[1]);
                for (var drop = 1; drop <= 6; drop++) {
                    var candidate = side.below(drop);
                    if (
                        MovementUtils.isSafeClimbNode(level, mob, candidate) && hasClimbClearance(level, mob, candidate)
                    ) {
                        result.add(candidate);
                        break;
                    }
                }
            }
        }

        return result;
    }

    /**
     * Computes the heuristic estimate for crawling mobs. Upward movement is penalized at 1.5× and downward movement at
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
        if (MovementUtils.isSafeClimbNode(level, mob, feet) && hasClimbClearance(level, mob, feet)) {
            result.add(feet);
        }
    }

    /**
     * Returns {@code true} if the mob's full bounding box fits at {@code feet} without clipping any adjacent geometry.
     * This rejects corner nodes beside stair blocks that pass {@link MovementUtils#isSafeClimbNode} but trap the mob
     * when physically occupied.
     */
    private static boolean hasClimbClearance(Level level, Mob mob, BlockPos feet) {
        var halfW = mob.getBbWidth() / 2.0D;

        // Check if the block directly below has a partial top surface (slab, stair top)
        // that would push the mob upward into the climb node space.
        var below = feet.below();
        var belowShape = level.getBlockState(below).getCollisionShape(level, below);
        var belowTopY = belowShape.isEmpty() ? 0.0D : belowShape.max(net.minecraft.core.Direction.Axis.Y);

        // If the block below has a top surface above 0 (e.g. slab = 0.5), the mob's
        // effective floor is higher — test clearance from that elevated Y.
        var bottomY = feet.getY() + belowTopY;

        var mobBox = new net.minecraft.world.phys.AABB(
            feet.getX() + 0.5D - halfW,
            bottomY,
            feet.getZ() + 0.5D - halfW,
            feet.getX() + 0.5D + halfW,
            bottomY + mob.getBbHeight(),
            feet.getZ() + 0.5D + halfW
        );
        return level.noBlockCollision(mob, mobBox);
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

        if (toIsClimbable && !toIsWalkable) {
            var halfW = mob.getBbWidth() / 2.0D;
            var belowTo = to.below();
            var belowShape = level.getBlockState(belowTo).getCollisionShape(level, belowTo);
            var belowTopY = belowShape.isEmpty() ? 0.0D : belowShape.max(net.minecraft.core.Direction.Axis.Y);
            var bottomY = to.getY() + belowTopY;
            var mobBox = new net.minecraft.world.phys.AABB(
                to.getX() + 0.5D - halfW,
                bottomY,
                to.getZ() + 0.5D - halfW,
                to.getX() + 0.5D + halfW,
                bottomY + mob.getBbHeight(),
                to.getZ() + 0.5D + halfW
            );
            if (!level.noBlockCollision(mob, mobBox)) {
                return 9999.0D;
            }
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

            if (dy > 0 && MovementUtils.canWallCrawl(mob)) {
                for (var dir : Direction.values()) {
                    var adj = to.relative(dir);
                    if (MovementUtils.isSafeClimbNode(level, mob, adj) && hasClimbClearance(level, mob, adj)) {
                        return 9999.0D;
                    }
                }
            }
        }

        return cost;
    }
}
