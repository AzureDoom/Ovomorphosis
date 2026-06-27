package mod.azure.ovomorphosis.ai.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

import mod.azure.ovomorphosis.CommonMod;

public class CrawlingCustomAStar extends CustomAStar {

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
        var maxSearched = 1500;
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

            for (var next : neighbors(level, mob, current.pos(), goalFeet)) {

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

    private static List<BlockPos> filterTransitionNodes(List<BlockPos> path, Level level, Mob mob) {
        if (path.size() <= 2)
            return path;
        var filtered = new ArrayList<BlockPos>();
        for (var i = 0; i < path.size(); i++) {
            var pos = path.get(i);
            var isWalk = canStandAt(level, mob, pos);
            var isClimb = MovementUtils.isSafeClimbNode(level, pos);
            var isTunnel = tunnelCanStandAt(level, mob, pos);
            if (i == path.size() - 1 || isWalk || isClimb || isTunnel) {
                filtered.add(pos);
            }
        }
        if (filtered.isEmpty() && !path.isEmpty()) {
            filtered.add(path.getLast());
        }
        return filtered;
    }

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

            var isClimb = MovementUtils.isSafeClimbNode(level, pos);
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

    public static List<BlockPos> neighbors(Level level, Mob mob, BlockPos pos, BlockPos goal) {
        List<BlockPos> result = new ArrayList<>();
        var goalIsBelow = goal.getY() < pos.getY() - 1;

        int[][] dirs = {
            { 1, 0 },
            { -1, 0 },
            { 0, 1 },
            { 0, -1 }
        };

        for (var dir : dirs) {
            var base = pos.offset(dir[0], 0, dir[1]);

            tryAddWalk(level, mob, result, base);

            if (!goalIsBelow) {
                tryAddWalk(level, mob, result, base.above());
            }

            for (var drop = 1; drop <= 3; drop++) {
                tryAddWalk(level, mob, result, base.below(drop));
            }

            tryAddTunnelWalk(level, mob, result, base);

            if (!goalIsBelow) {
                tryAddTunnelWalk(level, mob, result, base.above());
            }

            for (var drop = 1; drop <= 3; drop++) {
                tryAddTunnelWalk(level, mob, result, base.below(drop));
            }
        }

        for (var drop = 1; drop <= 8; drop++) {
            var candidate = pos.below(drop);
            if (!level.getBlockState(candidate).getCollisionShape(level, candidate).isEmpty())
                break;
            if (tunnelCanStandAt(level, mob, candidate) && !result.contains(candidate)) {
                result.add(candidate);
                break;
            }
        }

        if (MovementUtils.canWallCrawl(mob)) {
            var posIsTunnel = tunnelCanStandAt(level, mob, pos);
            for (var dir : Direction.values()) {
                var next = pos.relative(dir);

                if (goalIsBelow && next.getY() > pos.getY()) {
                    continue;
                }

                if (posIsTunnel && next.getY() > pos.getY() && !tunnelCanStandAt(level, mob, next)) {
                    continue;
                }

                tryAddClimb(level, mob, result, next);
            }

            int[][] hDirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };

            if (!posIsTunnel && !goalIsBelow) {
                for (var rise = 1; rise <= 4; rise++) {
                    var candidate = pos.above(rise);
                    if (
                        MovementUtils.isSafeClimbNode(level, candidate)
                            && hasClimbClearance(level, mob, candidate)
                    ) {
                        result.add(candidate);
                        break;
                    }
                }

                for (var dir : hDirs) {
                    var side = pos.offset(dir[0], 0, dir[1]);
                    for (var rise = 1; rise <= 4; rise++) {
                        var candidate = side.above(rise);
                        if (
                            MovementUtils.isSafeClimbNode(level, candidate)
                                && hasClimbClearance(level, mob, candidate)
                        ) {
                            result.add(candidate);
                            break;
                        }
                    }
                }
            }

            for (var dir : hDirs) {
                var side = pos.offset(dir[0], 0, dir[1]);
                for (var drop = 1; drop <= 6; drop++) {
                    var candidate = side.below(drop);
                    if (
                        MovementUtils.isSafeClimbNode(level, candidate) && hasClimbClearance(level, mob, candidate)
                    ) {
                        result.add(candidate);
                        break;
                    }
                }
            }
        }

        return result;
    }

    private static void tryAddTunnelWalk(Level level, Mob mob, List<BlockPos> result, BlockPos feet) {
        if (!result.contains(feet) && tunnelCanStandAt(level, mob, feet)) {
            result.add(feet);
        }
    }

    private static float getEffectiveCrawlHeight(Mob mob) {
        if (mob instanceof WallCrawlingMob) {
            return mob.getBbWidth();
        }
        return mob.getBbHeight();
    }

    private static boolean isPassableForCrawl(Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        if (state.is(Blocks.BARRIER))
            return true;
        if (state.is(Blocks.STRUCTURE_VOID))
            return true;
        return state.getCollisionShape(level, pos).isEmpty();
    }

    public static boolean tunnelCanStandAt(Level level, Mob mob, BlockPos feet) {
        if (!isPassableForCrawl(level, feet))
            return false;
        if (!isTightTunnel(level, feet))
            return false;

        var crawlHeight = getEffectiveCrawlHeight(mob);
        var blocksOccupied = Math.max(1, (int) Math.ceil(crawlHeight));

        for (var dy = 0; dy < blocksOccupied; dy++) {
            var check = feet.above(dy);
            if (!isPassableForCrawl(level, check))
                return false;
            var state = level.getBlockState(check);
            if (
                !state.is(Blocks.BARRIER)
                    && !state.is(Blocks.STRUCTURE_VOID)
                    && !MovementUtils.isSafeBlock(level, check)
            )
                return false;
        }

        var groundBlock = feet.below();
        if (!isPhysicallySolid(level, groundBlock))
            return false;

        var testHalfW = 0.3D;
        var mobBox = new AABB(
            feet.getX() + 0.5D - testHalfW,
            feet.getY(),
            feet.getZ() + 0.5D - testHalfW,
            feet.getX() + 0.5D + testHalfW,
            feet.getY() + crawlHeight,
            feet.getZ() + 0.5D + testHalfW
        );
        return level.noBlockCollision(mob, mobBox);
    }

    public static double heuristic(BlockPos a, BlockPos b) {
        var dx = Math.abs(a.getX() - b.getX());
        var dz = Math.abs(a.getZ() - b.getZ());
        var rawDy = b.getY() - a.getY();
        var yPenalty = rawDy < 0 ? Math.abs(rawDy) * 1.0D : rawDy * 3.0D;
        var tieBreak = Math.min(dx, dz) * 0.001;
        return dx + dz + yPenalty + tieBreak;
    }

    private static void tryAddWalk(Level level, Mob mob, List<BlockPos> result, BlockPos feet) {
        if (canStandAt(level, mob, feet)) {
            result.add(feet);
        }
    }

    private static void tryAddClimb(Level level, Mob mob, List<BlockPos> result, BlockPos feet) {
        if (MovementUtils.isSafeClimbNode(level, feet) && hasClimbClearance(level, mob, feet)) {
            result.add(feet);
        }
    }

    private static boolean hasClimbClearance(Level level, Mob mob, BlockPos feet) {
        var below = feet.below();
        var belowShape = level.getBlockState(below).getCollisionShape(level, below);
        var belowTopY = belowShape.isEmpty() ? 0.0D : belowShape.max(Direction.Axis.Y);
        var bottomY = feet.getY() + belowTopY;

        var tight = isTightTunnel(level, feet);
        var fullHalfW = mob.getBbWidth() / 2.0D;
        var testHalfW = tight ? Math.min(fullHalfW, 0.35D) : Math.min(fullHalfW, 0.3D);
        var testHeight = tight ? getEffectiveCrawlHeight(mob) : mob.getBbHeight();

        var mobBox = new AABB(
            feet.getX() + 0.5D - testHalfW,
            bottomY,
            feet.getZ() + 0.5D - testHalfW,
            feet.getX() + 0.5D + testHalfW,
            bottomY + testHeight,
            feet.getZ() + 0.5D + testHalfW
        );
        return level.noBlockCollision(mob, mobBox);
    }

    /**
     * Returns true if the block is physically solid for pathfinding purposes — has a non-empty collision shape. Blocks
     * like grass, flowers, and carpet return false here even though they are not air.
     */
    private static boolean isPhysicallySolid(Level level, BlockPos pos) {
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    public static boolean isTightTunnel(Level level, BlockPos pos) {
        var head = pos.above();

        var eastFeet = isPhysicallySolid(level, pos.east());
        var westFeet = isPhysicallySolid(level, pos.west());
        var eastHead = isPhysicallySolid(level, head.east());
        var westHead = isPhysicallySolid(level, head.west());
        var northFeet = isPhysicallySolid(level, pos.north());
        var southFeet = isPhysicallySolid(level, pos.south());
        var northHead = isPhysicallySolid(level, head.north());
        var southHead = isPhysicallySolid(level, head.south());

        var confinedX = (eastFeet || eastHead) && (westFeet || westHead);
        var confinedZ = (northFeet || northHead) && (southFeet || southHead);
        if (confinedX || confinedZ)
            return true;

        var ceiling = !level.getBlockState(head).getCollisionShape(level, head).isEmpty();
        if (ceiling) {
            return (eastFeet || eastHead) || (westFeet || westHead)
                || (northFeet || northHead) || (southFeet || southHead);
        }

        return false;
    }

    public static double movementCost(Level level, Mob mob, BlockPos from, BlockPos to) {
        if (!MovementUtils.isSafeBlock(level, to)) {
            return 9999.0D;
        }

        var toIsWalkable = canStandAt(level, mob, to);
        var toIsTunnelWalk = !toIsWalkable && (tunnelCanStandAt(level, mob, to)
            || (isTightTunnel(level, to) && canStandAtCrawlSize(level, mob, to)));
        var toIsClimbable = MovementUtils.isSafeClimbNode(level, to);

        if (!toIsWalkable && !toIsTunnelWalk && !toIsClimbable) {
            return 9999.0D;
        }

        if (toIsClimbable && !toIsWalkable && !toIsTunnelWalk) {
            var halfW = mob.getBbWidth() / 2.0D;
            var testHalfW = Math.min(halfW, 0.3D);
            var testHeight = isTightTunnel(level, to) ? getEffectiveCrawlHeight(mob) : mob.getBbHeight();
            var belowTo = to.below();
            var belowShape = level.getBlockState(belowTo).getCollisionShape(level, belowTo);
            var belowTopY = belowShape.isEmpty() ? 0.0D : belowShape.max(net.minecraft.core.Direction.Axis.Y);
            var bottomY = to.getY() + belowTopY;
            var mobBox = new AABB(
                to.getX() + 0.5D - testHalfW,
                bottomY,
                to.getZ() + 0.5D - testHalfW,
                to.getX() + 0.5D + testHalfW,
                bottomY + testHeight,
                to.getZ() + 0.5D + testHalfW
            );
            if (!level.noBlockCollision(mob, mobBox)) {
                return 9999.0D;
            }
        }

        var cost = 1.0D;

        var dy = to.getY() - from.getY();

        if (toIsTunnelWalk) {
            if (dy > 0)
                return 1.5D;
            if (dy < 0)
                return 1.0D;
            return 0.15D;
        }

        if (toIsClimbable && !toIsWalkable) {
            if (dy > 0) {
                cost += 2.0D;
            } else if (dy < 0) {
                cost += 2.0D;
            } else {
                cost += 3.0D;
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

        if (!hasTransitionClearance(level, mob, from, to)) {
            return 9999.0D;
        }

        return cost;
    }

    public static boolean verticalShaftCanCrawlAt(Level level, Mob mob, BlockPos feet) {
        var crawlHeight = getEffectiveCrawlHeight(mob);
        var blocksOccupied = Math.max(1, (int) Math.ceil(crawlHeight));

        for (var dy = 0; dy < blocksOccupied; dy++) {
            var check = feet.above(dy);

            if (!isPassableForCrawl(level, check))
                return false;

            if (!MovementUtils.isSafeBlock(level, check))
                return false;
        }

        var head = feet.above();

        var hasSideWall =
            isPhysicallySolid(level, feet.east()) || isPhysicallySolid(level, feet.west())
                || isPhysicallySolid(level, feet.north()) || isPhysicallySolid(level, feet.south())
                || isPhysicallySolid(level, head.east()) || isPhysicallySolid(level, head.west())
                || isPhysicallySolid(level, head.north()) || isPhysicallySolid(level, head.south());

        if (!hasSideWall)
            return false;

        var testHalfW = 0.3D;
        var mobBox = new AABB(
            feet.getX() + 0.5D - testHalfW,
            feet.getY(),
            feet.getZ() + 0.5D - testHalfW,
            feet.getX() + 0.5D + testHalfW,
            feet.getY() + crawlHeight,
            feet.getZ() + 0.5D + testHalfW
        );

        return level.noBlockCollision(mob, mobBox);
    }

    public static boolean canStandAtCrawlSize(Level level, Mob mob, BlockPos feet) {
        var crawlHeight = getEffectiveCrawlHeight(mob);
        var halfW = mob.getBbWidth() / 2.0D;
        var padding = 0.10D;
        var radius = halfW + padding;

        var centerX = feet.getX() + 0.5D;
        var centerZ = feet.getZ() + 0.5D;

        var minX = Mth.floor(centerX - radius);
        var maxX = Mth.floor(centerX + radius);
        var minZ = Mth.floor(centerZ - radius);
        var maxZ = Mth.floor(centerZ + radius);

        for (var x = minX; x <= maxX; x++) {
            for (var z = minZ; z <= maxZ; z++) {
                var checkFeet = new BlockPos(x, feet.getY(), z);
                var checkGround = checkFeet.below();

                if (!MovementUtils.isSafeBlock(level, checkFeet))
                    return false;
                if (!level.getBlockState(checkFeet).getCollisionShape(level, checkFeet).isEmpty())
                    return false;
                if (level.getBlockState(checkGround).getCollisionShape(level, checkGround).isEmpty())
                    return false;

                var blocksToCheck = Math.max(1, (int) Math.ceil(crawlHeight));
                for (var dy = 1; dy < blocksToCheck + 1; dy++) {
                    var checkAbove = checkFeet.above(dy);
                    if (!MovementUtils.isSafeBlock(level, checkAbove))
                        return false;
                    if (!level.getBlockState(checkAbove).getCollisionShape(level, checkAbove).isEmpty())
                        return false;
                }
            }
        }
        return true;
    }

    private static boolean hasTransitionClearance(Level level, Mob mob, BlockPos from, BlockPos to) {
        var fromCenter = Vec3.atBottomCenterOf(from);
        var toCenter = Vec3.atBottomCenterOf(to);

        var halfW = Math.min(mob.getBbWidth() / 2.0D, 0.35D);
        var height = getEffectiveCrawlHeight(mob);
        var dy = to.getY() - from.getY();

        if (dy > 0) {
            var toIsClimb = MovementUtils.isSafeClimbNode(level, to);
            var fromIsClimb = MovementUtils.isSafeClimbNode(level, from);
            var sweepOriginX = (toIsClimb && !fromIsClimb) ? toCenter.x : fromCenter.x;
            var sweepOriginZ = (toIsClimb && !fromIsClimb) ? toCenter.z : fromCenter.z;

            var vertSteps = Math.max(2, dy * 4);
            for (var i = 1; i <= vertSteps; i++) {
                var t = i / (double) vertSteps;
                var p = new Vec3(sweepOriginX, fromCenter.y + dy * t, sweepOriginZ);
                var box = new AABB(
                    p.x - halfW,
                    p.y,
                    p.z - halfW,
                    p.x + halfW,
                    p.y + height,
                    p.z + halfW
                );
                if (!level.noBlockCollision(mob, box)) {
                    return false;
                }
            }

            if (!toIsClimb) {
                var horizDelta = new Vec3(toCenter.x - fromCenter.x, 0.0D, toCenter.z - fromCenter.z);
                var horizSteps = Math.max(2, Mth.ceil(horizDelta.length() * 4.0D));
                for (var i = 1; i <= horizSteps; i++) {
                    var t = i / (double) horizSteps;
                    var p = new Vec3(
                        fromCenter.x + horizDelta.x * t,
                        toCenter.y,
                        fromCenter.z + horizDelta.z * t
                    );
                    var box = new AABB(
                        p.x - halfW,
                        p.y,
                        p.z - halfW,
                        p.x + halfW,
                        p.y + height,
                        p.z + halfW
                    );
                    if (!level.noBlockCollision(mob, box)) {
                        return false;
                    }
                }
            }
            return true;
        }

        var delta = toCenter.subtract(fromCenter);
        var steps = Math.max(2, Mth.ceil(delta.length() * 4.0D));

        for (var i = 1; i <= steps; i++) {
            var t = i / (double) steps;
            var p = fromCenter.add(delta.scale(t));

            var box = new AABB(
                p.x - halfW,
                p.y,
                p.z - halfW,
                p.x + halfW,
                p.y + height,
                p.z + halfW
            );

            if (!level.noBlockCollision(mob, box)) {
                return false;
            }
        }

        return true;
    }
}
